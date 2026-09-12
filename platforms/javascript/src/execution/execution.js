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
// elements it translates through the same PDB-backed metadata bridge the
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
// `__metadataInvoke`, the bridge routes that to the registry) resolves the
// proxy's Pure path to its translated global and calls it — here the
// translated code IS the runtime.

import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { dirname, join } from "node:path";
import vm from "node:vm";
import { createStore } from "../pdb/node-store.js";
import { createInMemoryModule } from "../pdb/modules.js";
import { installBridges } from "../pdb/metadata-bridge.js";
import { loadBundle, loadPdbReader } from "../grammar/parser.js";
import { installHostCompileSource } from "./compile-source.js";

const HERE = dirname(fileURLToPath(import.meta.url));   // .../platforms/javascript/src/execution
const REPO = join(HERE, "../../../..");                 // -> repo root
const SHARED = join(REPO, "shared");
const GEN = join(HERE, "../../generated");              // -> platforms/javascript/generated

// PDBs backing the metadata bridge: the code being translated (core +
// core-tests) plus the translator's own metamodels (JS language, translation
// conventions, shared canonicalization) — the translator instanceOf/matches
// against those classes while building its output AST.
const PDBS = ["core.pdb", "core-tests.pdb", "compiler.pdb", "javascript.pdb", "javascript-translation.pdb", "translation-shared.pdb"];
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
 * generated modules: export-stripped classic script -> global declarations). */
export function evalJs(source, filename = "translated.js") {
    vm.runInThisContext(source.replace(/^export /gm, ""), { filename });
    runtimeModule.invalidate();
}

/** Invoke a translated function by its Pure path. */
export function call(path, ...args) {
    return resolveFn(path, args.length)(...args);
}

// The compileSource native's host implementation lives in compile-source.js
// (host-agnostic — the browser page installs the same one with its own
// evalJs); this host injects the vm-backed evalJs below.

/**
 * Load the execution host into the current context (idempotent) and return
 * `{ translatePackage, evalJs, call, resolveFn, store }`.
 */
export async function loadExecution() {
    if (!loaded) {
        store = createStore(
            join(SHARED, "specification/m3.fbs"),
            PDBS.map((f) => join(SHARED, f)),
        );
        store.register(runtimeModule); // __metadataInvoke routes here via the bridge
        installBridges(store);
        loadBundle();
        loadPdbReader();
        for (const m of GEN_MODULES) Object.assign(globalThis, await import(join(GEN, m)));
        runtimeModule.invalidate();
        installHostCompileSource(store, evalJs);
        loaded = true;
    }
    return {
        translatePackage: (pkgPath) => globalThis[TRANSLATE_PACKAGE](pkgPath),
        evalJs, call, resolveFn, store,
    };
}
