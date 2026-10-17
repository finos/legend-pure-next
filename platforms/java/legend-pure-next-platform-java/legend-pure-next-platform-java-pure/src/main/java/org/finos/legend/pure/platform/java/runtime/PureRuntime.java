// Copyright 2026 Goldman Sachs
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

package org.finos.legend.pure.platform.java.runtime;

import org.finos.legend.pure.m3.PdbTypes;
import org.finos.legend.pure.platform.java.compiler.module.CompilationResult;
import org.finos.legend.pure.platform.java.compiler.module.Module;
import org.finos.legend.pure.platform.java.compiler.module.ModuleRegistry;
import org.finos.legend.pure.platform.java.compiler.module.inMemoryModule.InMemoryModule;
import org.finos.legend.pure.platform.java.compiler.module.pdbModule.LazyObject;
import org.eclipse.collections.api.factory.Lists;
import org.eclipse.collections.impl.block.factory.Functions;
import org.eclipse.collections.api.list.MutableList;
import org.eclipse.collections.api.list.ListIterable;
import org.finos.legend.pure.platform.java.compiler.module.pdbModule.Metadata;
import org.finos.legend.pure.platform.java.grammar.Antlr;
import org.finos.legend.pure.platform.java.grammar.Language;
import org.finos.legend.pure.platform.java.grammar.PureParsing;
import org.finos.legend.pure.platform.java.runtime.PlatformModules;
import org.finos.legend.pure.platform.java.compiler.module.pdbModule.PdbModule;
import org.finos.legend.pure.platform.java.compiler.module.pdbModule.PdbRuntime;
import org.finos.legend.pure.platform.java.execution.Execute;
import org.finos.legend.pure.platform.java.grammar.PureParsing;
import org.finos.legend.pure.platform.java.runtime.PlatformModules;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The host API on the Java platform: register modules, put Pure source into an in-memory
 * one, compile, execute what it defines. The same shape as bootstrap's and Truffle's
 * {@code PureRuntime}, so a program that drives one drives all three.
 *
 * <p>Relationship to {@link Execute}: that is the CLI, which loads PDBs and calls one
 * function. This is the programmatic entry point, and it adds the part the CLI has no use
 * for — compiling source at run time and executing the result.</p>
 *
 * <p>How executing compiled source works here, which differs from the other hosts: this
 * platform runs translated Java, and a function the compiler just built has no translated
 * class. {@link #execute} therefore goes through {@code Metadata.invoke}, which translates
 * the function and compiles it with javac in memory. Elements the compile produced are
 * contributed to the decoder by path, so translated code that resolves one of them — a
 * call to a sibling function, a {@code ^Class(...)} of a new class — finds it.</p>
 */
public final class PureRuntime
{
    /**
     * THE LANGUAGES IN PLAY, named by the composition root and read from here by the natives.
     *
     * <p>Global because it has to be: {@code parseAntlr} and {@code parse} are reached FROM translated
     * Pure, which cannot pass them along, so they need somewhere to look. It sits beside
     * {@link Metadata}, which is global for exactly the same reason and installed a few lines below.</p>
     *
     * <p>Empty until something installs: a run that parses without installing fails saying so, which is
     * better than a default that quietly decides which languages a host has.</p>
     */
    private static volatile ListIterable<Language> installedLanguages = Lists.immutable.empty();

    /** The last runtime built — what natives reached from translated Pure use (see {@link #current()}). */
    private static volatile PureRuntime current;

    private final ModuleRegistry registry;
    private final PdbRuntime runtime;
    private final ListIterable<Language> languages;

    private PureRuntime(ModuleRegistry registry, Path schema, ListIterable<Language> languages, Path root) throws IOException
    {
        installLanguages(languages);
        this.registry = registry;
        this.languages = Lists.immutable.withAll(languages);
        MutableList<String> declared = registerDeclaredModules(registry, languages, root);
        registry.validate();
        // The archives in the order the metadata opens them: what the languages declare first — core.pdb
        // leads, whatever the host registered before build() — then the host's own, in registration order
        // (a stable sort keeps it).
        ListIterable<Path> archives = Lists.mutable.withAll(registry.modules())
                .selectInstancesOf(PdbModule.class)
                .toSortedListBy(pdb -> declared.contains(pdb.name()) ? declared.indexOf(pdb.name()) : Integer.MAX_VALUE)
                .collect(PdbModule::path);
        // m3's tables, then each language's generated factory for the tables its schema fragment adds.
        ListIterable<java.util.function.Function<String, LazyObject>> factories =
                Lists.mutable.<java.util.function.Function<String, LazyObject>>with(PdbTypes::create)
                        .withAll(languages.collect(Language::types).select(java.util.Objects::nonNull));
        java.util.function.Function<String, LazyObject> types =
                table -> factories.asLazy().collect(factory -> factory.apply(table)).detect(java.util.Objects::nonNull);
        // The schema is the languages' own: m3's from ###Pure, then each extension's fragment.
        this.runtime = PdbRuntime.open(Language.schemaText(languages, schema), types, archives.toArray(new Path[0]));
        registry.attach(runtime);
        // Translated code reaches the metadata through the static Metadata, so a runtime
        // built here becomes the one it reads. One runtime at a time, as on the CLI.
        Metadata.install(runtime);
        current = this;
    }

    /**
     * The runtime built last — what a native reached from translated Pure (compileSource) works against,
     * since translated code cannot pass it along. One set of languages per JVM at a time: the last
     * {@code build()} wins.
     */
    public static PureRuntime current()
    {
        PureRuntime runtime = current;
        if (runtime == null)
        {
            throw new IllegalStateException("no PureRuntime has been built — PureRuntime.builder()...build() first");
        }
        return runtime;
    }

    /**
     * Register what the languages DECLARE, and what this platform always needs — so a host names its
     * languages and never repeats their module lists.
     *
     * <p>Each language's {@code pdbs} is read from its own manifest; {@link PlatformModules} is the Java
     * metamodel and translator, which every run here needs whichever languages it has. Paths are
     * repo-relative, so {@code root} anchors them.</p>
     *
     * <p>Already-registered modules are skipped, so a host that opened one itself — a different build of
     * core.pdb, say — keeps its own. Returns the declared modules' names in declared order: the archives
     * are opened in that order, before the host's own, so core.pdb leads.</p>
     */
    private static MutableList<String> registerDeclaredModules(ModuleRegistry registry, ListIterable<Language> languages, Path root)
    {
        if (root == null)
        {
            // No root, nothing opened: the host's registry is what this runtime has — its explicit choice,
            // not a default (the same rule as Truffle). A language whose module is absent is inert.
            return Lists.mutable.empty();
        }
        // Two languages naming the same module is normal, hence distinct().
        MutableList<PdbModule> opened = languages.flatCollect(Language::pdbs, Lists.mutable.empty())
                .withAll(PlatformModules.PDBS).distinct()
                .collect(Functions.throwing(pdb -> PdbModule.open(root.resolve(pdb))));
        registry.registerAll(opened.reject(module -> registry.module(module.name()) != null));
        return opened.collect(PdbModule::name);
    }

    public static Builder builder()
    {
        return new Builder();
    }

    /**
     * Install the languages this run has, before anything is parsed. Called by {@code build()} only: a host
     * names its languages with {@link Builder#withLanguages}.
     *
     * <p>Caches derived from the previous set are dropped here rather than by each holder, because a set
     * installed after the first parse would otherwise be read from a memo that predates it: a section
     * would simply stop parsing, with nothing to say why.</p>
     */
    private static void installLanguages(ListIterable<Language> languages)
    {
        PureRuntime.installedLanguages = Lists.immutable.withAll(languages);
        Antlr.reloadGrammars();
        PureParsing.reloadSectionParsers();
    }

    /**
     * The languages of the runtime built last, in registration order — read by the parser natives, which
     * translated Pure reaches without a runtime to ask. Not part of the host contract.
     */
    public static ListIterable<Language> languages()
    {
        return installedLanguages;
    }

    /**
     * The compiler passes every installed language contributes — what makes a language's section become
     * an ELEMENT rather than text the compiler carries past.
     *
     * <p>Each of the three compiler passes ends in a catch-all arm that ignores what it does not
     * recognise; an extension fills that arm. Without this a `###Diagram` section parsed and then
     * silently produced nothing, which is exactly what the extension's own
     * `testWithoutTheExtensionNothingIsClaimed` pins.</p>
     *
     * <p>GUARDED on the element being loaded, like the section parsers: an extension whose Pure module is
     * not registered contributes nothing rather than throwing.</p>
     */
    public MutableList<Object> compilerExtensions()
    {
        MutableList<Object> extensions = Lists.mutable.empty();
        this.languages.forEach((language) ->
        {
            String path = language.compilerExtension();
            if (path == null || Metadata.lenientElement(path) == null)
            {
                return;
            }
            try
            {
                Object value = Execute.call(path);
                if (value instanceof Iterable)
                {
                    ((Iterable<?>) value).forEach(extensions::add);
                }
                else if (value != null)
                {
                    extensions.add(value);
                }
            }
            catch (Exception e)
            {
                throw new RuntimeException("cannot read the compiler extension of language '"
                        + language.name() + "' (" + path + ")", e);
            }
        });
        return extensions;
    }

    public static final class Builder
    {
        private ModuleRegistry registry;
        private Path schema;
        private ListIterable<Language> languages = Lists.immutable.empty();
        private Path root;

        public Builder withRegistry(ModuleRegistry registry)
        {
            this.registry = registry;
            return this;
        }

        /**
         * Decode with the schema at {@code schema} instead of the always-active languages'
         * own. Only for a caller that deliberately wants a different schema —
         * the archives and the schema that describes them have to match.
         */
        public Builder withSchema(Path schema)
        {
            this.schema = schema;
            return this;
        }

        /**
         * The extensions this runtime parses and compiles beyond {@code ###Pure} — {@code DiagramLanguage.all()},
         * say. {@code ###Pure} is never optional, so {@code build()} puts {@code PureLanguage.all()} first unless
         * the list already names it.
         */
        public Builder withLanguages(ListIterable<Language> languages)
        {
            this.languages = languages;
            return this;
        }

        /**
         * Where the repo-relative module paths the languages declare (and this platform's own) are opened
         * from. Without one, {@code build()} opens nothing: the host's registry is all the runtime has.
         */
        public Builder withModuleRoot(Path root)
        {
            this.root = root;
            return this;
        }

        public PureRuntime build()
        {
            if (registry == null)
            {
                throw new IllegalStateException("PureRuntime needs withRegistry(...)");
            }
            // ###Pure is never optional, so it is always there: the always-active languages first, unless
            // the host already named them — as on Truffle and in JavaScript.
            ListIterable<Language> all = languages.anySatisfy(Language::bundled)
                    ? languages
                    : org.finos.legend.pure.platform.java.grammar.PureLanguage.all().newWithAll(languages);
            try
            {
                return new PureRuntime(registry, schema, all, root);
            }
            catch (IOException e)
            {
                throw new RuntimeException("Cannot open the registered archives: " + e.getMessage(), e);
            }
        }

    }

    /** The registry this runtime resolves through. */
    public ModuleRegistry registry()
    {
        return registry;
    }

    /**
     * Compile, in registration order, every in-memory module that has code: parse its
     * sources, run compiler-pure over them, and contribute the elements so later modules
     * and {@link #execute} see them. Stops at the first module that fails; that module
     * keeps the elements it had.
     */
    public CompilationResult compile()
    {
        List<Object> compiled = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        for (Module module : registry.modules())
        {
            if (module instanceof InMemoryModule inMemory && inMemory.hasCode()
                    && !compileModule(inMemory, compiled, errors))
            {
                break;
            }
        }
        return new CompilationResult(List.copyOf(compiled), List.copyOf(errors));
    }

    private boolean compileModule(InMemoryModule module, List<Object> compiled, List<String> errors)
    {
        List<Object> files = new ArrayList<>();
        try
        {
            for (Map.Entry<String, String> source : module.sources().entrySet())
            {
                files.add(PureParsing.parse(source.getKey(), source.getValue()));
            }
        }
        catch (RuntimeException e)
        {
            errors.add(module.name() + ": " + e.getMessage());
            return false;
        }

        Object result;
        try
        {
            // silent: compiling inside a program should not draw a progress bar.
            result = Execute.call(COMPILE, files, compilerExtensions(), true);
        }
        catch (Exception e)
        {
            errors.add(module.name() + ": " + e.getMessage());
            return false;
        }

        List<Object> reported = values(result, "errors");
        if (!reported.isEmpty())
        {
            reported.forEach(error -> errors.add(String.valueOf(error)));
            return false;
        }

        // The previous compile's elements go first, or one deleted from the source would
        // keep resolving.
        runtime.undefineElements(module.elements().keySet());
        Map<String, Object> defined = new LinkedHashMap<>();
        for (Object element : values(result, "elements"))
        {
            defined.put(Metadata.path(element, "::"), element);
        }
        defined.forEach(runtime::defineElement);
        module.setElements(defined);
        compiled.addAll(defined.values());
        return true;
    }

    /**
     * Execute a compiled function with the given raw Java arguments. Translating and
     * compiling it happens here, on first call, because the platform has no generated
     * class for a function the compiler just built.
     */
    public Object execute(Object function, Object... args)
    {
        return Metadata.invoke(function, List.of(args));
    }

    /** Nothing to release: the archives are read into memory when they are opened. */
    public void close()
    {
    }


    private static List<Object> values(Object result, String property)
    {
        return new ArrayList<>(((LazyObject) result).__values(property));
    }

    // The overload that takes the LANGUAGES' compiler passes. The CompilerContext overload compiles the
    // built-in element kinds only, so an extension's section would parse and then be dropped.
    private static final String COMPILE =
            "platform::pure::compiler::compile_PureFile_MANY__CompilerExtension_MANY__Boolean_1__CompilationResult_1_";
    private static final String NEW_CONTEXT = "platform::pure::compiler::newCompilerContext_Boolean_1__CompilerContext_1_";
}
