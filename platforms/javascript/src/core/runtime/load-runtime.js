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
import { EXTENSION_DESCRIPTORS } from "./LanguageExtensions.js";

const HERE = dirname(fileURLToPath(import.meta.url));   // .../platforms/javascript/src/core/runtime
const REPO = join(HERE, "../../../../..");                 // -> repo root
const SHARED = join(REPO, "shared");
const GEN = join(HERE, "../../../generated");              // -> platforms/javascript/generated

// The Pure language and nothing else — the same set the browser IDE loads. This entry point also
// TRANSLATES at run time (bin/pure-js execute, the PCT and spec harnesses go through
// `translatePackage`), which used to mean loading the translator's -tests pdb for its entry point
// and, transitively, core-tests: 5.35 MB of PCT corpus for five lines of package walking. The entry
// point now lives in the lean pdb where it belongs, so neither is needed.
// Generated JS loaded into the shared global scope: the core library the
// emitted code calls into, the meta::pure::test helpers (package walking,
// PCT discovery, the in-memory adapter), and the translator stack.
const GEN_MODULES = [
    "pure/compiler/metamodel.js", "pure/grammar/protocol.js", "pure/runtime/functions.js", "ui.js",
    "test.js", "pure/runtime/translator/shared.js", "pure/runtime/translator/javascript.js", "pure/runtime/translator/translation.js",
    "pure/compiler/compiler.js", // dynamic compilation (__hostCompileSource) runs the translated compiler
    // The TEST halves. This host runs test functions — every `spec-*` recipe goes through the CLI,
    // and the in-process PCT translates and evaluates the corpus — so it needs the elements the
    // library halves leave out. The browser IDE's list (interfaces/web/main.js) deliberately has
    // none of these: they were 1.2 MB of PCT corpus inside functions.js.
    "pure/compiler/metamodel-tests.js", "pure/grammar/protocol-tests.js", "pure/runtime/functions-tests.js",
    "pure/compiler/compiler-tests.js", "pure/runtime/translator/shared-tests.js",
    "pure/runtime/translator/javascript-tests.js", "pure/runtime/translator/translation-tests.js",
    // Every extension's translated Pure, from its descriptor — no extension is named here.
    ...EXTENSION_DESCRIPTORS.flatMap((extension) => extension.jsPaths),
];

/**
 * Load the code translated Pure runs on into the current context (idempotent): runtime-lib and the
 * parser bundles, the translated PDB reader, then the generated modules. A host that builds its own
 * registry and PureRuntime calls this after building the runtime.
 */
export async function loadTranslatedCode(modules = GEN_MODULES) {
    loadBundle();
    loadPdbReader();
    // SEQUENTIAL, because the generated modules are order-dependent: an enum constant resolves its
    // Enumeration as it loads, so the module declaring the class has to have run first.
    await modules.reduce(async (previous, m) => {
        await previous;
        Object.assign(globalThis, await import(join(GEN, m)));
    }, Promise.resolve());
}

let loaded = null;

/**
 * Load the runtime into the current context (idempotent) and return
 * `{ registry, runtime, execution, translatePackage, evalJs, call, resolveFn }`.
 * `pdbs` replaces the default PDB set (bin/pure-js passes its `--pdb` arguments through).
 *
 * `extraPdbs` ADDS to it, repo-relative, for a caller that needs Pure code the language itself does
 * not: the PCT harness runs the corpus in core-tests, which is test content to execute and not part
 * of what it takes to compile and run Pure. Keeping it out of the default is the difference between
 * every host paying 5.35 MB and only the harness that reads it paying.
 */
export async function loadRuntime({ pdbs, extraPdbs = [] } = {}) {
    if (!loaded) {
        const runtimeModule = new InMemoryModule("runtime");
        const execution = new Execution(runtimeModule);

        const registry = new ModuleRegistry(readFileSync(join(SHARED, "specification/m3.fbs"), "utf8"));
        // A caller that named its own `pdbs` chose exactly what to load, so nothing is autoloaded for it
        // — an extension it did not ask for must not appear behind its back. Otherwise only the EXTRA
        // modules are registered here and the runtime brings the rest.
        (pdbs ? pdbs.map((f) => resolve(f)) : extraPdbs.map((f) => join(REPO, f)))
            .forEach((p) => registry.register(PdbModule.open(p)));
        registry.register(runtimeModule); // __metadataInvoke routes here via the metadata globals

        const builder = PureRuntime.builder()
            .withRegistry(registry)
            .withNatives(NativeRegistry.createDefault([...DEFAULT_NATIVES_EXTENSIONS, fileSystemExtension]))
            .withEvalJs(execution.evalJs)
            .withSourceText(execution.sourceText)
            .withRuntimeModule(runtimeModule);

        let runtime;
        if (pdbs) {
            registry.validate();
            runtime = builder.build();
            await loadTranslatedCode();
            runtimeModule.invalidate();
        } else {
            // What the languages declare, the platform's own modules and every extension on disk — all
            // registered by the runtime, which then loads the translated code. Opening a pdb is
            // synchronous here; the browser's loader fetches instead (see interfaces/web/main.js).
            runtime = await builder
                .withModuleLoader(async (paths) => paths.map((f) => PdbModule.open(join(REPO, f))))
                .withTranslatedCode(() => loadTranslatedCode())
                .buildAsync();
        }
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
