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

// Synchronous raw-DEFLATE (RFC 1951) decompressor — zero deps, identical in Node
// and the browser. The PDB reader needs a SYNCHRONOUS inflate: the translated
// runtime calls __metadataRead synchronously, so the browser's async
// DecompressionStream can't back it. Inflation is lazy (only entries actually
// read get inflated), so compiling a small file touches only a handful of blobs.
//
// Based on the classic "tinf" algorithm (Joergen Ibsen, zlib-license), adapted
// to typed arrays. Correctness is covered by the Node compiler suite, which
// inflates and decodes real PDB elements end to end on every run.

const LENGTH_BASE = [3, 4, 5, 6, 7, 8, 9, 10, 11, 13, 15, 17, 19, 23, 27, 31, 35, 43, 51, 59, 67, 83, 99, 115, 131, 163, 195, 227, 258];
const LENGTH_BITS = [0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 1, 1, 2, 2, 2, 2, 3, 3, 3, 3, 4, 4, 4, 4, 5, 5, 5, 5, 0];
const DIST_BASE = [1, 2, 3, 4, 5, 7, 9, 13, 17, 25, 33, 49, 65, 97, 129, 193, 257, 385, 513, 769, 1025, 1537, 2049, 3073, 4097, 6145, 8193, 12289, 16385, 24577];
const DIST_BITS = [0, 0, 0, 0, 1, 1, 2, 2, 3, 3, 4, 4, 5, 5, 6, 6, 7, 7, 8, 8, 9, 9, 10, 10, 11, 11, 12, 12, 13, 13];
// Order in which the 19 code-length-code lengths appear in a dynamic block.
const CLC_ORDER = [16, 17, 18, 0, 8, 7, 9, 6, 10, 5, 11, 4, 12, 3, 13, 2, 14, 1, 15];

function newTree(maxSymbols) {
    return { counts: new Uint16Array(16), symbols: new Uint16Array(maxSymbols) };
}

// Build a canonical Huffman tree (counts-per-length + symbols-by-length) from a
// slice of code lengths.
function buildTree(tree, lengths, off, num) {
    tree.counts.fill(0);
    for (let i = 0; i < num; i++) tree.counts[lengths[off + i]]++;
    tree.counts[0] = 0;
    const offsets = new Uint16Array(16);
    let sum = 0;
    for (let i = 0; i < 16; i++) { offsets[i] = sum; sum += tree.counts[i]; }
    for (let i = 0; i < num; i++) {
        if (lengths[off + i]) tree.symbols[offsets[lengths[off + i]]++] = i;
    }
}

const fixedLt = newTree(288);
const fixedDt = newTree(30);
(function buildFixedTrees() {
    const l = new Uint8Array(288);
    for (let i = 0; i < 144; i++) l[i] = 8;
    for (let i = 144; i < 256; i++) l[i] = 9;
    for (let i = 256; i < 280; i++) l[i] = 7;
    for (let i = 280; i < 288; i++) l[i] = 8;
    buildTree(fixedLt, l, 0, 288);
    const d = new Uint8Array(30).fill(5);
    buildTree(fixedDt, d, 0, 30);
})();

class BitReader {
    constructor(src) { this.src = src; this.pos = 0; this.tag = 0; this.bitcount = 0; }
    getbit() {
        if (this.bitcount === 0) { this.tag = this.src[this.pos++]; this.bitcount = 8; }
        const bit = this.tag & 1;
        this.tag >>>= 1;
        this.bitcount--;
        return bit;
    }
    getbits(num, base) {
        let val = 0;
        for (let i = 0; i < num; i++) val |= this.getbit() << i;
        return val + (base | 0);
    }
    // Decode one symbol via the canonical Huffman tree.
    decode(tree) {
        let sum = 0, cur = 0, len = 0;
        do {
            cur = 2 * cur + this.getbit();
            len++;
            sum += tree.counts[len];
            cur -= tree.counts[len];
        } while (cur >= 0);
        return tree.symbols[sum + cur];
    }
}

// Growable output buffer (back-references read previously-written bytes).
class Out {
    constructor() { this.buf = new Uint8Array(1 << 16); this.len = 0; }
    ensure(extra) {
        if (this.len + extra <= this.buf.length) return;
        let cap = this.buf.length;
        while (cap < this.len + extra) cap *= 2;
        const next = new Uint8Array(cap);
        next.set(this.buf.subarray(0, this.len));
        this.buf = next;
    }
    push(byte) { this.ensure(1); this.buf[this.len++] = byte; }
}

function inflateBlock(r, out, lt, dt) {
    for (;;) {
        const sym = r.decode(lt);
        if (sym === 256) return;
        if (sym < 256) { out.push(sym); continue; }
        const s = sym - 257;
        const length = r.getbits(LENGTH_BITS[s], LENGTH_BASE[s]);
        const ds = r.decode(dt);
        const dist = r.getbits(DIST_BITS[ds], DIST_BASE[ds]);
        out.ensure(length);
        let from = out.len - dist;
        for (let i = 0; i < length; i++) out.buf[out.len++] = out.buf[from++];
    }
}

function inflateStored(r, out) {
    // Align to byte boundary, then copy LEN literal bytes.
    r.bitcount = 0;
    const src = r.src;
    const len = src[r.pos] | (src[r.pos + 1] << 8);
    r.pos += 4; // skip LEN + NLEN
    out.ensure(len);
    for (let i = 0; i < len; i++) out.buf[out.len++] = src[r.pos++];
}

const dynLt = newTree(288);
const dynDt = newTree(30);
const clcTree = newTree(19);
const codeLengths = new Uint8Array(288 + 32);

function inflateDynamic(r, out) {
    const hlit = r.getbits(5, 257);
    const hdist = r.getbits(5, 1);
    const hclen = r.getbits(4, 4);
    codeLengths.fill(0, 0, 19);
    for (let i = 0; i < hclen; i++) codeLengths[CLC_ORDER[i]] = r.getbits(3, 0);
    buildTree(clcTree, codeLengths, 0, 19);

    let num = 0;
    const total = hlit + hdist;
    while (num < total) {
        const sym = r.decode(clcTree);
        if (sym === 16) { const prev = codeLengths[num - 1]; for (let n = r.getbits(2, 3); n > 0; n--) codeLengths[num++] = prev; }
        else if (sym === 17) { for (let n = r.getbits(3, 3); n > 0; n--) codeLengths[num++] = 0; }
        else if (sym === 18) { for (let n = r.getbits(7, 11); n > 0; n--) codeLengths[num++] = 0; }
        else codeLengths[num++] = sym;
    }
    buildTree(dynLt, codeLengths, 0, hlit);
    buildTree(dynDt, codeLengths, hlit, hdist);
    inflateBlock(r, out, dynLt, dynDt);
}

/** Inflate raw DEFLATE (RFC 1951) bytes. Returns a Uint8Array. */
export function inflateRaw(source) {
    const r = new BitReader(source);
    const out = new Out();
    let bfinal;
    do {
        bfinal = r.getbit();
        const btype = r.getbits(2, 0);
        if (btype === 0) inflateStored(r, out);
        else if (btype === 1) inflateBlock(r, out, fixedLt, fixedDt);
        else if (btype === 2) inflateDynamic(r, out);
        else throw new Error(`invalid DEFLATE block type ${btype}`);
    } while (!bfinal);
    return out.buf.subarray(0, out.len);
}
