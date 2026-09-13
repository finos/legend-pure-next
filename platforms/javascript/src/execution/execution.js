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

// Execution host for the JavaScript platform — translate Pure code to
// JavaScript IN-PROCESS with the TRANSLATED TRANSLATOR and eval it, no JVM.
//
// The translator (pure/modules/translation/javascript, compiled into
// generated/translator.js + js-lang.js + translation-shared.js) reads the
// elements it translates through the same PDB-backed metadata access the
// compiler host uses. So the full loop is self-hosted: Pure code committed to
// the PDBs -> translated to JS text by translated Pure code -> eval'd into
// this very context -> callable alongside everything else.
//
// Same shape as compiler/host.js's loadCompiler — one idempotent async loader
// returning a small API:
//   - translatePackage(path) -> the bundled JS source for every translatable
//     element under the package (translation::pdb::translatePackageToJs)
//   - evalJs(source)         -> evaluate emitted JS into the shared global
//     scope (export-stripped classic script, like the pdb-reader loading)
//   - call(path, ...args)    -> invoke a translated function by its Pure path
//   - resolveFn(path, arity) -> the translated global for a Pure function
//   - store                  -> the metadata store (for test discovery etc.)
//
// The registry gets an IN-MEMORY MODULE alongside the PDB modules: invoking a
// metadata-backed function proxy (runtime-lib routes `__eval` on a proxy to
// `__metadataInvoke`, the metadata globals route that to the registry) resolves the
// proxy's Pure path to its translated global and calls it — here the
// translated code IS the runtime.

import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { dirname, join, resolve } from "node:path";
import vm from "node:vm";
import { createStore } from "../modules/node-store.js";
import { createInMemoryModule } from "../modules/memory/module.js";
import { installMetadataGlobals } from "../modules/pdb/marshal.js";
import { loadBundle, loadPdbReader } from "../grammar/parser.js";
import { installHostCompileSource } from "./compile-source.js";
import { installHostJsNatives } from "./js-natives.js";

const HERE = dirname(fileURLToPath(import.meta.url));   // .../platforms/javascript/src/execution
const REPO = join(HERE, "../../../..");                 // -> repo root
const SHARED = join(REPO, "shared");
const GEN = join(HERE, "../../generated");              // -> platforms/javascript/generated

// PDBs backing the metadata globals: the code being translated (core +
// core-tests) plus the translator's own metamodels (JS language, translation
// conventions, shared canonicalization) — the translator instanceOf/matches
// against those classes while building its output AST.
// Repo-relative: bootstrap outputs live in shared/, module outputs in each
// module's own build/ dir.
const PDBS = [
    "shared/core.pdb", "shared/core-tests.pdb", "shared/compiler.pdb",
    "pure/modules/language/javascript/build/javascript.pdb",
    "pure/modules/translation/javascript/build/javascript-translation.pdb",
    "pure/modules/translation/javascript/build/javascript-translation-tests.pdb",
    "pure/modules/translation/shared/build/translation-shared.pdb",
];
// Generated JS loaded into the shared global scope: the core library the
// emitted code calls into, the meta::pure::test helpers (package walking,
// PCT discovery, the in-memory adapter), and the translator stack.
const GEN_MODULES = [
    "core-metamodel.js", "core-functions.js", "core-ui.js",
    "test-utils.js", "translation-shared.js", "js-lang.js", "translator.js",
    "compiler.js", // dynamic compilation (__hostCompileSource) runs the translated compiler
];

const TRANSLATE_PACKAGE =
    "meta$external$language$javascript$translation$pdb$translatePackageToJs_String_1__String_1_";

let loaded = false;
let store = null;
// The registry's in-memory module: translated globals are its function store.
// Its resolveFn handles the mangling — a full function path (elementToPath
// output, with signature suffix) resolves exactly; a bare path resolves by
// prefix, with overloads disambiguated by parameter count.
const runtimeModule = createInMemoryModule("runtime");

export function resolveFn(path, arity) {
    const fn = runtimeModule.resolveFn(path, arity);
    if (!fn) {
        throw new Error(`no translated function for ${path}${arity !== undefined ? `/${arity}` : ""} (loaded generated/ JS and eval'd sources have no matching global)`);
    }
    return fn;
}

/** Evaluate emitted JS into the shared global scope (same contract as the
 * generated modules: export-stripped classic script -> global declarations).
 * Returns the script's completion value, so a caller can scope a module in a
 * function expression and get its exports back (js-natives compileModule). */
export function evalJs(source, filename = "translated.js") {
    const text = source.replace(/^export /gm, "");
    evaluatedSources.set(filename, text);
    const result = vm.runInThisContext(text, { filename });
    runtimeModule.invalidate();
    return result;
}

// Source text by the file name V8 reports in a call site: sources evaluated
// above, and the generated modules (imported, so reported as file: URLs).
// runtime-lib maps call sites back to Pure positions through the markers in
// that text (__pureStackFrames).
const evaluatedSources = new Map();
const moduleSources = new Map();
globalThis.__hostSourceText = (fileName) => {
    if (evaluatedSources.has(fileName)) return evaluatedSources.get(fileName);
    if (!String(fileName).startsWith("file:")) return undefined;
    if (!moduleSources.has(fileName)) {
        let text;
        try {
            text = readFileSync(fileURLToPath(fileName), "utf8");
        } catch {
            text = undefined;
        }
        moduleSources.set(fileName, text);
    }
    return moduleSources.get(fileName);
};

/** Invoke a translated function by its Pure path. */
export function call(path, ...args) {
    return resolveFn(path, args.length)(...args);
}

// The compileSource native's host implementation lives in compile-source.js
// (host-agnostic — the browser page installs the same one with its own
// evalJs); this host injects the vm-backed evalJs below.

/**
 * Load the execution host into the current context (idempotent) and return
 * `{ translatePackage, evalJs, call, resolveFn, store }`. `pdbs` replaces the
 * default PDB set (bin/pure-js passes its `--pdb` arguments through).
 */
export async function loadExecution({ pdbs } = {}) {
    if (!loaded) {
        store = createStore(
            join(SHARED, "specification/m3.fbs"),
            pdbs ? pdbs.map((f) => resolve(f)) : PDBS.map((f) => join(REPO, f)),
        );
        store.register(runtimeModule); // __metadataInvoke routes here via the metadata globals
        installMetadataGlobals(store);
        loadBundle();
        loadPdbReader();
        for (const m of GEN_MODULES) Object.assign(globalThis, await import(join(GEN, m)));
        runtimeModule.invalidate();
        installHostCompileSource(store, evalJs);
        // meta::external::language::javascript::{compileModule,execute,drainCompiledSources}
        installHostJsNatives(evalJs, runtimeModule);
        loaded = true;
    }
    return {
        translatePackage: (pkgPath) => globalThis[TRANSLATE_PACKAGE](pkgPath),
        evalJs, call, resolveFn, store,
    };
}
