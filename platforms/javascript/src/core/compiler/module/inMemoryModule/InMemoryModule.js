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
// pdbModule/PdbModule.js, which exists precisely to decode archive bytes lazily.

/**
 * Runtime-created metadata. Two halves:
 *
 *  - FUNCTIONS: translated global functions ARE this module's function store.
 *    Invoking a metadata function proxy resolves the proxy's Pure path to its
 *    signature-mangled global and calls it. Call `invalidate()` after eval'ing
 *    new translated sources so the global-key index rebuilds.
 *
 *  - SOURCES: `setSource`/`removeSource` hold Pure source text the module compiles
 *    from; PureRuntime.compile replaces the module's elements with the result.
 *
 *  - ELEMENTS: `addElement(s)` indexes the LIVE PDOs a compile produced, so
 *    reflection over just-compiled code (`x::Test.properties.name`) resolves
 *    without a PDB. `node()` hands back a `{live:true, obj}` marker and
 *    marshal.js reads the property straight off it (see readProp).
 *
 * Class/enum companions in runtime-lib's path-keyed registries
 * (__registerClass / __classRef) are a separate, narrower structure — name +
 * eval closures for qualified-property dispatch — not a reflection view.
 */
export class InMemoryModule {
    #name;
    #dependencies;
    /** Pure path -> live compiled element (PDO). */
    #elements = new Map();
    /** Source id -> Pure source text (PureRuntime.setSource / compile). */
    #sources = new Map();
    #fnKeys = null;
    /** Set by ModuleRegistry.register: flush every cache / one path's caches. */
    onChange = undefined;
    onPathChange = undefined;

    constructor(name, dependencies = [], elements = []) {
        this.#name = name;
        this.#dependencies = [...dependencies];
        if (elements.length) this.addElements(elements);
    }

    get name() { return this.#name; }
    get dependencies() { return this.#dependencies; }
    /** In-memory modules are not bound to a package pattern. */
    get packagePattern() { return null; }
    get kind() { return "memory"; }
    get elementCount() { return this.#elements.size; }

    /** Add or replace source `sourceId`. */
    setSource(sourceId, content) { this.#sources.set(sourceId, content); }
    /** Remove source `sourceId`. */
    removeSource(sourceId) { this.#sources.delete(sourceId); }
    /** `[{ sourceId, content }]`, in the order the sources were first set. */
    sources() { return [...this.#sources].map(([sourceId, content]) => ({ sourceId, content })); }
    /** Whether the module has source to compile. */
    hasCode() { return this.#sources.size > 0; }

    /** The live elements the module holds. */
    elements() { return [...this.#elements.values()]; }
    /** Drop every element (before a compile replaces them), flushing the registry's caches. */
    clearElements() {
        if (!this.#elements.size) return;
        this.#elements.clear();
        this.onChange?.();
    }

    hasElement(path) { return this.#elements.has(path); }
    getElement(path) { return this.#elements.get(path) ?? null; }
    elementPaths() { return this.#elements.keys(); }

    /** `{live:true, obj}` so readProp reads the PDO directly (no fbs decode). */
    node(path) {
        const obj = this.#elements.get(path);
        return obj === undefined ? undefined : { live: true, obj };
    }

    /** Index one live element under `path`, flushing that path in the registry's caches. */
    addElement(path, obj) {
        if (!path) return;
        this.#elements.set(path, obj);
        (this.onPathChange ?? this.onChange)?.(path);
    }

    /** Drop one element — used for call-scoped graphs (javascript::execute). */
    removeElement(path) {
        if (this.#elements.delete(path)) (this.onPathChange ?? this.onChange)?.(path);
    }

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
            if (path) { this.#elements.set(path, e); added.push(path); }
        }
        if (added.length) this.onChange?.();
        return added;
    }

    /** The translated global for a Pure function path: exact mangled name, else a unique prefix match (by arity). */
    resolveFn(path, arity) {
        const mangled = path.split("::").join("$");
        if (typeof globalThis[mangled] === "function") return globalThis[mangled];
        if (!this.#fnKeys) this.#fnKeys = Object.keys(globalThis).filter((k) => typeof globalThis[k] === "function");
        const prefix = mangled + "_";
        const candidates = this.#fnKeys.filter((k) => k.startsWith(prefix));
        const matches = candidates.length > 1 && arity !== undefined
            ? candidates.filter((k) => globalThis[k].length === arity)
            : candidates;
        if (matches.length === 1) return globalThis[matches[0]];
        if (matches.length === 0) return undefined;
        throw new Error(`ambiguous translated function for ${path}/${arity}: ${matches.join(", ")}`);
    }

    invoke(path, args) {
        const fn = this.resolveFn(path, args.length);
        if (!fn) throw new Error(`no translated function for ${path}/${args.length}`);
        return fn(...args);
    }

    /** Rebuild the global-function index after new translated sources are evaluated. */
    invalidate() { this.#fnKeys = null; }
}
