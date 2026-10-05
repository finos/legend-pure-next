// ©2026 JP Morgan Chase & Co. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

// Browser glue for the standalone Pure compiler + executor. Runs as an ES
// module after the classic <script>s (runtime-lib.js, antlr-bundle.js,
// parser-mappings-bundle.js) have defined the runtime helpers and the parsers;
// the PureRuntime built below installs globalThis.__pureHost, which runtime-lib forwards to.
//
// It backs the metadata globals with the in-browser PDB reader (src/core/compiler/module): fetch
// m3.fbs + the .pdb files, build a ModuleRegistry, build a PureRuntime — the same globals
// the Node host uses, just fed by `fetch` instead of `fs`. Then it imports the
// generated compiler + translator JS (Object.assign onto globalThis, mirroring
// the Node execution host) and wires Run (button or F9):
//   parse -> compile -> translate each element to JS in-process + eval ->
//   call go():Any[*] -> render the values, with the compiled graph
//   (printCompiledGraph) and the generated JavaScript shown below the result.
// Parse/compile errors are highlighted in the source.

import { openZip } from "../../core/compiler/module/pdbModule/zip/zip.js";
import { ModuleRegistry, PdbModule, InMemoryModule } from "../../core/compiler/module/ModuleRegistry.js";
import { translateProgramElements } from "../../core/execution/translate-elements.js";
import { antlrExtension } from "../../core/execution/natives/AntlrExtension.js";
import { compileSourceExtension } from "../../core/execution/natives/CompileSourceExtension.js";
import { NativeRegistry } from "../../core/execution/natives/NativeRegistry.js";
import { PureRuntime } from "../../core/runtime/PureRuntime.js";
import { registerPureLanguage, keywordsFromGrammar, primitivesFromRegistry,
         compileErrorMarkers, parseErrorMarkers } from "./pure-language.js";
import { createConceptTree } from "./concepts.js";
import { createModulePanel } from "./modules-panel.js";
import { createWorkspace } from "./workspace.js";
import { debounce } from "./source-store.js";
import { createProgress, downloadAll, fetchBytesWithProgress } from "./progress.js";
import { EXTENSION_DESCRIPTORS } from "../../core/runtime/LanguageExtensions.js";
import { createConsole } from "./console-panel.js";
import { initSplitters, initTheme, definePureThemes } from "./layout.js";
import { openInspector } from "./inspector.js";
import { createDiagramPanel } from "./diagram/panel.js";
import { unknownSectionHint, unknownSectionName, sectionHeaderLine } from "./section-hint.js";

const REPO = "../../../../..";
const SHARED = `${REPO}/shared`;
const GEN = "../../../generated";

// Generated JS loaded into the shared global scope: the core library the
// emitted code calls into, then the translator stack, then the compiler.
const GEN_MODULES = [
    "pure/compiler/metamodel.js", "pure/grammar/protocol.js", "pure/runtime/functions.js", "ui.js",
    "test.js", "pure/runtime/translator/shared.js", "pure/runtime/translator/javascript.js", "pure/runtime/translator/translation.js",
    "pure/compiler/compiler.js",
    // Every extension's translated Pure, from its descriptor — no extension is named here.
    ...EXTENSION_DESCRIPTORS.flatMap((extension) => extension.jsPaths),
];

// The overload that takes the language extensions, so a `###Diagram` section becomes a real
// element rather than being parsed and dropped. Silent: the console panel prints the errors.
const COMPILE = "meta$pure$compiler$compile_PureFile_MANY__CompilerExtension_MANY__Boolean_1__CompilationResult_1_";
const TO_REPRESENTATION = "meta$pure$functions$string$toRepresentation_Any_1__String_1_";
const GO_PATH = "go__Any_MANY_"; // mangled path of `function go():Any[*]`
// One element's JavaScript, without eval'ing or registering it — what the inspector shows.
const TRANSLATE_ELEMENT =
    "meta$external$language$javascript$translation$pdb$translateElement_PackageableElement_1__String_1_";
const GRAMMAR = `${REPO}/pure/specification/grammar/antlr/m3/M3Lexer.g4`;

const status = document.getElementById("status");
const runBtn = document.getElementById("run");

const panel = createConsole(document.getElementById("console"));
let editor = null;    // the Monaco editor, once its loader has run
let monaco = null;
let tree = null;
let workspace = null;     // the files, and the Monaco editor over them
let progress = null;      // the start-up bar, removed once Ready
let modulePanel = null;   // the Modules tab beside Concepts
let diagramPanel = null;  // the Diagram tab, created once Monaco exists

/** Monaco's AMD loader is a classic script; this resolves once editor.main has loaded. */
function loadMonaco() {
    return new Promise((resolve, reject) => {
        const req = globalThis.require;
        if (typeof req !== "function") {
            reject(new Error("Monaco was not vendored — run `just javascript::web-deps`"));
            return;
        }
        // The worker is started from a blob so the AMD build needs no separate entry file;
        // without it Monaco runs its services on the main thread and logs a warning.
        globalThis.MonacoEnvironment = {
            getWorkerUrl() {
                const base = new URL("../../../build/vs/", location.href).href;
                return URL.createObjectURL(new Blob([
                    `self.MonacoEnvironment = { baseUrl: ${JSON.stringify(base)} };`,
                    `importScripts(${JSON.stringify(base + "base/worker/workerMain.js")});`,
                ], { type: "text/javascript" }));
            },
        };
        req.config({ paths: { vs: new URL("../../../build/vs", location.href).href } });
        req(["vs/editor/editor.main"], () => resolve(globalThis.monaco), reject);
    });
}

async function fetchBytes(url) {
    return fetchBytesWithProgress(url);
}
async function fetchText(url) {
    const r = await fetch(url);
    if (!r.ok) throw new Error(`${url}: HTTP ${r.status}`);
    return r.text();
}

let compileFn;
let runtime = null;  // the PureRuntime built in setup(); Run parses through it
let registry = null; // its ModuleRegistry — the concept tree lists it, clicks resolve through it

// The registry's in-memory module: translated globals (including the ones
// __hostCompileSource evals at Run time) are its function store, so
// evaluate/eval on a compiled function dispatches via __metadataInvoke.
const runtimeModule = new InMemoryModule("runtime");

/** Evaluate emitted JS into the shared global scope — the browser twin of the
 * Node host's vm.runInThisContext (indirect eval = non-strict global scope,
 * so export-stripped function declarations become globals). */
function evalJs(source) {
    (0, eval)(source.replace(/^export /gm, ""));
    runtimeModule.invalidate();
}

// One-time compile that decodes the shared core metadata working set into the
// PDB reader's node cache. The reader is fully lazy, so the FIRST compile of
// anything forces the type-checker to walk ~4k core elements (types,
// generalizations, properties, signatures), each a cold zip-inflate +
// FlatBuffer-decode through the translated Pure reader — ~3.5s, which is the
// entire first-Run cost. Doing it here (during load, behind an honest status)
// makes the user's first Run already warm (~60ms). The snippet exercises the
// common surface (new / map / filter / fold / property access / string + / if /
// comparison) so little core is left cold. compile() alone warms the cache; we
// deliberately do NOT translate/eval it, so it leaves no globals behind.
const WARMUP_SRC = `Class warmup::C { n: String[1]; v: Integer[1]; }
function warmup::go(): Any[*] {
  let xs = [^warmup::C(n='a', v=1), ^warmup::C(n='b', v=2)];
  let m = $xs->map(c | $c.n + ':' + $c.v->toString());
  let f = $xs->filter(c | $c.v > 1)->map(c | $c.n);
  let s = $xs->fold({c, acc | $acc + $c.v}, 0);
  $m->size() + $f->size() + $s + if(true, |1, |2);
}`;

// Yield long enough for the pending status text to paint before the synchronous
// warmup blocks the main thread (double rAF: after layout + one more frame).
const paint = () => new Promise((r) => requestAnimationFrame(() => requestAnimationFrame(r)));

function warmCompilerCache() {
    try {
        compileFn([runtime.parse("warmup", WARMUP_SRC)], runtime.compilerExtensions(), true);
    } catch (e) {
        // Best-effort: if the snippet ever fails to compile, the app still
        // works — the first real Run just pays the cold cost as before.
        console.warn("compiler cache warmup skipped:", e && e.message ? e.message : e);
    }
}

async function setup() {
    // The bar overlays the header's bottom edge and the status line is its label, so there is
    // no progress row to insert and remove — which is what made the page jump on load.
    progress = createProgress({ bar: document.getElementById("startup-bar"), label: status });
    progress.indeterminate("fetching the metamodel schema…");
    // Schema TEXT — the store parses it lazily with the translated Pure parser
    // (pdb/schema/parser.pure), which is only available once the generated compiler
    // modules below have loaded; the first metadata read comes after that.
    const schemaText = await fetchText(`${SHARED}/specification/m3.fbs`);
    registry = new ModuleRegistry(schemaText);

    registry.register(runtimeModule); // __metadataInvoke routes here via the metadata globals
    // Metadata globals (globalThis.__metadata*, PDB-backed), the compileSource hook and
    // parsing through the runtime's language extensions — all on globalThis.__pureHost.
    //
    // WHICH modules are registered is the runtime's business, from the languages' own manifests; HOW
    // they are read is this page's, which is why the loader is passed in. The page fetched and
    // registered them itself until `buildAsync` existed, with its own copy of the two pdb lists.
    runtime = await PureRuntime.builder()
        .withRegistry(registry)
        .withNatives(NativeRegistry.createDefault([compileSourceExtension, antlrExtension]))
        .withEvalJs(evalJs)
        .withRuntimeModule(runtimeModule)
        // ALL the paths in one call, so they download in parallel behind one bar. The archives are the
        // only part of start-up with a real denominator — ~13 MB — so this is where it is determinate.
        // Opening them is deliberately NOT interleaved with the download: openZip reads the central
        // directory, which is cheap but not free, and interleaving would make the byte count stutter for
        // reasons the label cannot explain.
        .withModuleLoader(async (paths) => {
            const archives = await downloadAll(paths.map((f) => `${REPO}/${f}`), progress,
                                               { label: (url) => url.split("/").pop() });
            progress.indeterminate("opening archives…");
            return paths.map((f, i) => new PdbModule(openZip(archives[i]), f));
        })
        .withTranslatedCode(async () => {
            progress.indeterminate("loading the compiler and translator…");
            // SEQUENTIAL: the generated modules are order-dependent, an enum constant resolving its
            // Enumeration as it loads.
            await GEN_MODULES.reduce(async (previous, m) => {
                await previous;
                Object.assign(globalThis, await import(`${GEN}/${m}`));
            }, Promise.resolve());
        })
        .buildAsync();

    compileFn = globalThis[COMPILE];
    if (typeof compileFn !== "function") {
        throw new Error("compiler entry point not found after loading generated JS");
    }

    // The editor, its language (keywords from the grammar, primitives from the graph just
    // loaded) and the concept tree. Monaco comes up AFTER the metadata so the primitive
    // list is real rather than guessed.
    progress.indeterminate("starting the editor…");
    monaco = await loadMonaco();
    registerPureLanguage(monaco, {
        keywords: keywordsFromGrammar(await fetchText(GRAMMAR)),
        primitives: primitivesFromRegistry(registry),
    });
    definePureThemes(monaco);
    // The files. The in-memory module is the workspace, so every edit writes through to it
    // and a reload restores what was there (source-store.js).
    workspace = createWorkspace({
        monaco,
        container: document.getElementById("editor"),
        runtimeModule,
        sample: SAMPLE,
        onFilesChange: () => { refreshTree(); },
        onActiveChange: () => { diagramPanel?.sourceChanged(); refreshTree(); },
        onSaveFailed: () => panel.error("This browser will not remember your files "
            + "(private window, or storage full). Editing still works; a reload will start from the sample."),
        editorOptions: {
            automaticLayout: true,
            minimap: { enabled: false },
            scrollBeyondLastLine: false,
            fontSize: 13,
            tabSize: 2,
            renderWhitespace: "selection",
        },
    });
    editor = workspace.editor;

    editor.addCommand(monaco.KeyCode.F9, () => { if (!runBtn.disabled) run(); });
    editor.addCommand(monaco.KeyMod.Shift | monaco.KeyCode.F9, () => { if (!runBtn.disabled) compileOnly(); });
    // onDidChangeModelContent follows whichever file is open; onDidChangeContent on a single
    // model would go dead the moment you switched files.
    editor.onDidChangeModelContent(() => monaco.editor.setModelMarkers(editor.getModel(), "pure", []));

    // The theme is applied after the editor exists so Monaco switches with the page.
    initTheme(monaco, document.getElementById("theme"));
    initSplitters(document.querySelector("main"));

    // The Diagram tab. The source text is the model — it parses the `###Diagram` section
    // out of the editor and writes every gesture straight back, so Undo, Compile and Run
    // need to know nothing about diagrams.
    diagramPanel = createDiagramPanel({
        host: {
            tabs: document.getElementById("tabs"),
            editorEl: document.getElementById("editor"),
            panelEl: document.getElementById("diagramPanel"),
            canvas: document.getElementById("diagramCanvas"),
            tools: document.getElementById("diagramTools"),
            zoomLabel: document.getElementById("dgZoom"),
            fit: document.getElementById("dgFit"),
            zoomIn: document.getElementById("dgIn"),
            zoomOut: document.getElementById("dgOut"),
        },
        getSource: () => editor.getValue(),
        setSource: (text) => {
            // An undoable edit over the whole model, not setValue — setValue would wipe the
            // undo stack, so a mis-drag could not be undone.
            const model = editor.getModel();
            editor.executeEdits("diagram", [{ range: model.getFullModelRange(), text }]);
        },
        getRegistry: () => registry,
        onDiagramsChanged: () => refreshTree(),
        onOpenType: (path) => { diagramPanel.showCode(); showConcept(path, "Class", runtimeModule.name); },
        onRelayout: () => editor.layout(),
    });
    editor.onDidChangeModelContent(() => diagramPanel.sourceChanged());
    // Keep the Modules tab's line/char counts honest while typing. Debounced, because it
    // reads every file's text; the panel itself declines to redraw over an open name input.
    const refreshModules = debounce(() => modulePanel?.refresh(), 300);
    editor.onDidChangeModelContent(refreshModules);
    // Clean-up is user-initiated and always reports what it did: the diagram is part of the
    // user's source, so nothing may be deleted from it quietly.
    document.getElementById("dgClean").addEventListener("click", () => {
        const removed = diagramPanel.cleanUp();
        if (!removed) return;
        const { removedTypeViews, removedEdges } = removed;
        if (!removedTypeViews && !removedEdges) panel.info("diagram: nothing to clean up");
        else panel.info(`diagram: removed ${removedTypeViews} class${removedTypeViews === 1 ? "" : "es"}`
                        + ` and ${removedEdges} edge${removedEdges === 1 ? "" : "s"} with no counterpart in the graph`);
    });

    tree = createConceptTree(document.getElementById("concepts"), showConcept, inspectConcept,
                             (path) => diagramPanel.addType(path),
                             // Cleared from the concept tab: keep the Modules tab in step.
                             (moduleName) => { if (moduleName === null) modulePanel.clearSelection(); });
    // The left pane's second tab: which archive the graph came from, and what each one
    // declares. Selecting a module filters the concept tree to it.
    modulePanel = createModulePanel(
        document.getElementById("modules"),
        (moduleName) => { tree.setModuleFilter(moduleName); selectSide("concepts"); },
        // The in-memory module's sources ARE the workspace's files, so the panel edits them
        // directly. Opening one shows the Code tab, because that is where editing happens.
        {
            list: () => workspace.files(),
            open: (id) => { workspace.open(id); diagramPanel.showCode(); },
            create: (name) => { workspace.create(name); diagramPanel.showCode(); },
            rename: (id, name) => workspace.rename(id, name),
            remove: (id) => workspace.remove(id),
        });

    document.getElementById("reset").addEventListener("click", () => {
        workspace.reset();
        panel.info("workspace reset to the sample");
        compileOnly();
    });
    const sideTabs = document.getElementById("sideTabs");
    sideTabs.addEventListener("click", (e) => {
        const button = e.target.closest(".tab");
        if (button) selectSide(button.dataset.side);
    });

    refreshTree();
    panel.info(`${registry.elementKinds().length} elements loaded from ${registry.modules.length} modules.`);

    progress.indeterminate("warming the compiler (one-time)…");
    await paint();
    warmCompilerCache();

    status.textContent = "Ready.";
    progress.done();
    runBtn.disabled = false;
    document.getElementById("compile").disabled = false;

    // Compile the sample straight away so the page opens on a compiled graph rather than
    // an empty console — but do NOT execute it: opening a page should not run a program.
    // The warmup above has already paid the cold-decode cost, so this is the warm path.
    compileOnly();
}

/**
 * Everything the tree lists: the compiled graph, plus the `###Diagram` sections of the open
 * source that have not been compiled yet.
 *
 * A COMPILED diagram is an ordinary graph element — the `###Diagram` extension
 * (pure/extensions/diagram) puts it there — so `registry.elementKinds()` lists it like any
 * class, with no change to m3.ttl: `Diagram` is a class in the extension's own PDB, so it
 * shifts no `AnyUnion` discriminant and invalidates no archive or golden.
 *
 * The editor's own names are still merged in, for the diagram a person is typing right now:
 * it exists in the buffer before any compile has seen it, and the tree should show it then.
 * Names the graph already has are dropped, so a compiled diagram is listed once, as a graph
 * element rather than as a buffer one.
 */
function refreshTree() {
    const compiled = registry.elementKinds();
    const known = new Set(compiled.map(([path]) => path));
    const diagrams = (diagramPanel?.names() ?? [])
        .filter((name) => !known.has(name))
        .map((name) => [name, "Diagram", "editor"]);
    tree.setEntries([...compiled, ...diagrams]);
    // Element counts move as the editor's module is recompiled, so the module list is
    // refreshed with the tree rather than only once at start-up.
    modulePanel?.setRegistry(registry);
}

/** Swap the left pane between the concept tree and the module list. */
function selectSide(side) {
    for (const button of document.querySelectorAll("#sideTabs .tab")) {
        button.classList.toggle("selected", button.dataset.side === side);
    }
    document.getElementById("concepts").hidden = side !== "concepts";
    document.getElementById("modules").hidden = side !== "modules";
}

/**
 * A concept clicked in the tree.
 *
 * Elements the editor compiled carry a source position into the text on screen, so
 * clicking one selects its declaration — the tree doubles as an outline of what you are
 * editing. A concept from an archive has a position too, but into a source file this page
 * does not have, so clicking it does nothing for now.
 */
function showConcept(path, kind, module) {
    // A diagram has no source position to jump to — showing it means opening it.
    if (kind === "Diagram") { diagramPanel.show(path); return; }
    if (module !== runtimeModule.name) return;
    // With several files the element may not be in the one on screen, so follow its
    // sourceId first; the range below is meaningless against the wrong file.
    const element = registry.getElement(path);
    // Pure multiplicity crosses into this host as a value OR a one-element array, so the
    // SourceInformation itself has to be unwrapped before its fields are read; and Pure
    // Integers arrive as BigInt, which Monaco refuses to mix with Number ("Cannot mix
    // BigInt and other types"), so each field is coerced.
    const one = (v) => (Array.isArray(v) ? v[0] : v);
    const num = (v) => { const x = one(v); return x === undefined || x === null ? undefined : Number(x); };
    const si = one(element?.sourceInformation);
    const startLine = num(si?.startLine);
    if (!startLine) return;
    const sourceId = one(si.sourceId);
    if (typeof sourceId === "string" && sourceId) {
        diagramPanel.showCode();
        workspace.open(sourceId);
    }
    // Pure's end column is inclusive; Monaco's is exclusive.
    const range = new monaco.Range(startLine, num(si.startColumn) ?? 1,
                                   num(si.endLine) ?? startLine, (num(si.endColumn) ?? 1) + 1);
    editor.revealRangeInCenterIfOutsideViewport(range);
    editor.setSelection(range);
    editor.focus();
}

/**
 * Right-click ▸ Inspect: the element's graph beside the JavaScript it translates to.
 * Translation here is a pure function of the element — nothing is eval'd and nothing joins
 * the graph, so inspecting an archive concept leaves the page exactly as it was.
 */
function inspectConcept(path, kind) {
    const element = registry.getElement(path);
    if (!element) return;
    openInspector({
        path, kind, element,
        translate: () => globalThis[TRANSLATE_ELEMENT]?.(element),
    });
}

/** The source the editor opens with — the textarea's former contents. */
const SAMPLE = `Class test::Person
{
  firstName: String[1];
  lastName: String[1];
}

function test::greet(p: test::Person[1]): String[1]
{
  'Hello, ' + $p.firstName + ' ' + $p.lastName + '!'
}

function go(): Any[*]
{
  let people = [^test::Person(firstName = 'Pierre', lastName = 'Doe'),
                ^test::Person(firstName = 'Ada', lastName = 'Lovelace')];
  $people->map(p | test::greet($p));
}
`;

const since = (t0) => `${Math.round(performance.now() - t0)}ms`;

/**
 * The `###Section` names this runtime can parse — `Pure`, whatever compiler-tests
 * contributes, and `Diagram`. Read from the runtime's own language extensions, so the list
 * is whatever is actually loaded rather than a list kept in step by hand.
 */
function knownSectionNames() {
    const one = (v) => (Array.isArray(v) ? v[0] : v);
    try {
        return runtime.sectionParsers()
            .map((pair) => one(one(pair)?.first))
            .filter((name) => typeof name === "string")
            .sort();
    } catch {
        return [];
    }
}

/** Report a parse failure against the file it happened in, with markers on that file. */
function reportParseFailure(sourceId, content, e) {
    panel.error(`${sourceId}: ${e.message}`);
    const model = workspace.model(sourceId);
    const setMarkers = (ms) => { if (model) monaco.editor.setModelMarkers(model, "pure", ms); };
    // A misspelt `###Section` stops the whole file compiling, and the failure names only the
    // section that does not exist. Say which ones do, and mark the header itself.
    const unknown = unknownSectionName(e);
    if (unknown) {
        for (const line of unknownSectionHint(e, knownSectionNames())) panel.info(line);
        const at = sectionHeaderLine(content, unknown);
        if (at) {
            setMarkers([{
                severity: monaco.MarkerSeverity.Error,
                message: `Unknown section ###${unknown}`,
                startLineNumber: at, startColumn: 1,
                endLineNumber: at, endColumn: unknown.length + 4,
            }]);
        }
    } else if (Array.isArray(e.parseErrors)) {
        setMarkers(parseErrorMarkers(monaco, e.parseErrors, model));
    }
    // Bring the offending file to the front — an error in a file you cannot see is a puzzle.
    workspace.open(sourceId);
}

/**
 * Parse and compile what the editor holds. Returns null when it did not compile, having
 * already reported why — errors in the console and markers in the editor.
 *
 * This is compilation ONLY: no JavaScript is emitted and nothing is eval'd, so a program
 * can be checked without any of it running. `run()` adds those steps.
 */
function compileEditor() {
    panel.clear();
    // Clear markers on EVERY file: an error fixed in one file must not leave its squiggle
    // behind just because the compile now fails in another.
    for (const { id } of workspace.files()) {
        const model = workspace.model(id);
        if (model) monaco.editor.setModelMarkers(model, "pure", []);
    }

    let t0 = performance.now();
    // The whole workspace compiles together — that is what lets one file reference another.
    // The module already holds each file's text (workspace writes through on every edit), so
    // its sources and its elements always describe the same thing.
    const parsedFiles = [];
    for (const { sourceId, content } of workspace.sources()) {
        try {
            parsedFiles.push(runtime.parse(sourceId, content));
        } catch (e) {
            reportParseFailure(sourceId, content, e);
            return null;
        }
    }
    panel.info(`parsed in ${since(t0)}`);

    t0 = performance.now();
    let compiled;
    try {
        compiled = compileFn(parsedFiles, runtime.compilerExtensions(), true);
    } catch (e) {
        panel.error(e && e.stack ? e.stack : String(e));
        return null;
    }
    const errors = (compiled.errors || []).map(String);
    if (errors.length) {
        errors.forEach((e) => panel.error(e));
        const files = workspace.files();
        for (const { id } of files) {
            const model = workspace.model(id);
            if (!model) continue;
            // A compile error's text names the sourceId it came from, so each file gets its
            // own; with a single file there is nothing to attribute and they all belong to it.
            const mine = files.length === 1 ? errors : errors.filter((message) => message.includes(id));
            monaco.editor.setModelMarkers(model, "pure", compileErrorMarkers(monaco, mine));
        }
        return null;
    }
    panel.info(`compiled in ${since(t0)}`);

    // The compile IS the graph now: its elements replace whatever the previous one left in
    // the in-memory module, which is registered in the registry — so the concept tree, and
    // anything else that reads the graph, sees them without waiting for a translation.
    // replaceElements diffs rather than clearing, so the registry's PDB-derived caches
    // survive; clearing them made every recompile pay the cold walk again.
    runtimeModule.replaceElements(compiled.elements || []);

    return {
        elements: compiled.elements || [],
        context: Array.isArray(compiled.context) ? compiled.context[0] : compiled.context,
    };
}

/** Compile and show the graph — nothing is translated, eval'd or executed. */
function compileOnly() {
    const result = compileEditor();
    if (!result) return;
    refreshTree();
    // A compile can add or change properties, which is what fills the diagram's boxes.
    diagramPanel?.graphChanged();
    const n = result.elements.length;
    panel.info(`${n} element${n === 1 ? "" : "s"} in the graph — browse them on the left`);
    panel.info("compiled only — press Run (F9) to execute");
}

// ---- run (button or F9): compile -> translate+eval -> go() -------------------------
function run() {
    const result = compileEditor();
    if (!result) return;
    const { elements } = result;

    // Translate each compiled element to JavaScript in-process and eval it into this
    // page's global scope — the same loop the compileSource host uses; its emissions are
    // exactly what the Generated JavaScript block shows. PLATFORM mode: the editor's
    // compile IS the program, so its elements join the graph and reflection resolves.
    let t0 = performance.now();
    const emitted = translateProgramElements(elements, evalJs, "editor", runtimeModule);
    panel.info(`translated ${emitted.length} element${emitted.length === 1 ? "" : "s"} in ${since(t0)}`);

    // Already in the graph from the compile; refreshed because translation may have added
    // the function stores behind them.
    refreshTree();

    const goEl = elements.find((el) => el && el.__purePath === GO_PATH);
    const goFn = goEl ? runtimeModule.resolveFn(GO_PATH, 0) : null;
    if (!goEl) {
        panel.error("No `function go():Any[*]` found — add one and Run again.");
    } else if (typeof goFn !== "function") {
        panel.error("go() compiled but its translated global was not found.");
    } else {
        // print()/println() reach the panel as they happen (console-panel tees console.log,
        // which is where runtime-lib's __writeOut lands in a browser).
        t0 = performance.now();
        let value, failed = false;
        try {
            value = panel.capture(() => goFn());
        } catch (e) {
            failed = true;
            panel.error("Execution error: " + (e && e.message ? e.message : String(e)));
        }
        if (!failed) {
            const repr = globalThis[TO_REPRESENTATION];
            const vals = value === undefined || value === null ? [] : Array.isArray(value) ? value : [value];
            const rendered = vals.map((v) => { try { return repr(v); } catch { return String(v); } });
            panel.block("Result", rendered.length === 1 ? rendered[0] : `[${rendered.join(", ")}]`);
            panel.ok(`ran in ${since(t0)}`);
        }
    }

}

runBtn.addEventListener("click", run);
document.getElementById("compile").addEventListener("click", compileOnly);

// F9 also works when focus is outside the editor (the editor binds its own).
document.addEventListener("keydown", (e) => {
    if (e.key !== "F9") return;
    e.preventDefault();
    if (runBtn.disabled) return;
    e.shiftKey ? compileOnly() : run();
});

setup().catch((e) => {
    status.textContent = "Setup failed.";
    // Leaving the bar spinning would suggest something is still happening.
    progress?.done();
    panel.error(String(e && e.stack ? e.stack : e));
});
