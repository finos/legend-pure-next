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

// Generic ZIP reader (STORED + deflate entries). The central directory is
// indexed up front but each entry is decompressed only on `read(name)` — lazy,
// so opening an archive is cheap and only touched entries are inflated.
//
// This is the host wiring under the PDB store (the parent directory): a .pdb
// is a ZIP of FlatBuffer element blobs under `elements/` plus index sections.
// Nothing here is PDB-specific, though — it's plain zip decoding.
//
// Environment-neutral: takes the archive bytes as a Uint8Array and uses the
// zero-dep synchronous inflater (inflate.js), so the same code runs in Node and
// the browser. The Node hosts read the file with fs and hand the bytes here; the
// browser fetches them.

import { inflateRaw } from "./inflate.js";

const EOCD_SIG = 0x06054b50;
const CD_SIG = 0x02014b50;
const LOCAL_SIG = 0x04034b50;

const DECODER = new TextDecoder();

/** Open a zip archive from its raw bytes. Returns { names, has, read }. */
export function openZip(bytes) {
    const buf = bytes instanceof Uint8Array ? bytes : new Uint8Array(bytes);
    const dv = new DataView(buf.buffer, buf.byteOffset, buf.byteLength);
    const u32 = (p) => dv.getUint32(p, true);
    const u16 = (p) => dv.getUint16(p, true);

    // Locate End Of Central Directory (scan back over the optional comment).
    let eocd = -1;
    const minStart = Math.max(0, buf.length - 22 - 0xffff);
    for (let i = buf.length - 22; i >= minStart; i--) {
        if (u32(i) === EOCD_SIG) { eocd = i; break; }
    }
    if (eocd < 0) throw new Error("not a zip / EOCD not found");

    const count = u16(eocd + 10);
    let cd = u32(eocd + 16);

    const entries = new Map();
    for (let i = 0; i < count; i++) {
        if (u32(cd) !== CD_SIG) throw new Error("bad central-directory header");
        const method = u16(cd + 10);
        const compSize = u32(cd + 20);
        const nameLen = u16(cd + 28);
        const extraLen = u16(cd + 30);
        const commentLen = u16(cd + 32);
        const lho = u32(cd + 42);
        const name = DECODER.decode(buf.subarray(cd + 46, cd + 46 + nameLen));
        entries.set(name, { method, compSize, lho });
        cd += 46 + nameLen + extraLen + commentLen;
    }

    function read(name) {
        const e = entries.get(name);
        if (!e) throw new Error(`no such entry: ${name}`);
        if (u32(e.lho) !== LOCAL_SIG) throw new Error(`bad local header: ${name}`);
        const nameLen = u16(e.lho + 26);
        const extraLen = u16(e.lho + 28);
        const start = e.lho + 30 + nameLen + extraLen;
        const comp = buf.subarray(start, start + e.compSize);
        if (e.method === 0) return new Uint8Array(comp);
        if (e.method === 8) return inflateRaw(comp);
        throw new Error(`unsupported compression method ${e.method}: ${name}`);
    }

    return {
        names: () => [...entries.keys()],
        has: (name) => entries.has(name),
        read,
    };
}
