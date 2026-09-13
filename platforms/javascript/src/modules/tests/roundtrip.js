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

// Round-trip harness for the PDB writer, exercised by the compiler tests: take a
// freshly compiled in-memory graph, serialize it to .pdb bytes with the
// TRANSLATED SELF-HOSTED PURE WRITER (meta::pure::compiler::pdb::archive +
// gen::writeX, compiled into generated/compiler.js — the same Pure code that
// runs on the JVM), reopen those bytes with the TRANSLATED SELF-HOSTED PURE
// READER (pdb/reader/reader.pure via store.js/pure-reader.js), and re-print the graph
// straight out of the reader. Diffing that against the direct print is a
// faithful writer⇄reader check — self-hosted Pure code on both sides.
//
// The re-print runs with the fresh archive REGISTERED AS A MODULE at the
// front of the base registry (module layering, mirroring the truffle module
// architecture), so the same __pureResolve proxy path the live compiler uses
// reads the round-tripped elements — including the test's own packages and
// cross-references. Base paths (String, Class, …) read identically since the
// layered view is a superset. Unregistering afterwards restores the base view
// (registration flushes the registry and marshal caches on both edges).

import { openZip } from "../pdb/zip/zip.js";
import { createPdbModule } from "../registry.js";

// "elements/a/b/C.Class" -> "a::b::C" (inverse of the writer's entry naming).
const pathFromEntry = (name) => {
    const s = name.slice("elements/".length);
    return s.slice(0, s.lastIndexOf(".")).split("/").join("::");
};

// The translated Pure archive writer: elements -> whole .pdb bytes (a STORED
// zip of per-element FlatBuffer entries + elementIndex + manifest +
// functionIndex + reverseReferenceIndex). Pure Integer[*] surfaces as an array
// of BigInts (0n..255n). Manifest identity matches what the Java golden
// generator stamps on spec-test PDBs. `referencedBy` is the compile result's
// CompilerContext.referencedBy (a translated Pure Map); when absent, the
// 4-arg overload writes without a reverse index.
function pureWriteArchive(elements, referencedBy) {
    if (referencedBy != null) {
        const fn5 = globalThis["meta$pure$compiler$pdb$archive$writeArchive_Any_MANY__String_1__String_1__String_MANY__Map_1__Integer_MANY_"];
        if (typeof fn5 === "function") {
            return Uint8Array.from(fn5(elements, "test", "*", ["core"], referencedBy), Number);
        }
    }
    const fn = globalThis["meta$pure$compiler$pdb$archive$writeArchive_Any_MANY__String_1__String_1__String_MANY__Integer_MANY_"];
    if (typeof fn !== "function") {
        throw new Error("translated Pure pdb writer not loaded (run `just javascript::generate-all` and loadCompiler() first)");
    }
    return Uint8Array.from(fn(elements, "test", "*", ["core"]), Number);
}

/**
 * Serialize `elements` with the translated Pure writer and reopen them with
 * the reader as a registerable module.
 *
 * @param referencedBy the compile's CompilerContext.referencedBy Pure Map
 *   (optional) — carried into the archive's reverseReferenceIndex section.
 * @returns {{ paths: string[], module: object, bytes: Uint8Array }}
 *   paths  — the element paths the writer emitted (order preserved)
 *   module — a PdbModule over the fresh archive, ready to layer via withModule
 */
export function roundTripElements(elements, referencedBy) {
    const bytes = pureWriteArchive(elements, referencedBy);
    const fresh = openZip(bytes);
    const module = createPdbModule("roundtrip", fresh);
    const paths = fresh.names().filter((n) => n.startsWith("elements/")).map(pathFromEntry);
    return { paths, module, bytes };
}

/**
 * Run `fn` with `module` registered at the front of `registry` (its elements
 * win over the base modules), unregistering afterwards (even on throw).
 */
export function withModule(registry, module, fn) {
    registry.register(module, { front: true });
    try {
        return fn();
    } finally {
        registry.unregister(module);
    }
}
