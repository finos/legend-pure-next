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

// The IN-MEMORY module kind. Deliberately free of every PDB concern: no
// FlatBuffer decode, no schema, no zip — the elements it holds are live Pure
// values produced by a compile, so there is nothing to decode. Contrast
// pdb/module.js, which exists precisely to decode archive bytes lazily.

/**
 * Runtime-created metadata. Two halves:
 *
 *  - FUNCTIONS: translated global functions ARE this module's function store.
 *    Invoking a metadata function proxy resolves the proxy's Pure path to its
 *    signature-mangled global and calls it. Call `invalidate()` after eval'ing
 *    new translated sources so the global-key index rebuilds.
 *
 *  - ELEMENTS: `addElements` indexes the LIVE PDOs a compile produced, so
 *    reflection over just-compiled code (`x::Test.properties.name`) resolves
 *    without a PDB. These need none of the PdbModule machinery — no
 *    FlatBuffer decode, no schema lookup, no pointer-ref marshalling — because
 *    the objects already ARE Pure values. `node()` therefore hands back a
 *    `{live:true, obj}` marker and marshal.js reads the property straight off
 *    it (see readProp). That is the whole point: marshalling exists to decode
 *    PDB bytes lazily; in-memory elements have nothing to decode.
 *
 * Class/enum companions in runtime-lib's path-keyed registries
 * (__registerClass / __classRef) are a separate, narrower structure — name +
 * eval closures for qualified-property dispatch — not a reflection view.
 */
export function createInMemoryModule(name, dependencies = []) {
    let fnKeys = null;
    /** Pure path -> live compiled element (PDO). */
    const elements = new Map();
    function resolveFn(path, arity) {
        const mangled = path.split("::").join("$");
        if (typeof globalThis[mangled] === "function") return globalThis[mangled];
        if (!fnKeys) fnKeys = Object.keys(globalThis).filter((k) => typeof globalThis[k] === "function");
        const prefix = mangled + "_";
        const candidates = fnKeys.filter((k) => k.startsWith(prefix));
        const matches = candidates.length > 1 && arity !== undefined
            ? candidates.filter((k) => globalThis[k].length === arity)
            : candidates;
        if (matches.length === 1) return globalThis[matches[0]];
        if (matches.length === 0) return undefined;
        throw new Error(`ambiguous translated function for ${path}/${arity}: ${matches.join(", ")}`);
    }
    return {
        name, dependencies, kind: "memory",
        hasElement: (path) => elements.has(path),
        /** `{live:true, obj}` so readProp reads the PDO directly (no fbs decode). */
        node(path) {
            const obj = elements.get(path);
            return obj === undefined ? undefined : { live: true, obj };
        },
        /** Index one live element under `path`, flushing the registry's caches. */
        addElement(path, obj) { if (path) { elements.set(path, obj); (this.onPathChange ?? this.onChange)?.(path); } },
        /** Drop one element — used for call-scoped graphs (javascript::execute). */
        removeElement(path) { if (elements.delete(path)) (this.onPathChange ?? this.onChange)?.(path); },
        /**
         * Index a compile's elements, deriving each path with __elementToPath
         * (translated; only safe to call after the generated JS is loaded).
         * Returns the paths indexed.
         */
        addElements(els) {
            const added = [];
            for (const e of els ?? []) {
                let path;
                try { path = globalThis.__elementToPath?.(e); } catch { path = undefined; }
                if (path) { elements.set(path, e); added.push(path); }
            }
            if (added.length) this.onChange?.();
            return added;
        },
        paths: () => elements.keys(),
        get elementCount() { return elements.size; },
        resolveFn,
        invoke(path, args) {
            const fn = resolveFn(path, args.length);
            if (!fn) throw new Error(`no translated function for ${path}/${args.length}`);
            return fn(...args);
        },
        invalidate() { fnKeys = null; },
    };
}
