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

// Standalone host for the Pure compiler — no JVM / GraalVM.
//
// Builds on the parser bundle (grammar/parser.js's loadBundle): it installs the
// PDB-backed metadata bridge (src/pdb) over the full type/function graph (core +
// compiler PDBs) so the translated runtime's execution-time reflection
// (`__metadataRead`, `__metadataSubtypeOf`, …) is answered from the committed
// .pdb graph. It then imports the generated core + compiler JS
// (platforms/javascript/generated, produced by `just generate-all`) and exposes
// `{ parse, compile, printGraph }`.
//
// Same shape as grammar/parser.js's loadParser — one idempotent loader, no JVM;
// the difference is the wider PDB set and the generated compiler modules.

import { fileURLToPath } from "node:url";
import { dirname, join } from "node:path";
import { createStore } from "../pdb/node-store.js";
import { installBridges } from "../pdb/metadata-bridge.js";
import { loadBundle } from "../grammar/parser.js";

const HERE = dirname(fileURLToPath(import.meta.url));   // .../platforms/javascript/src/compiler
const REPO = join(HERE, "../../../..");                 // -> repo root
const SHARED = join(REPO, "shared");
const GEN = join(HERE, "../../generated");              // -> platforms/javascript/generated

// The compiler's two public entry points, under their full-path-mangled names.
const COMPILE = "meta$pure$compiler$compile_PureFile_MANY__CompilationResult_1_";
const PRINT_GRAPH =
    "meta$pure$compiler$test$printer$printCompiledGraph_PackageableElement_MANY__CompilerContext_1__String_1_";

// PDBs that back the metadata bridge (the type/function graph the compiler reads).
const PDBS = ["core.pdb", "core-tests.pdb", "compiler.pdb", "compiler-tests.pdb"];
// Generated JS the compiler depends on, loaded into the shared global scope.
const GEN_MODULES = ["core-metamodel.js", "core-functions.js", "core-ui.js", "compiler.js"];

let loaded = false;
let store = null; // the PDB-backed metadata store (also handed to callers)

/**
 * Load the standalone compiler into the current context (idempotent) and return
 * a `{ parse, compile, printGraph, store }` API.
 *   - parse(sourceId, content) -> PureFile AST
 *   - compile(pureFiles)       -> CompilationResult
 *   - printGraph(elements, ctx)-> the ###CompiledGraph string
 *   - store                    -> the metadata store (for e.g. the round-trip harness)
 */
export async function loadCompiler() {
    if (!loaded) {
        store = createStore(
            join(SHARED, "specification/m3.fbs"),
            PDBS.map((f) => join(SHARED, f)),
        );
        installBridges(store);
        loadBundle();
        for (const m of GEN_MODULES) Object.assign(globalThis, await import(join(GEN, m)));
        loaded = true;
    }
    return {
        parse: (sourceId, content) => globalThis.__pureParseTop(sourceId, content),
        compile: globalThis[COMPILE],
        printGraph: globalThis[PRINT_GRAPH],
        store,
    };
}
