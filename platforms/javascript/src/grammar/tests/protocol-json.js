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

// Serialize a parsed Pure AST to protocol JSON — the SAME strategy the Java
// side uses (Jackson in TopLevelProtocolJsonSerializer):
//   - `_type` = the class simple name (last `::` segment of the classifier path)
//   - JsonInclude.NON_EMPTY — omit null / "" / empty collections
//   - bigint / big.js Decimal → plain number
//   - `Any`-typed slots (e.g. AtomicValue.value) carry their directly-held
//     object WITHOUT a `_type` discriminator; nested fields keep theirs.
//
// (Key ordering is irrelevant here — the grammar tests compare structurally.)

const DROP_KEYS = new Set(["classifierGenericType", "__equalityKeys", "__purePath", "path"]);
const ANY_TYPED = new Set(["AtomicValue.value"]);

function isBig(v) {
    return v && typeof v === "object" && Array.isArray(v.c) &&
        typeof v.e === "number" && typeof v.s === "number" && typeof v.toFixed === "function";
}

// Jackson NON_EMPTY: null/undefined, empty string, empty array/map are omitted.
function isEmpty(v) {
    return v === null || v === undefined || v === "" ||
        (Array.isArray(v) && v.length === 0) ||
        (typeof v === "object" && !Array.isArray(v) && Object.keys(v).length === 0);
}

function convert(value, omitOwnType) {
    if (typeof value === "bigint") return Number(value);
    if (isBig(value)) return Number(value.toString());
    if (Array.isArray(value)) return value.map((v) => convert(v, omitOwnType));
    if (value && typeof value === "object") {
        const cgt = value.classifierGenericType;
        const typeName =
            cgt && cgt.type && typeof cgt.type.__purePath === "string"
                ? cgt.type.__purePath.split("::").pop()
                : undefined;
        const out = {};
        if (typeName && !omitOwnType) out._type = typeName;
        for (const [k, v] of Object.entries(value)) {
            if (DROP_KEYS.has(k)) continue;
            const converted = convert(v, ANY_TYPED.has(`${typeName}.${k}`));
            if (isEmpty(converted)) continue; // NON_EMPTY
            out[k] = converted;
        }
        return out;
    }
    return value;
}

/** Convert a parsed AST node to its protocol.json representation. */
export function toProtocolJson(value) {
    return convert(value, false);
}

/** Strict structural equality; returns the first differing path, or null. */
export function protocolEqual(got, exp, path = "$") {
    const ta = got === null ? "null" : Array.isArray(got) ? "array" : typeof got;
    const tb = exp === null ? "null" : Array.isArray(exp) ? "array" : typeof exp;
    if (ta !== tb) return `${path}: ${ta}=${JSON.stringify(got)} vs ${tb}=${JSON.stringify(exp)}`;
    if (ta === "array") {
        if (got.length !== exp.length) return `${path}: array length ${got.length} vs ${exp.length}`;
        for (let i = 0; i < got.length; i++) {
            const d = protocolEqual(got[i], exp[i], `${path}[${i}]`);
            if (d) return d;
        }
        return null;
    }
    if (ta === "object") {
        for (const k of Object.keys(got)) if (!(k in exp)) return `${path}.${k}: extra (=${JSON.stringify(got[k])})`;
        for (const k of Object.keys(exp)) {
            if (!(k in got)) return `${path}.${k}: missing (exp ${JSON.stringify(exp[k])})`;
            const d = protocolEqual(got[k], exp[k], `${path}.${k}`);
            if (d) return d;
        }
        return null;
    }
    return got === exp ? null : `${path}: ${JSON.stringify(got)} vs ${JSON.stringify(exp)}`;
}
