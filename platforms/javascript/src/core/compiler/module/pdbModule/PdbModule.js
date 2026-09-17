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

// A .pdb archive as a module: identity from the archive's manifest, an entry
// index, and LAZY node decoding. Decoding runs through the TRANSLATED
// SELF-HOSTED PURE READER (pdb/reader/reader.pure via pure-reader.js) — only
// touched elements are inflated and decoded; archive opening and inflation stay
// host wiring (zip/zip.js).
//
// This is the only module kind that needs any of that machinery. Its sibling
// ../inMemoryModule/InMemoryModule.js holds live objects and decodes nothing.

import { openZip } from "./zip/zip.js";
import { toPureBytes, elementNode } from "./pure-reader.js";

// The archive section holding the module manifest (name, packagePattern,
// dependencies) — ModuleManifest.ARCHIVE_SECTION on the JVM hosts.
const MANIFEST_SECTION = "manifest";
const UTF8 = new TextDecoder();

/** One .pdb archive as a module. */
export class PdbModule {
    /**
     * Open a .pdb file. Node only — a browser has bytes, not paths: use
     * `new PdbModule(openZip(bytes))`. Synchronous like the JVM hosts, via
     * process.getBuiltinModule, so this file stays importable in a browser.
     */
    static open(path) {
        const fs = globalThis.process?.getBuiltinModule?.("node:fs");
        if (!fs) throw new Error("PdbModule.open(path) needs Node; in a browser use new PdbModule(openZip(bytes))");
        return new PdbModule(openZip(fs.readFileSync(path)), String(path));
    }

    #archive;
    #manifest;
    /** path -> { entry, typeName }; paths are unique within one archive. */
    #index = new Map();

    /**
     * @param archive an opened zip: { names(), has(name), read(name) }
     * @param source  names the archive in error messages
     */
    constructor(archive, source = "archive") {
        if (!archive.has(MANIFEST_SECTION)) {
            throw new Error(`PDB archive at ${source} has no module manifest section. `
                + "It must be rebuilt with a writer that embeds one.");
        }
        this.#archive = archive;
        this.#manifest = JSON.parse(UTF8.decode(archive.read(MANIFEST_SECTION)));
        for (const entry of archive.names()) {
            if (!entry.startsWith("elements/")) continue;
            const s = entry.slice("elements/".length);
            const dot = s.lastIndexOf(".");
            this.#index.set(s.slice(0, dot).split("/").join("::"), { entry, typeName: s.slice(dot + 1) });
        }
    }

    get name() { return this.#manifest.name; }
    get dependencies() { return this.#manifest.dependencies ?? []; }
    get packagePattern() { return this.#manifest.packagePattern; }
    get kind() { return "pdb"; }
    /** The opened zip — the registry reads the functionIndex section from it. */
    get archive() { return this.#archive; }
    get elementCount() { return this.#index.size; }

    hasElement(path) { return this.#index.has(path); }
    /** The element's proxy (its reads go through the metadata globals), or null when not stored here. */
    getElement(path) { return this.#index.has(path) ? globalThis.__pureResolve(path) : null; }
    elementPaths() { return this.#index.keys(); }

    /** Decode the element node for `path` (undefined when not stored here). */
    node(path) {
        const e = this.#index.get(path);
        return e ? elementNode(toPureBytes(this.#archive.read(e.entry)), e.typeName) : undefined;
    }
    typeNameOf(path) { return this.#index.get(path)?.typeName; }
}
