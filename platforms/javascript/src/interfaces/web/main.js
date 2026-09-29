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
import { createConsole } from "./console-panel.js";
import { initSplitters, initTheme, definePureThemes } from "./layout.js";
import { openInspector } from "./inspector.js";

const REPO = "../../../../..";
const SHARED = `${REPO}/shared`;
const GEN = "../../../generated";

// Same module list as the Node runtime (src/core/runtime/load-runtime.js):
// core + compiler compile user code; javascript/translation-shared/
// javascript-translation are the translator's own metamodels (it
// instanceOf/matches against those classes while building its output AST).
// Repo-relative: bootstrap outputs in shared/, module outputs in each module's build/.
const PDBS = [
    "shared/core.pdb", "shared/core-tests.pdb", "shared/compiler.pdb",
    "pure/modules/language/javascript/build/javascript.pdb",
    "pure/modules/translation/javascript/build/javascript-translation.pdb",
    "pure/modules/translation/shared/build/translation-shared.pdb",
];
// Generated JS loaded into the shared global scope: the core library the
// emitted code calls into, then the translator stack, then the compiler.
const GEN_MODULES = [
    "core-metamodel.js", "core-functions.js", "core-ui.js",
    "test-utils.js", "translation-shared.js", "js-lang.js", "translator.js",
    "compiler.js",
];

const COMPILE = "meta$pure$compiler$compile_PureFile_MANY__CompilationResult_1_";
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
    const r = await fetch(url);
    if (!r.ok) throw new Error(`${url}: HTTP ${r.status}`);
    return new Uint8Array(await r.arrayBuffer());
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
        compileFn([runtime.parse("warmup", WARMUP_SRC)]);
    } catch (e) {
        // Best-effort: if the snippet ever fails to compile, the app still
        // works — the first real Run just pays the cold cost as before.
        console.warn("compiler cache warmup skipped:", e && e.message ? e.message : e);
    }
}

async function setup() {
    status.textContent = "Loading metamodel (m3.fbs + PDBs)…";
    // Schema TEXT — the store parses it lazily with the translated Pure parser
    // (pdb/schema/parser.pure), which is only available once the generated compiler
    // modules below have loaded; the first metadata read comes after that.
    const schemaText = await fetchText(`${SHARED}/specification/m3.fbs`);
    registry = new ModuleRegistry(schemaText);
    for (const f of PDBS) registry.register(new PdbModule(openZip(await fetchBytes(`${REPO}/${f}`)), f));
    registry.register(runtimeModule); // __metadataInvoke routes here via the metadata globals
    registry.validate();
    // Metadata globals (globalThis.__metadata*, PDB-backed), the compileSource hook and
    // parsing through the runtime's language extensions — all on globalThis.__pureHost.
    runtime = PureRuntime.builder()
        .withRegistry(registry)
        .withNatives(NativeRegistry.createDefault([compileSourceExtension, antlrExtension]))
        .withEvalJs(evalJs)
        .withRuntimeModule(runtimeModule)
        .build();

    status.textContent = "Loading compiler + translator…";
    for (const m of GEN_MODULES) Object.assign(globalThis, await import(`${GEN}/${m}`));
    runtimeModule.invalidate();
    compileFn = globalThis[COMPILE];
    if (typeof compileFn !== "function") {
        throw new Error("compiler entry point not found after loading generated JS");
    }

    // The editor, its language (keywords from the grammar, primitives from the graph just
    // loaded) and the concept tree. Monaco comes up AFTER the metadata so the primitive
    // list is real rather than guessed.
    status.textContent = "Starting editor…";
    monaco = await loadMonaco();
    registerPureLanguage(monaco, {
        keywords: keywordsFromGrammar(await fetchText(GRAMMAR)),
        primitives: primitivesFromRegistry(registry),
    });
    definePureThemes(monaco);
    editor = monaco.editor.create(document.getElementById("editor"), {
        value: SAMPLE,
        language: "pure",
        automaticLayout: true,
        minimap: { enabled: false },
        scrollBeyondLastLine: false,
        fontSize: 13,
        tabSize: 2,
        renderWhitespace: "selection",
    });
    editor.addCommand(monaco.KeyCode.F9, () => { if (!runBtn.disabled) run(); });
    editor.addCommand(monaco.KeyMod.Shift | monaco.KeyCode.F9, () => { if (!runBtn.disabled) compileOnly(); });
    editor.getModel().onDidChangeContent(() => monaco.editor.setModelMarkers(editor.getModel(), "pure", []));

    // The theme is applied after the editor exists so Monaco switches with the page.
    initTheme(monaco, document.getElementById("theme"));
    initSplitters(document.querySelector("main"));

    tree = createConceptTree(document.getElementById("concepts"), showConcept, inspectConcept);
    tree.setEntries(registry.elementKinds());
    panel.info(`${registry.elementKinds().length} elements loaded from ${PDBS.length} archives.`);

    status.textContent = "Warming compiler (one-time)…";
    await paint();
    warmCompilerCache();

    status.textContent = "Ready.";
    runBtn.disabled = false;
    document.getElementById("compile").disabled = false;

    // Compile the sample straight away so the page opens on a compiled graph rather than
    // an empty console — but do NOT execute it: opening a page should not run a program.
    // The warmup above has already paid the cold-decode cost, so this is the warm path.
    compileOnly();
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
    if (module !== runtimeModule.name) return;
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
 * Parse and compile what the editor holds. Returns null when it did not compile, having
 * already reported why — errors in the console and markers in the editor.
 *
 * This is compilation ONLY: no JavaScript is emitted and nothing is eval'd, so a program
 * can be checked without any of it running. `run()` adds those steps.
 */
function compileEditor() {
    panel.clear();
    const model = editor.getModel();
    const setMarkers = (ms) => monaco.editor.setModelMarkers(model, "pure", ms);
    setMarkers([]);

    let t0 = performance.now();
    let parsed;
    try {
        parsed = runtime.parse("editor", editor.getValue());
    } catch (e) {
        panel.error(e.message);
        if (Array.isArray(e.parseErrors)) setMarkers(parseErrorMarkers(monaco, e.parseErrors, model));
        return null;
    }
    panel.info(`parsed in ${since(t0)}`);

    t0 = performance.now();
    let compiled;
    try {
        compiled = compileFn([parsed]);
    } catch (e) {
        panel.error(e && e.stack ? e.stack : String(e));
        return null;
    }
    const errors = (compiled.errors || []).map(String);
    if (errors.length) {
        errors.forEach((e) => panel.error(e));
        setMarkers(compileErrorMarkers(monaco, errors));
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
    tree.setEntries(registry.elementKinds());
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
    tree.setEntries(registry.elementKinds());

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
    panel.error(String(e && e.stack ? e.stack : e));
});
