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

// Standalone host for the Pure JavaScript parser — no JVM / GraalVM.
//
// Loads the self-contained parser bundle that translation/javascript builds:
//   - runtime-lib.js          shared runtime helpers (__ctorScope, __map, …)
//   - build/antlr-bundle.js   antlr4 + generated JS parsers + the ANTLR-bridge
//                             natives (parser half — no Pure/PDB dependency).
//   - build/parser-mappings-bundle.js
//                             the translated `parser-mappings` + the
//                             `globalThis.__pureParseTop(sourceId, content)`
//                             entry point. Split from the parser half so the
//                             parser half can be built without PDBs, without
//                             it; both are needed here. Load order matters.
//
// The parser-mappings run `cast(@meta::pure::protocol::grammar::…)` checks while
// building the AST; those resolve through `__metadataSubtypeOf`. On the JVM the
// metamodel answers them — here we back the same metadata globals with the PDB
// reader (src/modules), reading the protocol-grammar types from core.pdb. So JS
// parsing carries the same metadata dependency the Truffle host has, with no
// hand-maintained subtype table.
//
// The PDB decoding itself is done by the TRANSLATED SELF-HOSTED PURE READER
// (pdb/reader/reader.pure). The compiler host gets it as part of generated/compiler.js;
// this parser-only host loads the small generated/pdb-reader.js instead
// (`just javascript::generate-pdb-reader`), so it still doesn't depend on the
// whole generated compiler.

import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { dirname, join } from "node:path";
import vm from "node:vm";
import { createStore } from "../modules/node-store.js";
import { installMetadataGlobals } from "../modules/pdb/marshal.js";

const HERE = dirname(fileURLToPath(import.meta.url));
const SHARED = join(HERE, "../../../../shared");
const BUNDLE_DIR = join(HERE, "../../../../pure/modules/translation/javascript/js");
const RUNTIME_LIB = join(BUNDLE_DIR, "runtime-lib.js");
const ANTLR_BUNDLE = join(BUNDLE_DIR, "build/antlr-bundle.js");
const PARSER_MAPPINGS_BUNDLE = join(BUNDLE_DIR, "build/parser-mappings-bundle.js");
const PDB_READER = join(HERE, "../../generated/pdb-reader.js");

function readBundleFile(path, buildHint = "just modules::translation_javascript::antlr-all") {
    try {
        return readFileSync(path, "utf8");
    } catch (e) {
        throw new Error(
            `Pure parser bundle not found at:\n  ${path}\n` +
            `Build it first:\n  ${buildHint}\n` +
            `(${e.message})`,
        );
    }
}

// Load runtime-lib + antlr-bundle into the current context (idempotent). Shared
// by loadParser and loadCompiler — both need the same bundle globals in scope.
let bundleLoaded = false;
export function loadBundle() {
    if (bundleLoaded) return;
    // Order matters twice over: runtime-lib defines the global helpers the
    // parser-mappings reference by bare name, and the mappings bundle calls
    // `__parseAntlr` — published by the antlr bundle — so it must come last.
    vm.runInThisContext(readBundleFile(RUNTIME_LIB), { filename: "runtime-lib.js" });
    vm.runInThisContext(readBundleFile(ANTLR_BUNDLE), { filename: "antlr-bundle.js" });
    vm.runInThisContext(readBundleFile(PARSER_MAPPINGS_BUNDLE), { filename: "parser-mappings-bundle.js" });
    if (typeof globalThis.__pureParseTop !== "function") {
        throw new Error("__pureParseTop was not defined after loading the parser bundle");
    }
    bundleLoaded = true;
}

// Load the translated Pure PDB reader as a classic script (idempotent):
// stripping the `export ` prefixes turns the module into top-level function
// declarations, which bind to globalThis — the same composition contract as
// loadCompiler's Object.assign(globalThis, await import(...)). Skipped when
// the compiler host already brought the same functions in via compiler.js.
// Shared with the execution host (src/execution), which needs the reader for
// its PDB-backed metadata access without the whole generated compiler.
let pdbReaderLoaded = false;
export function loadPdbReader() {
    if (pdbReaderLoaded || typeof globalThis.meta$pure$compiler$pdb$schema$parseFbs_String_1__FbsSchema_1_ === "function") return;
    vm.runInThisContext(
        readBundleFile(PDB_READER, "just javascript::generate-pdb-reader").replace(/^export /gm, ""),
        { filename: "pdb-reader.js" },
    );
    pdbReaderLoaded = true;
}

let parserReady = false;

/**
 * Load the Pure parser into the current context (idempotent) and return a
 * `{ parse }` API. `parse(sourceId, content)` returns the PureFile AST.
 *
 * `pdbPaths` back the metadata globals; core.pdb carries the protocol-grammar
 * types the parser-mapping casts resolve against.
 */
export function loadParser(pdbPaths = [join(SHARED, "core.pdb")]) {
    if (!parserReady) {
        installMetadataGlobals(createStore(join(SHARED, "specification/m3.fbs"), pdbPaths));
        loadBundle();
        loadPdbReader();
        parserReady = true;
    }
    return {
        parse: (sourceId, content) => globalThis.__pureParseTop(sourceId, content),
    };
}
