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

// PureRuntime for the JavaScript host: provides everything translated code needs from the
// host — metadata access over the registry, the natives, parsing through its language
// extensions, source text — on ONE global, `globalThis.__pureHost`. runtime-lib's
// `__metadataRead`, `__getChild`, `__pureParseTop`, … forward to it. It also executes
// translated functions. JavaScript extras: evalJs (evaluates emitted JS into the shared
// global scope) and the in-memory runtime module (translated globals are its
// function store).

import { metadataHost } from "../compiler/module/pdbModule/marshal.js";
import { NativeRegistry, hostHookFor } from "../execution/natives/NativeRegistry.js";
import { DEFAULT_LANGUAGE_EXTENSIONS } from "./LanguageExtensions.js";
import { translateProgramElements } from "../execution/translate-elements.js";

// The translated compiler-pure entry point, silent overload (generated/compiler.js).
const COMPILE = "meta$pure$compiler$compile_PureFile_MANY__Boolean_1__Boolean_1__CompilationResult_1_";
const asArray = (v) => (v === undefined || v === null ? [] : Array.isArray(v) ? v : [v]);

/** Modules in dependency order (a module after the modules it depends on). */
function dependencyOrder(modules) {
    const byName = new Map(modules.map((m) => [m.name, m]));
    const ordered = [], visited = new Set();
    const visit = (module) => {
        if (visited.has(module.name)) return;
        visited.add(module.name);
        for (const dependency of module.dependencies ?? []) if (byName.has(dependency)) visit(byName.get(dependency));
        ordered.push(module);
    };
    modules.forEach(visit);
    return ordered;
}

// The translated parser-mappings entry point (parser-mappings-bundle.js).
const PARSE_DOCUMENT = "meta$pure$parser$mappings$interpreter$parseDocument_AntlrContext_1__String_1__Boolean_1__Pair_MANY__PureFile_1_";

export class PureRuntime {
    static builder() { return new PureRuntimeBuilder(); }

    #registry;
    #natives;
    #languageExtensions;
    #evalJs;
    #runtimeModule;
    #host = Object.create(null);

    /** Use PureRuntime.builder(). */
    constructor({ registry, natives, languageExtensions, evalJs, runtimeModule, sourceText }) {
        this.#registry = registry;
        this.#natives = natives;
        this.#languageExtensions = languageExtensions;
        this.#evalJs = evalJs;
        this.#runtimeModule = runtimeModule;
        Object.assign(this.#host, metadataHost(registry));
        for (const [signature, factory] of natives.entries()) this.#install(hostHookFor(signature), factory(this));
        // Pure's `parse` (and compileSource's source form) compile to runtime-lib's __pureParseTop.
        this.#install("pureParseTop", (sourceId, content) => this.parse(sourceId, content));
        // Source text of evaluated JavaScript, for Pure stack traces (runtime-lib's call sites).
        if (sourceText) this.#install("hostSourceText", sourceText);
        globalThis.__pureHost = this.#host;
    }

    #install(name, value) {
        this.#host[name] = value;
    }

    get registry() { return this.#registry; }
    get natives() { return this.#natives; }
    get languageExtensions() { return this.#languageExtensions; }
    get evalJs() { return this.#evalJs; }
    get runtimeModule() { return this.#runtimeModule; }
    /** What this runtime provides to translated code (installed as globalThis.__pureHost). */
    get host() { return this.#host; }

    /**
     * Parse Pure source into a PureFile: the Top grammar splits the source into sections, then
     * each section is parsed by the parser a registered language extension contributes for it.
     * Source without a section header is a `###Pure` section.
     */
    parse(sourceId, content) {
        const parseDocument = globalThis[PARSE_DOCUMENT];
        if (typeof globalThis.__parseAntlr !== "function" || typeof parseDocument !== "function") {
            throw new Error("PureRuntime.parse: the parser bundles are not loaded (antlr-bundle.js, parser-mappings-bundle.js)");
        }
        const syntheticHeader = !content.startsWith("###");
        const effective = syntheticHeader ? "###Pure\n" + content : content;
        const document = globalThis.__parseAntlr(effective, "TopParser", sourceId, 0n);
        return parseDocument(document, sourceId, syntheticHeader, this.sectionParsers());
    }

    /** The section parsers of every registered language extension. */
    sectionParsers() {
        return this.#languageExtensions.flatMap((extension) => extension.sectionParsers(this));
    }

    /** Add or replace source `sourceId` in the registered in-memory module `moduleName`. */
    setSource(moduleName, sourceId, content) { this.#sourceModule(moduleName).setSource(sourceId, content); }

    /** Remove source `sourceId` from the registered in-memory module `moduleName`. */
    removeSource(moduleName, sourceId) { this.#sourceModule(moduleName).removeSource(sourceId); }

    #sourceModule(name) {
        const module = this.#registry.module(name);
        if (!module) throw new Error(`no module '${name}' is registered`);
        if (typeof module.setSource !== "function") throw new Error(`module '${name}' does not hold sources`);
        return module;
    }

    /**
     * Compile, in dependency order, the in-memory modules with code: parse their sources through the
     * language extensions, compile them with compiler-pure (translated), then replace the module's
     * elements with the result and evaluate their translated JavaScript — so later modules, and
     * execute, see the fresh elements. Stops at the first module that fails; that module keeps its
     * previous elements. Returns `{ elements, errors }` over the modules compiled.
     */
    compile() {
        const elements = [], errors = [];
        for (const module of dependencyOrder(this.#registry.modules)) {
            if (typeof module.hasCode !== "function" || !module.hasCode()) continue;
            const compile = globalThis[COMPILE];
            if (typeof compile !== "function") throw new Error("PureRuntime.compile: compiler-pure is not loaded (generated/compiler.js)");
            if (typeof this.#evalJs !== "function") throw new Error("PureRuntime.compile: withEvalJs(evalJs) is required to run compiled code");
            let result;
            try {
                result = compile(module.sources().map(({ sourceId, content }) => this.parse(sourceId, content)), false, true);
            } catch (e) {
                errors.push(`${module.name}: ${e && e.message ? e.message : e}`);
                break;
            }
            const moduleErrors = asArray(result.errors).map(String);
            if (moduleErrors.length) {
                errors.push(...moduleErrors);
                break;
            }
            const compiled = asArray(result.elements);
            module.clearElements();
            translateProgramElements(compiled, this.#evalJs, module.name, module);
            module.invalidate?.();
            elements.push(...compiled);
        }
        return { elements, errors };
    }

    /** Execute `fn` — an element from registry.getElement(path), or its path — with `args`. */
    execute(fn, ...args) {
        const path = typeof fn === "string" ? fn : fn?.__purePath;
        if (typeof path !== "string") {
            throw new Error("PureRuntime.execute: expected a function element from registry.getElement(path)");
        }
        // Translated functions are globals any in-memory module resolves; prefer the runtime module.
        const resolver = this.#runtimeModule ?? this.#registry.modules.find((m) => typeof m.resolveFn === "function");
        const impl = resolver?.resolveFn(path, args.length);
        if (typeof impl !== "function") throw new Error(`no translated function for ${path}/${args.length}`);
        return impl(...args);
    }

    /** Uninstall this runtime's host object (when it is still the installed one). */
    close() {
        if (globalThis.__pureHost === this.#host) delete globalThis.__pureHost;
    }
}

export class PureRuntimeBuilder {
    #registry = null;
    #natives = null;
    #languageExtensions = null;
    #evalJs = null;
    #runtimeModule = null;
    #sourceText = null;

    withRegistry(registry) { this.#registry = registry; return this; }
    withNatives(natives) { this.#natives = natives; return this; }
    /** Language extensions (their section parsers); DEFAULT_LANGUAGE_EXTENSIONS when not set. */
    withLanguageExtensions(extensions) { this.#languageExtensions = [...extensions]; return this; }
    withEvalJs(evalJs) { this.#evalJs = evalJs; return this; }
    withRuntimeModule(runtimeModule) { this.#runtimeModule = runtimeModule; return this; }
    /** `fileName -> source text` of evaluated JavaScript, for Pure stack traces. */
    withSourceText(sourceText) { this.#sourceText = sourceText; return this; }

    build() {
        if (!this.#registry) throw new Error("PureRuntime.builder(): withRegistry(registry) is required");
        return new PureRuntime({
            registry: this.#registry,
            natives: this.#natives ?? new NativeRegistry(),
            languageExtensions: this.#languageExtensions ?? DEFAULT_LANGUAGE_EXTENSIONS,
            evalJs: this.#evalJs,
            runtimeModule: this.#runtimeModule,
            sourceText: this.#sourceText,
        });
    }
}
