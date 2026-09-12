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
// module after the classic <script>s (runtime-lib.js + antlr-bundle.js) have
// defined the runtime helpers and globalThis.__pureParseTop.
//
// It backs the metadata bridge with the in-browser PDB reader (src/pdb): fetch
// m3.fbs + the .pdb files, build a registry, installBridges — the same bridge
// the Node host uses, just fed by `fetch` instead of `fs`. Then it imports the
// generated compiler + translator JS (Object.assign onto globalThis, mirroring
// the Node execution host) and wires Run (button or F9):
//   parse -> compile -> translate each element to JS in-process + eval ->
//   call go():Any[*] -> render the values, with the compiled graph
//   (printCompiledGraph) and the generated JavaScript shown below the result.
// Parse/compile errors are highlighted in the source.

import { openZip } from "../src/pdb/zip/zip.js";
import { createRegistry, createPdbModule, createInMemoryModule } from "../src/pdb/modules.js";
import { installBridges } from "../src/pdb/metadata-bridge.js";
import { installHostCompileSource, translateCompiledElements } from "../src/execution/compile-source.js";

const SHARED = "../../../shared";
const GEN = "../generated";

// Same module list as the Node execution host (src/execution/execution.js):
// core + compiler compile user code; javascript/translation-shared/
// javascript-translation are the translator's own metamodels (it
// instanceOf/matches against those classes while building its output AST).
const PDBS = ["core.pdb", "core-tests.pdb", "compiler.pdb", "javascript.pdb", "javascript-translation.pdb", "translation-shared.pdb"];
// Generated JS loaded into the shared global scope: the core library the
// emitted code calls into, then the translator stack, then the compiler.
const GEN_MODULES = [
    "core-metamodel.js", "core-functions.js", "core-ui.js",
    "test-utils.js", "translation-shared.js", "js-lang.js", "translator.js",
    "compiler.js",
];

const COMPILE = "meta$pure$compiler$compile_PureFile_MANY__CompilationResult_1_";
const PRINT_GRAPH =
    "meta$pure$compiler$test$printer$printCompiledGraph_PackageableElement_MANY__CompilerContext_1__String_1_";
const TO_REPRESENTATION = "meta$pure$functions$string$toRepresentation_Any_1__String_1_";
const GO_PATH = "go__Any_MANY_"; // mangled path of `function go():Any[*]`

const out = document.getElementById("out");
const graph = document.getElementById("graph");
const gen = document.getElementById("gen");
const status = document.getElementById("status");
const runBtn = document.getElementById("run");
const src = document.getElementById("src");
const highlights = document.getElementById("highlights");

const SECTIONS = { out: "outSec", graph: "graphSec", gen: "genSec" };
function show(pre, text) {
    pre.textContent = text;
    document.getElementById(SECTIONS[pre.id]).hidden = false;
}
function clearOutputs() {
    for (const [preId, secId] of Object.entries(SECTIONS)) {
        document.getElementById(preId).textContent = "";
        document.getElementById(secId).hidden = true;
    }
}

// ---- error highlighting ------------------------------------------------------
// Plain (header-less) source is what the demo uses; __pureParseTop synthesizes
// the ###Pure header, and BOTH parse and compile positions then map directly to
// textarea lines (1-based). Compile columns are 1-based; antlr4 parse columns
// are 0-based.

const escapeHtml = (s) => s.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;");

// Char offset of (1-based line, 0-based column) in `text`.
function offsetOf(text, line, col0) {
    const lines = text.split("\n");
    let off = 0;
    for (let i = 0; i < line - 1 && i < lines.length; i++) off += lines[i].length + 1;
    return off + col0;
}

function clearHighlights() { highlights.innerHTML = ""; }

// Render the textarea text with <mark> spans over the given [{start,end}] ranges.
function showHighlights(ranges) {
    const text = src.value;
    const clean = ranges
        .map((r) => ({ start: Math.max(0, r.start), end: Math.min(text.length, r.end) }))
        .filter((r) => r.end > r.start)
        .sort((a, b) => a.start - b.start);
    let html = "", pos = 0;
    for (const r of clean) {
        const s = Math.max(r.start, pos);
        if (s >= r.end) continue; // fully covered by a previous (merged) range
        if (s > pos) html += escapeHtml(text.slice(pos, s));
        html += "<mark>" + escapeHtml(text.slice(s, r.end)) + "</mark>";
        pos = r.end;
    }
    html += escapeHtml(text.slice(pos));
    highlights.innerHTML = html;
    highlights.scrollTop = src.scrollTop;
    highlights.scrollLeft = src.scrollLeft;
}

// Compile errors carry "... (at <id>:<sl>c<sc>-<el>c<ec>)" (1-based line+col).
function compileErrorRanges(errors) {
    const text = src.value;
    const re = /\(at [^:)]+:(\d+)c(\d+)-(\d+)c(\d+)\)/g;
    const ranges = [];
    for (const err of errors) {
        let m;
        while ((m = re.exec(err))) {
            const start = offsetOf(text, +m[1], +m[2] - 1);
            const end = offsetOf(text, +m[3], +m[4] - 1) + 1; // end col is inclusive
            ranges.push({ start, end });
        }
    }
    return ranges;
}

// Parse errors give {line (1-based), column (0-based)}; highlight the token there.
function parseErrorRanges(parseErrors) {
    const text = src.value;
    return parseErrors.map(({ line, column }) => {
        const start = offsetOf(text, line, column);
        let end = start;
        while (end < text.length && !/\s/.test(text[end])) end++;
        return { start, end: Math.max(end, start + 1) };
    });
}

src.addEventListener("input", clearHighlights);
src.addEventListener("scroll", () => {
    highlights.scrollTop = src.scrollTop;
    highlights.scrollLeft = src.scrollLeft;
});

// ---- load + compile ----------------------------------------------------------

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

let compileFn, printGraphFn;

// The registry's in-memory module: translated globals (including the ones
// __hostCompileSource evals at Run time) are its function store, so
// evaluate/eval on a compiled function dispatches via __metadataInvoke.
const runtimeModule = createInMemoryModule("runtime");

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
        compileFn([globalThis.__pureParseTop("warmup", WARMUP_SRC)]);
    } catch (e) {
        // Best-effort: if the snippet ever fails to compile, the app still
        // works — the first real Run just pays the cold cost as before.
        console.warn("compiler cache warmup skipped:", e && e.message ? e.message : e);
    }
}

async function setup() {
    status.textContent = "Loading metamodel (m3.fbs + PDBs)…";
    // Schema TEXT — the store parses it lazily with the translated Pure parser
    // (pdb/fbsParser.pure), which is only available once the generated compiler
    // modules below have loaded; the first metadata read comes after that.
    const schemaText = await fetchText(`${SHARED}/specification/m3.fbs`);
    const modules = [];
    for (const f of PDBS) modules.push(createPdbModule(f.replace(/\.pdb$/, ""), openZip(await fetchBytes(`${SHARED}/${f}`))));
    const registry = createRegistry(schemaText, modules);
    registry.register(runtimeModule); // __metadataInvoke routes here via the bridge
    installBridges(registry); // sets globalThis.__metadata* (real, PDB-backed)

    status.textContent = "Loading compiler + translator…";
    for (const m of GEN_MODULES) Object.assign(globalThis, await import(`${GEN}/${m}`));
    runtimeModule.invalidate();
    installHostCompileSource(registry, evalJs);
    compileFn = globalThis[COMPILE];
    printGraphFn = globalThis[PRINT_GRAPH];
    if (typeof compileFn !== "function" || typeof printGraphFn !== "function") {
        throw new Error("compiler entry points not found after loading generated JS");
    }

    // Warm the reader's node cache before enabling Run, so the first Run is
    // fast (~60ms) instead of paying the ~3.5s cold-decode cost on the click.
    status.textContent = "Warming compiler (one-time)…";
    await paint();
    warmCompilerCache();

    status.textContent = "Ready.";
    runBtn.disabled = false;
}

// ---- run (button or F9): parse -> compile -> translate+eval -> go() ----------

function run() {
    clearOutputs();
    clearHighlights();

    let parsed;
    try {
        parsed = globalThis.__pureParseTop("editor", src.value);
    } catch (e) {
        show(out, e.message);
        if (Array.isArray(e.parseErrors)) showHighlights(parseErrorRanges(e.parseErrors));
        return;
    }

    let compiled;
    try {
        compiled = compileFn([parsed]);
    } catch (e) {
        show(out, "Error: " + (e && e.stack ? e.stack : String(e)));
        return;
    }
    const errors = (compiled.errors || []).map(String);
    if (errors.length) {
        show(out, "Compile errors:\n  " + errors.join("\n  "));
        showHighlights(compileErrorRanges(errors));
        return;
    }

    const ctx = Array.isArray(compiled.context) ? compiled.context[0] : compiled.context;
    show(graph, printGraphFn(compiled.elements, ctx));

    // Translate each compiled element to JavaScript in-process and eval it
    // into this page's global scope — the same loop the compileSource host
    // uses; its emissions are exactly what we display. Nothing is registered:
    // the compiled values are self-contained, the boot registry stays
    // immutable.
    const elements = compiled.elements || [];
    const emitted = translateCompiledElements(elements, evalJs, "editor");
    show(gen, emitted.map((e) => e.source).join("\n\n") || "// nothing translatable");

    const goEl = elements.find((el) => el && el.__purePath === GO_PATH);
    if (!goEl) {
        show(out, "No `function go():Any[*]` found — add one and Run again.");
        return;
    }
    const goFn = runtimeModule.resolveFn(GO_PATH, 0);
    if (typeof goFn !== "function") {
        show(out, "go() compiled but its translated global was not found.");
        return;
    }

    // print()/println() write to console.log — mirror them onto the page.
    // Captured strings carry Pure's own newlines (println appends \n, print
    // does not), so join with "" to keep Pure semantics.
    const printed = [];
    const origLog = console.log;
    console.log = (...args) => { printed.push(args.join(" ")); origLog.apply(console, args); };
    let result;
    try {
        result = goFn();
    } catch (e) {
        show(out, printed.join("") + "\nExecution error: " + (e && e.message ? e.message : String(e)));
        return;
    } finally {
        console.log = origLog;
    }

    const repr = globalThis[TO_REPRESENTATION];
    const vals = result === undefined || result === null ? [] : Array.isArray(result) ? result : [result];
    const rendered = vals.map((v) => { try { return repr(v); } catch { return String(v); } });
    show(out, printed.join("") +
        (rendered.length === 1 ? `-> ${rendered[0]}` : `-> [${rendered.join(", ")}]`));
}

runBtn.addEventListener("click", run);
document.addEventListener("keydown", (e) => {
    if (e.key === "F9") {
        e.preventDefault();
        if (!runBtn.disabled) run();
    }
});

setup().catch((e) => {
    status.textContent = "Setup failed.";
    out.textContent = String(e && e.stack ? e.stack : e);
});
