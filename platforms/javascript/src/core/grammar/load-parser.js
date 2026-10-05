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
//                             the translated `parser-mappings` (parseDocument
//                             and the section parsers). Split from the parser
//                             half so the parser half can be built without PDBs;
//                             both are needed here. Load order matters. The
//                             PureRuntime's parse drives them
//                             (runtime-lib's `__pureParseTop` forwards to it).
//
// The parser-mappings run `cast(@meta::pure::protocol::grammar::…)` checks while
// building the AST; those resolve through `__metadataSubtypeOf`. On the JVM the
// metamodel answers them — here we back the same metadata globals with the PDB
// reader (src/core/compiler/module), reading the protocol-grammar types from core.pdb. So JS
// parsing carries the same metadata dependency the Truffle host has, with no
// hand-maintained subtype table.
//
// The PDB decoding itself is done by the TRANSLATED SELF-HOSTED PURE READER
// (pdb/reader/reader.pure). The compiler host gets it as part of generated/pure/compiler/compiler.js;
// this parser-only host loads the small generated/pure/compiler/pdb.js instead
// (`just javascript::generate-pdb-reader`), so it still doesn't depend on the
// whole generated compiler.

import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { dirname, join } from "node:path";
import vm from "node:vm";
import { ModuleRegistry, PdbModule } from "../compiler/module/ModuleRegistry.js";
import { PureRuntime } from "../runtime/PureRuntime.js";
import { NativeRegistry } from "../execution/natives/NativeRegistry.js";
import { antlrExtension } from "../execution/natives/AntlrExtension.js";

const HERE = dirname(fileURLToPath(import.meta.url));
const SHARED = join(HERE, "../../../../../shared");
// The two parser bundles are this platform's own artifacts. runtime-lib.js is not: it is the
// contract the TRANSLATOR emits against — the 170 `__` helpers its output calls — so it lives with
// the translator and is read from there.
const BUILD = join(HERE, "../../../build");
const ANTLR_BUNDLE = join(BUILD, "antlr-bundle.js");
const PARSER_MAPPINGS_BUNDLE = join(BUILD, "parser-mappings-bundle.js");
const RUNTIME_LIB = join(HERE, "../../../../../pure/modules/translation/javascript/js/runtime-lib.js");
const PDB_READER = join(HERE, "../../../generated/pure/compiler/pdb.js");
// The CLASSES the translated parser constructs. A Pure class is emitted as a JavaScript class and
// `^X(...)` as `new X()._a(1)`, so the module that DEFINES a class has to be loaded in every host
// that runs code constructing it — this one builds 44 protocol classes plus Pair. Without them the
// parser dies on `meta$pure$protocol$grammar$Class is not defined`, which the parser bundle hid for
// a while by being stale (it predated class emission and still built plain object literals).
// ORDER MATTERS: metamodel.js defines the classes the other two build with at LOAD time —
// functions.js evaluates its enumeration consts immediately, and each member is an
// `meta::pure::metamodel::type::Enum` whose classifier is a GenericTypeValue. Loading functions.js
// first left every enum value and classifier as the plain-object fallback, and because a generated
// const only builds once (the `typeof globalThis.X === 'undefined'` guard), re-loading it later
// could not repair them.
const METAMODEL = join(HERE, "../../../generated/pure/compiler/metamodel.js");
const PROTOCOL = join(HERE, "../../../generated/pure/grammar/protocol.js");
const FUNCTIONS = join(HERE, "../../../generated/pure/runtime/functions.js");

function readBundleFile(path, buildHint = "just javascript::antlr-all") {
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
    // The CLASS modules come before the bundles, because a generated module builds values at LOAD
    // time (an enumeration's const, with each member an Enum and its classifier a GenericTypeValue)
    // and those builds have to find their classes. Only runtime-lib has to precede them.
    //
    // GUARDED on a representative class, the way loadPdbReader guards on a representative function:
    // `export ` is stripped, so a `class X {}` lands as a LEXICAL binding in the global scope and a
    // second runInThisContext of the same text is a SyntaxError ("Identifier ... has already been
    // declared"). A host that brought these in already — the compiler host imports them as ES
    // modules — must not have them loaded twice.
    for (const [path, filename, sentinel] of [
        [METAMODEL, "pure/compiler/metamodel.js", "meta$pure$metamodel$type$Enum"],
        [PROTOCOL, "pure/grammar/protocol.js", "meta$pure$protocol$PureFile"],
        [FUNCTIONS, "pure/runtime/functions.js", "meta$pure$functions$collection$Pair"],
    ]) {
        if (typeof globalThis[sentinel] === "function") continue;
        vm.runInThisContext(readBundleFile(path, "just javascript::generate-all").replace(/^export /gm, ""),
                            { filename });
    }
    vm.runInThisContext(readBundleFile(ANTLR_BUNDLE), { filename: "antlr-bundle.js" });
    vm.runInThisContext(readBundleFile(PARSER_MAPPINGS_BUNDLE), { filename: "parser-mappings-bundle.js" });
    // After the bundles, because these only have to be in scope by the time a parse RUNS, and
    // `export ` is stripped for the same reason loadPdbReader strips it: a classic script's
    // top-level declarations bind to globalThis, which is how the bundle's bare class references
    // resolve. (A `class X {}` would bind only lexically, hence the explicit `globalThis.X = X`
    // each generated class carries.)
    if (typeof globalThis.meta$pure$parser$mappings$interpreter$parseDocument_AntlrContext_1__String_1__Boolean_1__Pair_MANY__PureFile_1_ !== "function") {
        throw new Error("parseDocument was not defined after loading the parser-mappings bundle");
    }
    bundleLoaded = true;
}

// Load the translated Pure PDB reader as a classic script (idempotent):
// stripping the `export ` prefixes turns the module into top-level function
// declarations, which bind to globalThis — the same composition contract as
// loadCompiler's Object.assign(globalThis, await import(...)). Skipped when
// the compiler host already brought the same functions in via compiler.js.
// Shared with the runtime (src/core/runtime/load-runtime.js), which needs the reader for
// its PDB-backed metadata access without the whole generated compiler.
let pdbReaderLoaded = false;
export function loadPdbReader() {
    if (pdbReaderLoaded || typeof globalThis.meta$pure$compiler$pdb$schema$parseFbs_String_1__FbsSchema_1_ === "function") return;
    vm.runInThisContext(
        readBundleFile(PDB_READER, "just javascript::generate-pdb-reader").replace(/^export /gm, ""),
        { filename: "pure/compiler/pdb.js" },
    );
    pdbReaderLoaded = true;
}

let parserRuntime = null;

/**
 * Load the Pure parser into the current context (idempotent) and return a
 * `{ parse }` API. `parse(sourceId, content)` returns the PureFile AST.
 *
 * `pdbPaths` back the metadata globals; core.pdb carries the protocol-grammar
 * types the parser-mapping casts resolve against.
 */
export function loadParser(pdbPaths = [join(SHARED, "core.pdb")]) {
    if (!parserRuntime) {
        const registry = new ModuleRegistry(readFileSync(join(SHARED, "specification/m3.fbs"), "utf8"));
        for (const p of pdbPaths) registry.register(PdbModule.open(p));
        registry.validate();
        parserRuntime = PureRuntime.builder().withRegistry(registry).withNatives(NativeRegistry.createDefault([antlrExtension])).build();
        loadBundle();
        loadPdbReader();
    }
    return {
        parse: (sourceId, content) => parserRuntime.parse(sourceId, content),
    };
}
