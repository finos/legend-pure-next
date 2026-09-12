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

// Host wiring over the TRANSLATED SELF-HOSTED PURE READER (pdb/reader.pure +
// pdb/fbsParser.pure, compiled into generated/compiler.js — the same Pure code
// that runs on the JVM and Truffle). reader.pure is deliberately format-only:
// element identity, caching and reference resolution are the host's job, and
// this module is that thin layer for JS — translated-function lookup, byte-list
// conversion (Pure Integer[*] ⇄ Uint8Array), a JS-side index over the parsed
// schema so field lookups don't re-filter Pure lists per read, and shape
// predicates for the reader's output forms (FbsNode / ReadPointerRef /
// ReadAncestorRef, which surface as plain objects with .pos / .segs / .depth).
//
// The translated functions are resolved from globalThis lazily AT CALL TIME:
// the store is built (and the bridge installed) before generated/compiler.js
// is imported, and nothing here may touch the translated code until the first
// real metadata read — which only happens once the compiler is loaded.

const FNS = {
    parseFbs: "meta$pure$compiler$pdb$schema$parseFbs_String_1__FbsSchema_1_",
    elementNode: "meta$pure$compiler$pdb$reader$elementNode_Integer_MANY__String_1__FbsNode_1_",
    rootNode: "meta$pure$compiler$pdb$reader$rootNode_Integer_MANY__String_1__FbsNode_1_",
    readField: "meta$pure$compiler$pdb$reader$readField_FbsSchema_1__FbsNode_1__String_1__Any_MANY_",
    fieldPos: "meta$pure$compiler$pdb$reader$fieldPos_FbsNode_1__Integer_1__Integer_1_",
    unwrapValueDef: "meta$pure$compiler$pdb$reader$unwrapValueDef_FbsNode_1__Any_1_",
};

function fn(name) {
    const f = globalThis[FNS[name]];
    if (typeof f !== "function") {
        throw new Error(`translated Pure pdb reader not loaded (missing ${FNS[name]}; run \`just javascript::generate-all\` and load the generated compiler first)`);
    }
    return f;
}

// A translated [*] result may surface as an array, a single value, or [].
export const asList = (v) => (v === undefined || v === null ? [] : Array.isArray(v) ? v : [v]);

// Pure Integer[*] is a list of BigInts; convert entry bytes once per entry.
export const toPureBytes = (bytes) => Array.from(bytes, BigInt);

// m3.fbs PointerKind, in declaration order (ReadPointerRef.kind is the index).
export const POINTER_KINDS = ["Element", "Property", "QualifiedProperty", "Stereotype", "Tag"];

// --- output-shape predicates (translated instances are plain objects) ----------
export const isFbsNode = (v) => !!v && typeof v === "object" && typeof v.type === "string" && v.pos !== undefined;
export const isPointerRef = (v) => !!v && typeof v === "object" && v.segs !== undefined;
export const isAncestorRef = (v) => !!v && typeof v === "object" && v.depth !== undefined && v.segs === undefined;

/**
 * Parse the .fbs schema text with the translated parser and index it for the
 * JS side: tables by name, each with its fields (name/id/type/vector) plus a
 * precomputed category ('scalar' | 'table' | 'union' | 'enum') and a by-name
 * map. `pschema` is the translated FbsSchema handed back to readField.
 */
export function parseSchema(text) {
    const pschema = fn("parseFbs")(text);
    const unions = new Set(asList(pschema.unions).map((u) => u.name));
    const enums = new Set(asList(pschema.enums).map((e) => e.name));
    const tables = new Map();
    for (const t of asList(pschema.tables)) {
        const fields = asList(t.fields).map((f) => ({
            name: f.name, id: f.id, type: f.type, vector: !!f.vector,
        }));
        tables.set(t.name, { name: t.name, fields, byName: new Map(fields.map((f) => [f.name, f])) });
    }
    for (const t of tables.values()) {
        for (const f of t.fields) {
            f.cat = unions.has(f.type) ? "union" : tables.has(f.type) ? "table" : enums.has(f.type) ? "enum" : "scalar";
        }
    }
    return { pschema, tables };
}

/** Root Def node of one `elements/<path>.<TypeName>` entry's (Pure) bytes. */
export const elementNode = (pureBytes, typeName) => fn("elementNode")(pureBytes, typeName);

/** Root table of a non-element section (e.g. functionIndex -> FunctionIndex). */
export const rootNode = (pureBytes, rootType) => fn("rootNode")(pureBytes, rootType);

/**
 * Read one field by fbs name, normalized to an array. Elided scalars surface
 * their FlatBuffer default (one element); elided strings/tables/unions/vectors
 * come back empty. PointerRef/AncestorRef surface as descriptions.
 */
export const readField = (sx, node, fieldName) => asList(fn("readField")(sx.pschema, node, fieldName));

/** Present-in-vtable probe (decodes nothing) — `f` is an indexed field def. */
export const hasField = (node, f) => Number(fn("fieldPos")(node, f.id)) !== -1;

/** Unwrap a scalar `*ValueDef` to its bare payload (Decimal stays a string). */
export const unwrapValueDef = (node) => {
    const v = fn("unwrapValueDef")(node);
    return Array.isArray(v) ? v[0] : v;
};
