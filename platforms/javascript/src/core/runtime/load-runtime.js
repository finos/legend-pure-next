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

// The JavaScript runtime on Node — brings everything together: the PDBs as
// modules behind the ModuleRegistry, the in-memory runtime module, the
// PureRuntime (metadata globals + natives), the parser bundle and translated PDB
// reader, the generated JavaScript, and the Execution that translates,
// evaluates and calls. The browser page wires the same pieces with fetch
// (src/interfaces/web/main.js).
//
// The registry gets an IN-MEMORY MODULE alongside the PDB modules: invoking a
// metadata-backed function proxy (runtime-lib routes `__eval` on a proxy to
// `__metadataInvoke`, the metadata globals route that to the registry) resolves
// the proxy's Pure path to its translated global and calls it — here the
// translated code IS the runtime.

import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { dirname, join, resolve } from "node:path";
import { ModuleRegistry, PdbModule } from "../compiler/module/ModuleRegistry.js";
import { InMemoryModule } from "../compiler/module/inMemoryModule/InMemoryModule.js";
import { loadBundle, loadPdbReader } from "../grammar/load-parser.js";
import { Execution } from "../execution/Execution.js";
import { DEFAULT_NATIVES_EXTENSIONS, NativeRegistry } from "../execution/natives/NativeRegistry.js";
import { fileSystemExtension } from "../execution/natives/FileSystemExtension.js";
import { PureRuntime } from "./PureRuntime.js";

const HERE = dirname(fileURLToPath(import.meta.url));   // .../platforms/javascript/src/core/runtime
const REPO = join(HERE, "../../../../..");                 // -> repo root
const SHARED = join(REPO, "shared");
const GEN = join(HERE, "../../../generated");              // -> platforms/javascript/generated

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

/**
 * Load the code translated Pure runs on into the current context (idempotent): runtime-lib and the
 * parser bundles, the translated PDB reader, then the generated modules. A host that builds its own
 * registry and PureRuntime calls this after building the runtime.
 */
export async function loadTranslatedCode(modules = GEN_MODULES) {
    loadBundle();
    loadPdbReader();
    for (const m of modules) Object.assign(globalThis, await import(join(GEN, m)));
}

let loaded = null;

/**
 * Load the runtime into the current context (idempotent) and return
 * `{ registry, runtime, execution, translatePackage, evalJs, call, resolveFn }`.
 * `pdbs` replaces the default PDB set (bin/pure-js passes its `--pdb` arguments through).
 */
export async function loadRuntime({ pdbs } = {}) {
    if (!loaded) {
        const runtimeModule = new InMemoryModule("runtime");
        const execution = new Execution(runtimeModule);

        const registry = new ModuleRegistry(readFileSync(join(SHARED, "specification/m3.fbs"), "utf8"));
        for (const p of pdbs ? pdbs.map((f) => resolve(f)) : PDBS.map((f) => join(REPO, f))) {
            registry.register(PdbModule.open(p));
        }
        registry.register(runtimeModule); // __metadataInvoke routes here via the metadata globals
        registry.validate();

        const runtime = PureRuntime.builder()
            .withRegistry(registry)
            .withNatives(NativeRegistry.createDefault([...DEFAULT_NATIVES_EXTENSIONS, fileSystemExtension]))
            .withEvalJs(execution.evalJs)
            .withSourceText(execution.sourceText)
            .withRuntimeModule(runtimeModule)
            .build();

        await loadTranslatedCode();
        runtimeModule.invalidate();
        loaded = { registry, runtime, execution };
    }
    const { registry, runtime, execution } = loaded;
    return {
        registry, runtime, execution,
        translatePackage: execution.translatePackage,
        evalJs: execution.evalJs,
        call: execution.call,
        resolveFn: execution.resolveFn,
    };
}
