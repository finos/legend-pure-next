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

// MARSHALLING: turn values decoded by the TRANSLATED SELF-HOSTED PURE READER
// (pdb/reader/reader.pure via pure-reader.js) into the shapes the translated
// runtime expects, and install the globals it calls (`installMetadataGlobals`).
//
// This is the wiring half reader.pure deliberately leaves to the host: element
// identity (the __pureResolve proxies), per-(address,prop) caching, PointerRef
// resolution, AncestorRef cycle closure, *ValueDef unboxing and enum
// rehydration. Every one of those exists because the value came out of
// FlatBuffer BYTES — which is why it lives under pdb/ and why an in-memory
// element needs none of it (readProp returns `node.obj[prop]` and stops).
//
// The graph queries it installs (subtypeOf / instanceOf / pathToElement) are
// thin delegates to the registry; only `__metadataRead` does real work here.

import {
    readField, hasField, unwrapValueDef,
    isFbsNode, isPointerRef, isAncestorRef, asList, POINTER_KINDS,
} from "./pure-reader.js";

const camelToSnake = (s) => s.replace(/([A-Z])/g, "_$1").toLowerCase();
const snakeToCamel = (s) => s.replace(/_([a-z])/g, (_, c) => c.toUpperCase());

// Pure property name -> the table's fbs field def (readField wants fbs names).
function fieldDef(sx, type, prop) {
    const t = sx.tables.get(type);
    if (!t) return null;
    for (const c of [prop, camelToSnake(prop), camelToSnake(prop) + "_", prop + "_"]) {
        const f = t.byName.get(c);
        if (f) return f;
    }
    return null;
}
// schema field -> Pure property: strip trailing `_`, snake -> camel.
const pureName = (field) => snakeToCamel(field.endsWith("_") ? field.slice(0, -1) : field);

// Scalar `*ValueDef` wrappers -> the bare primitive (mirrors the write-side that
// packs a raw AtomicValue payload into the matching def). The translated
// unwrapValueDef handles elision defaults; Decimals are stored as a string and
// rehydrated through the runtime's Big constructor.
const VALUE_DEFS = new Set(["IntegerValueDef", "FloatValueDef", "BooleanValueDef", "StringValueDef", "DecimalValueDef"]);

// Enum-typed properties are stored as bare name strings (the writer emits
// value._name()); rehydrate them to the runtime's enum-value singletons so
// `.name` access and `==` against the constants work. Same two fields the
// reference reader resolves (generated wrappers call resolveEnumValue for
// exactly GenericTypeOperationType and AggregationKind). The singletons are
// the generated core-metamodel exports, merged onto globalThis by loadCompiler.
const ENUM_STRING_FIELDS = {
    operation_type: "GenericTypeOperationType",
    aggregation: "AggregationKind",
};
function rehydrateEnum(fieldName, v) {
    const enumType = ENUM_STRING_FIELDS[fieldName];
    if (!enumType || typeof v !== "string") return v;
    const values = globalThis[enumType];
    return (values && values[v]) || { name: v };
}

// Enum-value LITERALS are stored as an AtomicValue whose string payload is
// `Owner.ValueName` (no dedicated union member exists — enum PDOs are too
// small to pay the table overhead). Mirror the reference reconstructor
// (truffle AtomicValueEnumReconstructor): when the AtomicValue's genericType
// points at an Enumeration, swap the string for the runtime's enum-value
// singleton (the generated `const <Enum> = {VALUE: {name}}` exports, merged
// onto globalThis). Non-enum-typed strings pass through — they're real
// String literals.
function rehydrateAtomicEnum(raw, genericType) {
    const t = genericType && genericType.type;
    const typePath = t && (t.__purePath ?? t.path);
    if (typeof typePath !== "string"
            || !globalThis.__metadataInstanceOf
            || !globalThis.__metadataInstanceOf(typePath, "meta::pure::metamodel::type::Enumeration")) {
        return raw;
    }
    const name = raw.slice(raw.lastIndexOf(".") + 1);
    const values = globalThis[typePath.split("::").pop()];
    return (values && values[name]) || { name };
}

// Marshal a decoded value into the shape the translated runtime expects:
//   scalar         -> as-is
//   ReadPointerRef -> element proxy (separate element)
//   ReadAncestorRef-> the ancestor's (possibly still-filling) output object
//   inline node    -> self-describing object (proxies for type refs within)
//
// `stack` mirrors the writer's per-element write stack: the output objects of
// the table chain from the element root down to the table whose fields are
// being filled (base = the element's own proxy). An AncestorRef(depth) is a
// back-reference depth levels up that chain — depth 0 is the containing table
// itself — so resolving it returns the same mutable object, closing the cycle
// exactly as the JVM wrapper's _fbParent() walk does. This is the wiring half
// of AncestorRef; the format half (decoding depth) lives in reader.pure.
export function marshal(sx, v, stack = []) {
    if (v === undefined || v === null) return undefined;
    if (Array.isArray(v)) return v.map((x) => marshal(sx, x, stack));
    if (isPointerRef(v)) {
        const arr = asList(v.segs);
        const kind = POINTER_KINDS[Number(v.kind)] ?? "Element";
        // Stereotype/Tag pointers aren't elements: the path is [..profile, name].
        // Synthesize the annotation with its profile + value (name).
        if (kind === "Stereotype" || kind === "Tag") {
            const cls = kind === "Stereotype" ? "meta::pure::metamodel::extension::Stereotype" : "meta::pure::metamodel::extension::Tag";
            return {
                profile: globalThis.__pureResolve(arr.slice(0, -1).join("::")),
                value: arr[arr.length - 1],
                classifierGenericType: { type: globalThis.__pureResolve(cls) },
            };
        }
        // (Qualified)Property pointers are TempCompilerPointers, not elements: the
        // path is [..owner segments, member]. Rebuild the pointer object so the
        // graph printer's PropertyPointer/QualifiedPropertyPointer arms match.
        if (kind === "Property" || kind === "QualifiedProperty") {
            const cls = "meta::pure::metamodel::pointer::" + kind + "Pointer";
            const ownerPath = arr.slice(0, -1).join("::");
            const member = arr[arr.length - 1];
            const ptr = {
                path: ownerPath,
                element: member,
                classifierGenericType: { type: globalThis.__pureResolve(cls) },
            };
            // Property-shaped reads resolve lazily through the owner element
            // (e.g. the translator's findAssociationPartner walks
            // `cls.propertiesFromAssociations` reading `.name`/`.owner`/
            // `.multiplicity`). Non-enumerable so the pointer's printed/
            // compared shape stays exactly the printer-matched trio above.
            const resolved = () => {
                const props = globalThis.__metadataRead(ownerPath, "properties");
                return (Array.isArray(props) ? props : [props]).find((p) => p && p.name === member);
            };
            Object.defineProperty(ptr, "name", { get: () => member });
            Object.defineProperty(ptr, "owner", { get: () => globalThis.__pureResolve(ownerPath) });
            Object.defineProperty(ptr, "multiplicity", { get: () => resolved()?.multiplicity });
            Object.defineProperty(ptr, "genericType", { get: () => resolved()?.genericType });
            return ptr;
        }
        const p = arr.join("::");
        // Mirror the JVM's PointerRefResolver: getElement() on a path that
        // isn't stored yields null (e.g. the reference writer's toString
        // fallback for relation columns — a bare column name). A synthetic
        // proxy here would flow a phantom element into match dispatch.
        // Optimization GenericTypes are the exception: not stored, but the
        // resolver synthesizes them (readProp's synthOptGenericType).
        if (globalThis.__metadataPathToElement
                && globalThis.__metadataPathToElement(p, "::") === null
                && !p.startsWith(OPT_PREFIX)) {
            return undefined;
        }
        return globalThis.__pureResolve(p);
    }
    if (isAncestorRef(v)) {
        const depth = Number(v.depth);
        const idx = stack.length - 1 - depth;
        if (idx < 0) throw new Error(`AncestorRef depth ${depth} exceeds marshal chain (${stack.length} deep)`);
        return stack[idx];
    }
    if (isFbsNode(v)) {
        // A scalar `*ValueDef` wraps a single primitive in `val` (the FlatBuffer
        // encoding of an AtomicValue's raw payload). Unwrap it back to the bare
        // scalar the runtime holds in memory. (ValueDefs are not write-stack
        // levels — the writer emits them via encode helpers, not writeX — so no
        // stack push here.)
        if (VALUE_DEFS.has(v.type)) {
            const raw = unwrapValueDef(v);
            return v.type === "DecimalValueDef" ? globalThis.__dec(raw) : raw;
        }
        const out = {};
        stack.push(out); // on the write path this table was pushed before its fields
        try {
            const tdef = sx.tables.get(v.type);
            for (const f of (tdef ? tdef.fields : [])) {
                const val = marshalField(sx, v, f, stack);
                if (val !== undefined) out[pureName(f.name)] = rehydrateEnum(f.name, val);
            }
            if (v.type === "AtomicValueDef" && typeof out.value === "string") {
                out.value = rehydrateAtomicEnum(out.value, out.genericType);
            }
        } finally {
            stack.pop();
        }
        return out;
    }
    return v;
}

// One field of an inline node, mirroring the writer's elision rules: elided
// strings/tables/unions/vectors stay absent (undefined); elided scalars surface
// their FlatBuffer default (so e.g. a 0-valued multiplicity bound isn't dropped
// from the self-describing object) — readField's present-or-default does that.
function marshalField(sx, node, f, stack) {
    if (f.vector) {
        const raw = readField(sx, node, f.name);
        if (!raw.length && !hasField(node, f)) return undefined;
        return marshal(sx, raw, stack);
    }
    if (f.cat === "table" || f.cat === "union" || f.type === "string") {
        const raw = readField(sx, node, f.name);
        return raw.length ? marshal(sx, raw[0], stack) : undefined;
    }
    if (f.cat === "enum") return hasField(node, f) ? readField(sx, node, f.name)[0] : undefined;
    return readField(sx, node, f.name)[0]; // scalar: present-or-default
}

// __metadataRead(address, prop): read one property off the element at `address`.
// Throws "unknown element" when the address isn't stored, which lets the
// runtime's __pureResolve synthesise optimization GenericTypes.
// Optimization GenericType singletons (…optimization::GenericType_<type>) are
// UserDefinedPackageableGenericType instances synthesised from the address —
// most aren't stored. Mirror what the JVM resolver returns for their slots.
const OPT_PREFIX = "meta::pure::metamodel::type::generics::optimization::GenericType_";
const UDPGT = "meta::pure::metamodel::type::generics::UserDefinedPackageableGenericType";
function synthOptGenericType(address, prop) {
    if (prop === "type") return globalThis.__pureResolve(address.slice(OPT_PREFIX.length).replace(/_/g, "::"));
    if (prop === "classifierGenericType") return { type: globalThis.__pureResolve(UDPGT) };
    if (prop === "typeArguments" || prop === "multiplicityArguments" || prop === "typeVariableValues") return [];
    return undefined;
}

// Lambda AST sub-addresses: the translator wraps every translated lambda with
// `__lambda(arrow, '<rootPath>$lambda/<idx>')` (wrapLambdaWithAst), and
// reflective reads on the wrapped closure (`.expressionSequence`,
// `.classifierGenericType`, …) come here with that synthetic address. The
// lambda's AST is stored INSIDE its containing element's blob, and `<idx>`
// was assigned by the translator's collectLambdasInOrder over the root's
// expressionSequence — so resolve by reading the base element's marshaled
// body and walking it with the SAME translated function (available when the
// translator stack is loaded; without it, fall through to unknown-element,
// as before). On Truffle the equivalent addresses resolve against the
// injected graph; this is the PDB-backed analog.
const LAMBDA_SEP = "$lambda/";
const COLLECT_LAMBDAS =
    "meta$external$language$javascript$translation$collectLambdasInOrder_ValueSpecification_MANY__LambdaFunction_MANY_";
function resolveLambdaAst(store, address) {
    const at = address.indexOf(LAMBDA_SEP);
    const basePath = address.slice(0, at);
    const idx = Number(address.slice(at + LAMBDA_SEP.length));
    const collect = globalThis[COLLECT_LAMBDAS];
    if (typeof collect !== "function" || !Number.isInteger(idx) || !store.has(basePath)) return undefined;
    const exprSeq = globalThis.__metadataRead(basePath, "expressionSequence");
    const lambdas = collect(exprSeq ?? []);
    return (Array.isArray(lambdas) ? lambdas : [lambdas])[idx];
}

// The ROOT package ('::') isn't a stored element — synthesize it: its
// children are the top-level entries across all modules (each PDB's Package
// elements are path-addressed, so top level = paths with no '::').
function synthRoot(store, prop) {
    // '::' matches how the in-memory compiler root prints (the graph printer
    // renders a package value by name; the golden shows `AtomicValue  ::`).
    if (prop === "name") return "::";
    // The optimization-GenericType singleton proxy (same convention as
    // synthOptGenericType): a bare `{type: …}` object has no classifier, and
    // permissive __matchType would let it match ANY class arm (e.g. the
    // printer's GenericTypeOperation arm).
    if (prop === "classifierGenericType") return globalThis.__pureResolve(OPT_PREFIX + "meta_pure_metamodel_Package");
    if (prop === "children") {
        const seen = new Set();
        for (const m of store.modules ?? []) {
            for (const p of m.paths?.() ?? []) if (!p.includes("::")) seen.add(p);
        }
        return [...seen].map((p) => globalThis.__pureResolve(p));
    }
    return undefined;
}

function readProp(store, address, prop) {
    if (address === "::") return synthRoot(store, prop);
    const node = store.resolve(address);
    if (!node) {
        if (address.startsWith(OPT_PREFIX)) return synthOptGenericType(address, prop);
        if (address.includes(LAMBDA_SEP)) {
            const lam = resolveLambdaAst(store, address);
            if (lam !== undefined && lam !== null) {
                // A captured lambda VALUE is a translated closure: its AST lives
                // at its own address, not on the function object.
                if (typeof lam === "function" && typeof lam.__purePath === "string" && lam.__purePath !== address) {
                    return globalThis.__metadataRead(lam.__purePath, prop);
                }
                return lam[prop];
            }
        }
        throw new Error("unknown element: " + address);
    }
    // LIVE element (in-memory module): the PDO already IS Pure values, so read
    // the property straight off it. None of what follows applies — there is no
    // FlatBuffer to decode, no schema field to look up, and no pointer/ancestor
    // ref to marshal. Marshalling's job is lazy PDB decoding; this isn't that.
    // A translated closure indexed as a live element (e.g. the adapter's
    // canonical lambda injected as `__local::root`) is a JS function tagged with
    // its address; its AST lives at that address, not on the function object.
    if (node.live) {
        const obj = node.obj;
        if (typeof obj === "function" && typeof obj.__purePath === "string" && obj.__purePath !== address) {
            return globalThis.__metadataRead(obj.__purePath, prop);
        }
        return obj[prop];
    }
    const sx = store.schema;
    const f = fieldDef(sx, node.type, prop);
    if (!f) return undefined;
    // The marshal chain starts at the element itself: an AncestorRef reaching
    // all the way up resolves to the element's proxy (the runtime's identity
    // for it), as the JVM wrapper walk terminates at the element wrapper.
    const stack = [globalThis.__pureResolve(address)];
    // Package children are the UNION over every archive that stores this
    // package (each PDB's Package element lists only its own compile's
    // children), deduped by pointer path — the same merged-package view the
    // JVM/Truffle hosts present.
    if (node.type === "PackageDef" && f.name === "children" && store.resolveAll) {
        const seen = new Set();
        const merged = [];
        for (const n of store.resolveAll(address)) {
            for (const c of readField(sx, n, "children")) {
                const key = isPointerRef(c) ? asList(c.segs).join("::") : c;
                if (seen.has(key)) continue;
                seen.add(key);
                merged.push(marshal(sx, c, stack));
            }
        }
        return merged;
    }
    const raw = readField(sx, node, f.name);
    // An absent vector ([*]) field is [], not undefined ([*]=array convention);
    // absent strings/tables/unions are undefined; elided scalars are their default.
    if (f.vector) return marshal(sx, raw, stack);
    if (!raw.length) return undefined;
    return rehydrateEnum(f.name, marshal(sx, raw[0], stack));
}

export function installMetadataGlobals(store, opts = {}) {
    const log = opts.log ?? [];
    const cap = opts.cap ?? 5000;
    const rec = (entry) => { if (log.length < cap) log.push(entry); };

    // The full generalization graph from the PDB — covers the metamodel AND the
    // meta::pure::protocol::grammar::* nodes (they're stored elements too), so a
    // separate protocol-grammar oracle isn't needed here (unlike the parser host,
    // which has no PDB).
    globalThis.__metadataSubtypeOf = (sub, sup) => store.subtypeOf(sub, sup);

    globalThis.__metadataInstanceOf = (valuePath, typePath) => store.instanceOf(valuePath, typePath);

    // pathToElement accepts a custom separator (e.g. '.'); normalize to '::'
    // before the lookup, and return the canonical '::' address (it becomes
    // the proxy's identity downstream).
    globalThis.__metadataPathToElement = (path, sep) => {
        const p = sep && sep !== "::" ? path.split(sep).join("::") : path;
        if (p === "" || p === "::") return "::"; // the Root package (synthesized)
        return store.has(p) ? p : null;
    };

    // Read a property off a stored element; throws "unknown element" for
    // non-stored addresses (drives the optimization-GenericType synthesis).
    // Metadata is immutable while the module list is stable, so cache
    // address -> (prop -> marshalled value) — the same read recurs heavily
    // (e.g. Any.generalizations). Registering/unregistering a module (e.g.
    // layering a freshly written archive for a round-trip) flushes it all; one
    // live element coming or going (javascript::execute's call-scoped graph)
    // evicts only the reads that depend on it: its own address and its
    // `<path>$lambda/<n>` sub-addresses, which resolve through that element.
    const readCache = new Map();
    const lambdaAddresses = new Map(); // element path -> its cached `$lambda/` addresses
    const elementOf = (address) => {
        const i = address.indexOf(LAMBDA_SEP);
        return i < 0 ? address : address.slice(0, i);
    };
    const evictReads = (path) => {
        if (path === undefined) { readCache.clear(); lambdaAddresses.clear(); return; }
        readCache.delete(path);
        for (const a of lambdaAddresses.get(path) ?? []) readCache.delete(a);
        lambdaAddresses.delete(path);
    };
    if (store.addInvalidationListener) store.addInvalidationListener(evictReads);
    globalThis.__metadataRead = (address, prop) => {
        let props = readCache.get(address);
        if (props !== undefined) {
            const hit = props.get(prop);
            if (hit !== undefined || props.has(prop)) return hit;
        }
        rec({ fn: "read", address, prop });
        const v = readProp(store, address, prop);
        if (props === undefined) {
            readCache.set(address, props = new Map());
            const element = elementOf(address);
            if (element !== address) {
                let set = lambdaAddresses.get(element);
                if (set === undefined) lambdaAddresses.set(element, set = new Set());
                set.add(address);
            }
        }
        props.set(prop, v);
        return v;
    };

    // Invoking a metadata function proxy routes to the registry, which asks
    // its modules in order — the in-memory module (when registered) resolves
    // the path's translated global; without one this throws, as before.
    globalThis.__metadataInvoke = (path, args) => { rec({ fn: "invoke", path }); return store.invoke(path, args); };

    // Metadata-enumeration natives, answered by reading the PDB. Results are
    // element proxies (lazy property access routes back through __metadataRead).
    globalThis.__findAllTypes = () => store.allTypes().map((p) => globalThis.__pureResolve(p));
    globalThis.__findFunctionsByNameAndArity = (name, arity) =>
        store.functionsByNameAndArity(name, arity).map((p) => globalThis.__pureResolve(p));

    return log;
}
