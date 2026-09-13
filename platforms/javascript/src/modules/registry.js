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

// Module architecture for metadata, mirroring the truffle/bootstrap runtimes:
// a MODULE is a named, dependency-aware unit of metadata — one per .pdb
// archive (PdbModule) or an in-memory equivalent (InMemoryModule) — and a
// REGISTRY resolves paths across the registered modules in order, owning the
// cross-module concerns: package-children union (each PDB's Package element
// lists only its own compile's children; the runtime view is their union),
// the generalization/classifier graph queries, function-index union, invoke
// routing, and cache invalidation when modules are registered/unregistered
// (e.g. a freshly written archive layered over the base PDBs for a
// round-trip, then removed).
//
// Decoding is done by the TRANSLATED SELF-HOSTED PURE READER (pdb/reader/reader.pure
// via pure-reader.js) — only touched elements are inflated/decoded; archive
// opening and inflation stay host wiring (zip/zip.js).
//
// Environment-neutral: takes the m3.fbs schema TEXT (or an already-parsed
// schema index) and already-opened archives. The schema is parsed on first
// use — the translated parser lives in generated JS the hosts import AFTER
// building the registry, so nothing here may touch translated code until the
// first real read.

import {
    parseSchema, toPureBytes, elementNode, rootNode, readField,
    isPointerRef, isAncestorRef, isFbsNode,
} from "./pdb/pure-reader.js";

import { createPdbModule } from "./pdb/module.js";
export { createPdbModule };
export { createInMemoryModule } from "./memory/module.js";

/**
 * Resolution across an ordered module list (first match wins). `schemaSrc` is
 * the m3.fbs text (parsed lazily via the translated parser) or an
 * already-parsed schema index. The returned registry is what the metadata
 * metadata globals consume; hosts assemble it from their module lists.
 */
export function createRegistry(schemaSrc, modules = []) {
    let sx = typeof schemaSrc === "string" ? null : schemaSrc;
    const schema = () => sx ?? (sx = parseSchema(schemaSrc));
    const mods = [...modules];

    // --- caches, all flushed when the module list changes -----------------
    const NODE_CACHE_MAX = 512; // Pure Integer[*] bytes are BigInt lists (~30x raw size)
    const nodeCache = new Map(); // insertion order = LRU order (refreshed on hit)
    const supersMemo = new Map();
    const classifierMemo = new Map();
    const subMemo = new Map();
    let typesCache = null;
    let fnEntries = null;
    const invalidationListeners = [];
    /**
     * Drop the caches for ONE path. A live module adding or removing a single
     * element (javascript::execute injects a call-scoped graph, so this happens
     * twice per call) must not flush the whole nodeCache: the gallery does ~110
     * adapter calls, and a full flush made every one of them re-inflate and
     * re-convert every element it touched — which profiled as 42% in
     * toPureBytes plus the GC churn behind it.
     */
    function invalidatePath(path) {
        nodeCache.delete(path);
        supersMemo.delete(path); classifierMemo.delete(path);
        typesCache = null; fnEntries = null;
        // subMemo is keyed "sub<:sup"; only entries naming this path can change.
        for (const k of [...subMemo.keys()]) if (k.includes(path)) subMemo.delete(k);
        for (const l of invalidationListeners) l();
    }

    function invalidate() {
        nodeCache.clear(); supersMemo.clear(); classifierMemo.clear(); subMemo.clear();
        typesCache = null; fnEntries = null;
        for (const l of invalidationListeners) l();
    }

    function register(module, opts = {}) {
        if (opts.front) mods.unshift(module); else mods.push(module);
        // A module whose contents can change after registration (the in-memory
        // one) needs to flush the registry's caches: `resolve` memoizes misses
        // in nodeCache, so a path probed during compile stays cached as null
        // and a later addElement would be invisible.
        module.onChange = invalidate;
        module.onPathChange = invalidatePath;
        invalidate();
        return module;
    }
    function unregister(module) {
        if (module) { module.onChange = undefined; module.onPathChange = undefined; }
        const i = mods.indexOf(module);
        if (i >= 0) mods.splice(i, 1);
        invalidate();
    }

    const has = (path) => mods.some((m) => m.hasElement(path));

    /** A resolved node only when it is a real FlatBuffer node (not a live PDO). */
    const fbsNodeOrNull = (n) => (n && n.live ? null : n);

    function resolve(path) {
        const hit = nodeCache.get(path);
        if (hit !== undefined) {
            nodeCache.delete(path);
            nodeCache.set(path, hit);
            return hit;
        }
        let node = null;
        for (const m of mods) {
            const n = m.node(path);
            if (n !== undefined) { node = n; break; }
        }
        nodeCache.set(path, node);
        if (nodeCache.size > NODE_CACHE_MAX) nodeCache.delete(nodeCache.keys().next().value);
        return node;
    }

    // Every module's node for `path` — the metadata globals union Package children
    // across these (only Packages legitimately appear in several modules).
    function resolveAll(path) {
        const out = [];
        for (const m of mods) {
            const n = m.node(path);
            if (n !== undefined) out.push(n);
        }
        return out;
    }

    // --- graph queries (over the merged view) ------------------------------
    const refToPath = (v) => (isPointerRef(v) ? [v.segs].flat().join("::") : null);
    const OPT_PREFIX = "meta::pure::metamodel::type::generics::optimization::GenericType_";
    const decodeOpt = (p) => (p && p.startsWith(OPT_PREFIX) ? p.slice(OPT_PREFIX.length).replace(/_/g, "::") : null);

    // The Type path a GenericType denotes. `gt` may be: a PointerRef to a
    // GenericType element (optimization singleton -> decode; otherwise resolve
    // and read its type_), or an inline GenericType def (read type_).
    function genericTypePath(gt) {
        if (!gt) return null;
        if (isPointerRef(gt)) {
            const p = refToPath(gt);
            return decodeOpt(p) ?? genericTypePath(resolve(p));
        }
        if (isAncestorRef(gt) || !isFbsNode(gt)) return null;
        return refToPath(readField(schema(), gt, "type_")[0]);
    }

    function directSupers(path) {
        const memo = supersMemo.get(path);
        if (memo) return memo;
        // LIVE in-memory nodes carry no FlatBuffer, so they cannot feed readField.
        // They are skipped here deliberately: the generalization/classifier graph
        // stays PDB-only, which is exactly the behaviour before in-memory elements
        // became addressable (they simply didn't resolve). Reflection reads work
        // (see marshal.js's `node.live` branch); subtype/classifier queries over
        // just-compiled elements are not supported yet — wiring them needs the
        // live-object walk, not a decode.
        const node = fbsNodeOrNull(resolve(path));
        const out = [];
        if (node) {
            for (const gen of readField(schema(), node, "generalizations")) {
                const p = isFbsNode(gen) ? genericTypePath(readField(schema(), gen, "general")[0]) : null;
                if (p) out.push(p);
            }
        }
        supersMemo.set(path, out);
        return out;
    }

    function subtypeOf(sub, sup) {
        if (sub === sup) return true;
        const key = sub + "<:" + sup;
        if (subMemo.has(key)) return subMemo.get(key);
        subMemo.set(key, false); // cycle guard
        let res = false;
        for (const s of directSupers(sub)) {
            if (s === sup || subtypeOf(s, sup)) { res = true; break; }
        }
        subMemo.set(key, res);
        return res;
    }

    const UDPGT = "meta::pure::metamodel::type::generics::UserDefinedPackageableGenericType";
    const LAMBDA = "meta::pure::metamodel::function::LambdaFunction";
    function classifierPath(path) {
        if (classifierMemo.has(path)) return classifierMemo.get(path);
        // See directSupers: live nodes are not fbs-readable, so they fall through
        // to the synthesized-address logic below, as before they were addressable.
        const node = fbsNodeOrNull(resolve(path));
        // Synthesized addresses that aren't stored elements: optimization
        // GenericType singletons are UserDefinedPackageableGenericType
        // instances; `<root>$lambda/<idx>` sub-addresses (wrapLambdaWithAst's
        // tags on translated closures) are LambdaFunctions — so
        // instanceOf/match type checks resolve for both.
        const res = node
            ? genericTypePath(readField(schema(), node, "classifier_generic_type")[0])
            : (path.startsWith(OPT_PREFIX) ? UDPGT
                : (path.includes("$lambda/") ? LAMBDA
                    : (path === "::" ? "meta::pure::metamodel::Package" : null)));
        classifierMemo.set(path, res);
        return res;
    }
    function instanceOf(valuePath, typePath) {
        const cp = classifierPath(valuePath);
        return cp ? subtypeOf(cp, typePath) : false;
    }

    // findAllTypes(): every element whose classifier is a (subtype of) Type —
    // Class, Enumeration, PrimitiveType, etc. across all registered modules.
    const TYPE = "meta::pure::metamodel::type::Type";
    function allPaths() {
        const seen = new Set();
        for (const m of mods) for (const p of m.paths?.() ?? []) seen.add(p);
        return seen;
    }
    function allTypes() {
        if (typesCache) return typesCache;
        typesCache = [];
        for (const path of allPaths()) {
            const cp = classifierPath(path);
            if (cp && (cp === TYPE || subtypeOf(cp, TYPE))) typesCache.push(path);
        }
        return typesCache;
    }

    // findFunctionsByNameAndArity(name, arity): union of each PDB module's
    // purpose-built `functionIndex` section. arity = function_type.parameters.
    function functionIndexEntries() {
        if (fnEntries) return fnEntries;
        fnEntries = [];
        for (const m of mods) {
            if (m.kind !== "pdb" || !m.archive.has("functionIndex")) continue;
            const root = rootNode(toPureBytes(m.archive.read("functionIndex")), "FunctionIndex");
            for (const e of readField(schema(), root, "entries")) {
                const ft = readField(schema(), e, "function_type")[0];
                const params = isFbsNode(ft) ? readField(schema(), ft, "parameters") : [];
                fnEntries.push({
                    path: readField(schema(), e, "full_path")[0],
                    name: readField(schema(), e, "function_name")[0],
                    arity: params.length,
                });
            }
        }
        return fnEntries;
    }
    function functionsByNameAndArity(name, arity) {
        const n = Number(arity);
        return functionIndexEntries().filter((e) => e.name === name && e.arity === n).map((e) => e.path);
    }

    // Invoke a metadata-addressed function: first module that can, answers.
    function invoke(path, args) {
        for (const m of mods) {
            if (m.invoke) return m.invoke(path, args);
        }
        throw new Error(`__metadataInvoke not implemented: ${path} (no in-memory module registered)`);
    }

    return {
        get schema() { return schema(); },
        get modules() { return [...mods]; },
        register, unregister,
        addInvalidationListener: (l) => invalidationListeners.push(l),
        has, resolve, resolveAll,
        subtypeOf, instanceOf, classifierPath, directSupers,
        allTypes, functionsByNameAndArity, invoke,
        get elementCount() { return allPaths().size; },
    };
}
