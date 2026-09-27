// Copyright (c) 2020-present, Goldman Sachs
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

// Reading the ZIP container a .pdb is — see archiveRead.pure. The pdb FORMAT
// stays in Pure; the container is a host service.
//
// NOT a Node-only extension: it takes the archive bytes it is given and uses the
// zero-dep synchronous zip reader (pdbModule/zip/zip.js), which runs the same in
// Node and the browser. Whoever OBTAINS the bytes decides the environment — the
// Node hosts read the file with fs, a browser page fetches it.

import { openZip } from "../../compiler/module/pdbModule/zip/zip.js";
import { ENTRY_BYTES, ENTRY_NAMES } from "./native-signatures.js";

/** Host natives for meta::pure::compiler::pdb::archive. */
export const archiveExtension = {
    registerAll(natives) {
        natives.register(ENTRY_NAMES, () => entryNames);
        natives.register(ENTRY_BYTES, () => entryBytes);
    },
};

// entryNames(archive): every entry, in archive order.
function entryNames(archive) {
    return openZip(toArchiveBytes(archive)).names();
}

// entryBytes(archive, name): one entry, inflated, as a Binary.
function entryBytes(archive, name) {
    return openZip(toArchiveBytes(archive)).read(String(name));
}

// A Binary IS the Uint8Array openZip takes — a handle, not a conversion. This
// used to unbox a multi-megabyte BigInt list on every call.
function toArchiveBytes(archive) {
    if (archive instanceof Uint8Array) return archive;
    throw new Error("Expected a Binary (Uint8Array) archive, got " + typeof archive);
}
