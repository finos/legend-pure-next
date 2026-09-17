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

// Structural diff for two PDB archives — the parity check between the JS writer
// and a Java-written golden. FlatBuffer layout details that don't change meaning
// (STORED vs deflate, vtable dedup, shared-string dedup, entry order) are ignored:
// we decode each element blob with the TRANSLATED SELF-HOSTED PURE READER
// (pure-reader.js) and compare the resulting node trees field-by-field,
// following the schema. The first divergence is reported with the field path so
// the writer can be corrected to match Java.

import {
    toPureBytes, elementNode, readField, hasField,
    isFbsNode, isPointerRef, isAncestorRef, asList,
} from "../pdbModule/pure-reader.js";

// "elements/a/b/C.Class" -> { path: "a::b::C", typeName: "Class" }
function parseEntry(name) {
    const s = name.slice("elements/".length);
    const dot = s.lastIndexOf(".");
    return { path: s.slice(0, dot).split("/").join("::"), typeName: s.slice(dot + 1) };
}

// Compare two decoded field values. Returns a divergence { path, a, b } or null.
function cmpValue(sx, a, b, path) {
    const ea = a === undefined || a === null;
    const eb = b === undefined || b === null;
    if (ea || eb) return ea && eb ? null : { path, a: desc(a), b: desc(b) };

    if (isPointerRef(a) || isPointerRef(b)) {
        if (!isPointerRef(a) || !isPointerRef(b)) return { path, a: desc(a), b: desc(b) };
        if (Number(a.kind) !== Number(b.kind)) return { path: path + ".<kind>", a: Number(a.kind), b: Number(b.kind) };
        const ap = asList(a.segs).join("::"), bp = asList(b.segs).join("::");
        return ap === bp ? null : { path: path + ".<path>", a: ap, b: bp };
    }
    if (isAncestorRef(a) || isAncestorRef(b)) {
        if (!isAncestorRef(a) || !isAncestorRef(b)) return { path, a: desc(a), b: desc(b) };
        return Number(a.depth) === Number(b.depth) ? null : { path: path + ".<depth>", a: Number(a.depth), b: Number(b.depth) };
    }
    if (isFbsNode(a) || isFbsNode(b)) {
        if (!isFbsNode(a) || !isFbsNode(b)) return { path, a: desc(a), b: desc(b) };
        return cmpNode(sx, a, b, path);
    }
    // scalars / strings / enum names
    if (a !== b) return { path, a: desc(a), b: desc(b) };
    return null;
}

// Fields whose vector is a set (order carries no meaning), compared as a
// multiset. `Package.children` is a containment set — JS emits it in insertion
// order, Java in resolution order — and the compiled-graph printer sorts by
// source position regardless, so the order is unobservable in the graph.
// `resolved_type_parameters` / `resolved_multiplicity_parameters` are keyed by
// `name` and the compiled-graph printer sorts them by name — so their stored
// order is non-semantic (JS and Java build them in different orders).
const SET_FIELDS = new Set(["children", "resolved_type_parameters", "resolved_multiplicity_parameters"]);

// A canonical key for set-membership: a `name` for named entries (resolved
// params), else the PointerRef path (children), else the node type.
function sortKey(sx, v) {
    if (isPointerRef(v)) return "P:" + asList(v.segs).join("::");
    if (isFbsNode(v)) {
        const t = sx.tables.get(v.type);
        if (t && t.byName.has("name")) {
            const n = readField(sx, v, "name")[0];
            if (typeof n === "string") return "M:" + n;
        }
        return "N:" + v.type;
    }
    return "S:" + String(v);
}

function cmpVectorAsSet(sx, a, b, path) {
    if (a.length !== b.length) return { path: path + ".length", a: a.length, b: b.length };
    const byKey = (v) => ({ v, k: sortKey(sx, v) });
    const cmp = (x, y) => (x.k < y.k ? -1 : x.k > y.k ? 1 : 0);
    const ai = a.map(byKey).sort(cmp);
    const bi = b.map(byKey).sort(cmp);
    for (let i = 0; i < ai.length; i++) {
        const d = cmpValue(sx, ai[i].v, bi[i].v, `${path}{set}[${i}]`);
        if (d) return d;
    }
    return null;
}

// Compare two table nodes: same type, same value for every schema field.
// Vector presence is probed in the vtable so an absent vector still differs
// from a present-but-empty one; scalars compare present-or-default (matching
// the writers, which elide default-valued scalars).
function cmpNode(sx, a, b, path) {
    if (a.type !== b.type) return { path: path + ".<type>", a: a.type, b: b.type };
    const tdef = sx.tables.get(a.type);
    for (const f of (tdef ? tdef.fields : [])) {
        const fpath = `${path}.${f.name}`;
        if (f.vector) {
            const pa = hasField(a, f), pb = hasField(b, f);
            if (pa !== pb) { return { path: fpath, a: pa ? "<present>" : "<absent>", b: pb ? "<present>" : "<absent>" }; }
            if (!pa) continue;
            const av = readField(sx, a, f.name), bv = readField(sx, b, f.name);
            if (av.length !== bv.length) return { path: fpath + ".length", a: av.length, b: bv.length };
            if (SET_FIELDS.has(f.name)) {
                const d = cmpVectorAsSet(sx, av, bv, fpath);
                if (d) return d;
                continue;
            }
            for (let i = 0; i < av.length; i++) {
                const d = cmpValue(sx, av[i], bv[i], `${fpath}[${i}]`);
                if (d) return d;
            }
            continue;
        }
        const d = cmpValue(sx, readField(sx, a, f.name)[0], readField(sx, b, f.name)[0], fpath);
        if (d) return d;
    }
    return null;
}

function desc(v) {
    if (v === undefined) return "<absent>";
    if (v === null) return "<null>";
    if (isPointerRef(v)) return `<PointerRef ${asList(v.segs).join("::")}>`;
    if (isAncestorRef(v)) return `<AncestorRef ${Number(v.depth)}>`;
    if (isFbsNode(v)) return `<${v.type}>`;
    if (typeof v === "bigint") return `${v}n`;
    if (typeof v === "string") return JSON.stringify(v);
    return String(v);
}

/**
 * Diff two opened PDB archives (see zip/zip.js openZip). `golden` is
 * authoritative. Returns a list of findings; empty means structurally identical
 * over the element set. Each finding: { path, entry?, field?, a, b, kind }.
 */
export function diffArchives(sx, jsAr, goldenAr) {
    const findings = [];
    const entryOf = (ar) => new Map(ar.names().filter((n) => n.startsWith("elements/")).map((n) => [parseEntry(n).path, n]));
    const jsEntries = entryOf(jsAr);
    const goldEntries = entryOf(goldenAr);

    for (const [path, gname] of goldEntries) {
        const jname = jsEntries.get(path);
        if (!jname) {
            // The Java golden also carries test-fixture elements JS intentionally
            // omits: the per-test package named after the sourceId (e.g.
            // "primitive/simple") that holds the ###CompiledGraph assertion. Skip
            // any golden-only Package; still flag missing real elements.
            if (!gname.endsWith(".Package")) findings.push({ path, kind: "missing-in-js" });
            continue;
        }
        const { typeName: gType } = parseEntry(gname);
        const { typeName: jType } = parseEntry(jname);
        if (gType !== jType) { findings.push({ path, kind: "type-mismatch", a: jType, b: gType }); continue; }
        const jn = elementNode(toPureBytes(jsAr.read(jname)), jType);
        const gn = elementNode(toPureBytes(goldenAr.read(gname)), gType);
        const d = cmpNode(sx, jn, gn, path);
        if (d) findings.push({ path, kind: "field-diff", field: d.path, a: d.a, b: d.b });
    }
    for (const path of jsEntries.keys()) {
        if (!goldEntries.has(path)) findings.push({ path, kind: "extra-in-js" });
    }
    return findings;
}
