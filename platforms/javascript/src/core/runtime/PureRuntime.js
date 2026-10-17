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
import { PLATFORM_PDBS } from "./PlatformModules.js";
import { coreLanguages } from "./LanguageExtensions.js";
import { translateProgramElements } from "../execution/translate-elements.js";

// The translated compiler-pure entry point (generated/pure/compiler/compiler.js): the overload that takes the
// language extensions, so a `###Diagram` section becomes a graph element rather than being
// parsed and dropped. Silent — the live progress bar belongs to the CLI.
const COMPILE = "platform$pure$compiler$compile_PureFile_MANY__CompilerExtension_MANY__Boolean_1__CompilationResult_1_";
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
const PARSE_DOCUMENT = "platform$pure$parser$mappings$interpreter$parseDocument_AntlrContext_1__String_1__Pair_MANY__PureFile_1_";

export class PureRuntime {
    static builder() { return new PureRuntimeBuilder(); }

    #registry;
    #natives;
    #languages;
    #evalJs;
    #runtimeModule;
    #host = Object.create(null);

    /** Use PureRuntime.builder(). */
    constructor({ registry, natives, languages, evalJs, runtimeModule, sourceText }) {
        this.#registry = registry;
        this.#natives = natives;
        this.#languages = languages;
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
    /** The languages this runtime parses and compiles — what `withLanguages(...)` named. */
    get languages() { return this.#languages; }
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
        const document = globalThis.__parseAntlr(content, "TopParser", sourceId, 0n);
        return parseDocument(document, sourceId, this.sectionParsers());
    }

    /** The section parsers of every registered language extension. */
    sectionParsers() {
        return this.#languages.flatMap((language) => language.sectionParsers(this));
    }

    /**
     * The compiler extensions of every registered language extension. Optional on an extension,
     * because a section whose text the compiler merely carries has no compiler half.
     */
    compilerExtensions() {
        return this.#languages.flatMap((language) => language.compilerExtensions?.(this) ?? []);
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
            if (typeof compile !== "function") {
                throw new Error("PureRuntime.compile: compiler-pure is not loaded, or predates the language-extension "
                                + "overload of compile (run `just javascript::generate`)");
            }
            if (typeof this.#evalJs !== "function") throw new Error("PureRuntime.compile: withEvalJs(evalJs) is required to run compiled code");
            let result;
            try {
                result = compile(module.sources().map(({ sourceId, content }) => this.parse(sourceId, content)),
                                 this.compilerExtensions(), true);
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
        // A Pure CompilationResult, built through the generated class so a caller reads it with the
        // same accessors as any other Pure value (`$r.elements`, `$r.errors`).
        const Cls = globalThis["platform$pure$compiler$CompilationResult"];
        return typeof Cls === "function" ? Object.assign(new Cls(), { elements, errors }) : { elements, errors };
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

/**
 * Each extension with ITS module — the one its manifest's `module` names, wherever it sits in `pdbs` —
 * and the rest of its pdbs. `loaded` holds the modules opened for the declared pdbs (the first
 * `declaredCount`, which belong to no extension) and then every extension's `pdbs`, in order.
 */
export function pairExtensionModules(descriptors, loaded, declaredCount) {
    return descriptors.reduce((acc, extension) => {
        const mine = loaded.slice(acc.start, acc.start + (extension.pdbs ?? []).length);
        const own = mine.find((module) => module.name === extension.module) ?? null;
        return {
            start: acc.start + mine.length,
            pairs: [...acc.pairs, { extension, own, others: mine.filter((module) => module !== own) }],
        };
    }, { start: declaredCount, pairs: [] }).pairs;
}

export class PureRuntimeBuilder {
    #registry = null;
    #natives = null;
    #languages = null;
    #evalJs = null;
    #runtimeModule = null;
    #sourceText = null;
    #loadModules = null;
    #loadTranslatedCode = null;

    withRegistry(registry) { this.#registry = registry; return this; }
    /** The natives to install. Required: `NativeRegistry.createDefault(...)` for the built-in ones. */
    withNatives(natives) { this.#natives = natives; return this; }
    /**
     * The extensions this runtime parses and compiles beyond ###Pure — `extensionNamed("diagram")`, or
     * `extensionLanguages` for every one built. ###Pure is never optional, so `build()` puts
     * `coreLanguages` first unless the list already names them. Their schema is what the registry decodes
     * with.
     */
    withLanguages(languages) { this.#languages = [...languages]; return this; }
    withEvalJs(evalJs) { this.#evalJs = evalJs; return this; }
    withRuntimeModule(runtimeModule) { this.#runtimeModule = runtimeModule; return this; }
    /** `fileName -> source text` of evaluated JavaScript, for Pure stack traces. */
    withSourceText(sourceText) { this.#sourceText = sourceText; return this; }

    /**
     * How to obtain the modules the LANGUAGES declare, so `buildAsync()` can register them and no host
     * has to name them. `load(repoRelativePaths) => Promise<Module[]>`, same order.
     *
     * ALL THE PATHS AT ONCE, not one per call, because the two environments load differently and only
     * the host knows how: Node opens files, and the browser fetches — in PARALLEL, behind one progress
     * bar. A loader called once per module would serialise those fetches and make the page slower.
     *
     * Injected rather than chosen here: this file must not reach for `node:fs` (the browser cannot
     * import it) nor assume `fetch` (Node's host reads from disk). It is the counterpart of the Java
     * platform's `withModuleRoot`, where opening a file is synchronous and the runtime can do it itself.
     */
    withModuleLoader(load) { this.#loadModules = load; return this; }

    /**
     * How to load the TRANSLATED CODE this runtime runs on — `load() => Promise<void>`, awaited by
     * `buildAsync()` after the runtime exists.
     *
     * AFTER, not before: the generated modules resolve metadata as they load (an enum constant reaches
     * for its Enumeration through `__pureResolve`), so the metadata globals this runtime installs have to
     * be in place first. Injected for the same reason as the module loader — Node imports from the file
     * system and the browser from URLs.
     */
    withTranslatedCode(load) { this.#loadTranslatedCode = load; return this; }

    /**
     * Register what the languages declare, then build.
     *
     * <p>The `pdbs` of the languages named in `withLanguages` — ###Pure's from its own manifest — and
     * `PLATFORM_PDBS`, what makes Pure runnable HERE; each named extension is registered as one act, its
     * module paired with the language it adds. An extension that was not named is not loaded. A module
     * the host already registered is left alone, and registration order is preserved because it decides
     * the order the archives are opened in: core.pdb has to be first.</p>
     */
    async buildAsync() {
        this.#requireComplete();
        if (typeof this.#loadModules !== "function") {
            throw new Error("PureRuntime.buildAsync(): withModuleLoader(load) is required — it is what "
                          + "reads the modules the languages declare (fs on Node, fetch in a browser)");
        }
        // What the NAMED languages declare — ###Pure's modules, then each named extension's — and the
        // platform's own (its metamodel and translator, which are the platform, not a choice).
        const descriptors = this.#languages.map((language) => language.descriptor);
        this.#registry.useLanguages(descriptors);
        const declared = [...new Set([...descriptors.filter((d) => !d.module).flatMap((d) => d.pdbs ?? []), ...PLATFORM_PDBS])];
        // Extensions are the languages with a `dir` (LanguageExtensions.js's rule): each arrives with its
        // own module. A core language with a module and no pdbs (the compiler test sections) is
        // activated by whichever host registers that module.
        const extensions = descriptors.filter((d) => d.dir);
        const loaded = await this.#loadModules([...declared, ...extensions.flatMap((e) => e.pdbs ?? [])]);
        loaded.slice(0, declared.length)
            .filter((module) => !this.#registry.module(module.name))
            .forEach((module) => this.#registry.register(module));
        // Each extension is paired with ITS module — the one its manifest's `module` names — whatever
        // else it declares and in whatever order; the rest of its pdbs are registered as plain modules.
        pairExtensionModules(extensions, loaded, declared.length).forEach(({ extension, own, others }) => {
            others.filter((module) => !this.#registry.module(module.name))
                .forEach((module) => this.#registry.register(module));
            this.#registry.registerExtension(extension, own ?? null);
        });
        // Every module's dependencies are present, and each extension came with its own module. Cheap
        // and repeatable, so a host that also validates loses nothing.
        this.#registry.validate();

        const runtime = this.build();
        if (typeof this.#loadTranslatedCode === "function") {
            await this.#loadTranslatedCode();
            // The in-memory module's elements were resolved against the code that was loaded BEFORE
            // this, so they are stale now. Both hosts did this by hand, immediately after loading.
            this.#runtimeModule?.invalidate?.();
        }
        return runtime;
    }

    #requireComplete() {
        if (!this.#registry) throw new Error("PureRuntime.builder(): withRegistry(registry) is required");
        if (!this.#natives) {
            throw new Error("PureRuntime.builder(): withNatives(...) is required — "
                + "NativeRegistry.createDefault(...) for the built-in natives");
        }
        // ###Pure is never optional, so it is always there: the always-active languages first, unless
        // the host already named them — as on Truffle and the Java platform.
        const languages = this.#languages ?? [];
        this.#languages = languages.some((language) => language.descriptor && !language.descriptor.module)
            ? languages
            : [...coreLanguages, ...languages];
    }

    build() {
        this.#requireComplete();
        // The registry decodes with the NAMED languages' schema: m3's, then each named extension's.
        this.#registry.useLanguages(this.#languages.map((language) => language.descriptor));
        return new PureRuntime({
            registry: this.#registry,
            natives: this.#natives,
            languages: this.#languages,
            evalJs: this.#evalJs,
            runtimeModule: this.#runtimeModule,
            sourceText: this.#sourceText,
        });
    }
}
