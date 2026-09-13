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

// A .pdb archive as a module: an entry index plus LAZY node decoding. Decoding
// runs through the TRANSLATED SELF-HOSTED PURE READER (pdb/reader/reader.pure via
// pure-reader.js) — only touched elements are inflated and decoded; archive
// opening and inflation stay host wiring (zip/zip.js).
//
// This is the only module kind that needs any of that machinery. Its sibling
// ../memory/module.js holds live objects and decodes nothing.

import { toPureBytes, elementNode } from "./pure-reader.js";

/** One .pdb archive as a module: an entry index plus lazy node decoding. */
export function createPdbModule(name, archive, dependencies = []) {
    // path -> { name, typeName }. Within one archive paths are unique.
    const index = new Map();
    for (const entryName of archive.names()) {
        if (!entryName.startsWith("elements/")) continue;
        const s = entryName.slice("elements/".length);
        const dot = s.lastIndexOf(".");
        index.set(s.slice(0, dot).split("/").join("::"), { name: entryName, typeName: s.slice(dot + 1) });
    }
    return {
        name, dependencies, kind: "pdb", archive,
        hasElement: (path) => index.has(path),
        /** Decode the element node for `path` (undefined when not stored here). */
        node(path) {
            const e = index.get(path);
            return e ? elementNode(toPureBytes(archive.read(e.name)), e.typeName) : undefined;
        },
        typeNameOf: (path) => index.get(path)?.typeName,
        paths: () => index.keys(),
        elementCount: index.size,
    };
}
