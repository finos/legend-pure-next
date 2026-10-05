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
import org.eclipse.collections.api.factory.Sets;
import org.eclipse.collections.api.list.MutableList;
import org.eclipse.collections.api.set.MutableSet;
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
    private static volatile ListIterable<Language> languages = Lists.immutable.empty();

    private final ModuleRegistry registry;
    private final PdbRuntime runtime;

    private PureRuntime(ModuleRegistry registry, Path schema, ListIterable<Language> languages, Path root,
                        ListIterable<Path> hostArchives) throws IOException
    {
        installLanguages(languages);
        this.registry = registry;
        registerDeclaredModules(registry, languages, root);
        for (Path archive : hostArchives)
        {
            PdbModule module = PdbModule.open(archive);
            // A caller may name a module a language also declares — the extension's own pdb, say. It is
            // already registered then, and registering it twice would be an error rather than a no-op.
            if (registry.module(module.name()) == null)
            {
                registry.register(module);
            }
        }
        registry.validate();
        List<Path> archives = new ArrayList<>();
        for (Module module : registry.modules())
        {
            if (module instanceof PdbModule pdb)
            {
                archives.add(pdb.path());
            }
        }
        this.runtime = schema == null
                ? PdbRuntime.open(PdbTypes::create, archives.toArray(new Path[0]))
                : PdbRuntime.open(schema, PdbTypes::create, archives.toArray(new Path[0]));
        registry.attach(runtime);
        // Translated code reaches the metadata through the static Metadata, so a runtime
        // built here becomes the one it reads. One runtime at a time, as on the CLI.
        Metadata.install(runtime);
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
     * core.pdb, say — keeps its own. Registered AFTER the host's own modules, which is why an embedder
     * should register only non-PDB modules of its own: the archive order the metadata is opened with
     * follows registration order, and core.pdb has to be first.</p>
     */
    private static void registerDeclaredModules(ModuleRegistry registry, ListIterable<Language> languages, Path root)
            throws IOException
    {
        MutableList<String> declared = Lists.mutable.empty();
        languages.forEach((language) -> declared.addAllIterable(language.pdbs()));
        declared.addAllIterable(PlatformModules.PDBS);
        if (declared.notEmpty() && root == null)
        {
            throw new IllegalStateException("the languages declare modules (" + declared.makeString(", ")
                    + "), so PureRuntime needs withModuleRoot(...) to resolve their repo-relative paths");
        }
        MutableSet<String> seen = Sets.mutable.empty();
        for (String pdb : declared)
        {
            if (!seen.add(pdb))
            {
                continue;   // two languages naming the same module is normal
            }
            PdbModule module = PdbModule.open(root.resolve(pdb));
            if (registry.module(module.name()) == null)
            {
                registry.register(module);
            }
        }
    }

    public static Builder builder()
    {
        return new Builder();
    }

    /**
     * Install the languages this run has, before anything is parsed — what
     * {@link Builder#withLanguages} does, exposed for a host that drives the platform without building a
     * runtime (the CLI opens the archives itself).
     *
     * <p>Caches derived from the previous set are dropped here rather than by each holder, because a set
     * installed after the first parse would otherwise be read from a memo that predates it: a section
     * would simply stop parsing, with nothing to say why.</p>
     */
    public static void installLanguages(ListIterable<Language> languages)
    {
        PureRuntime.languages = Lists.immutable.withAll(languages);
        Antlr.reloadGrammars();
        PureParsing.reloadSectionParsers();
    }

    /** The installed languages, in registration order. */
    public static ListIterable<Language> languages()
    {
        return languages;
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
    public static MutableList<Object> compilerExtensions()
    {
        MutableList<Object> extensions = Lists.mutable.empty();
        languages.forEach((language) ->
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
        private ListIterable<Path> archives = Lists.immutable.empty();

        public Builder withRegistry(ModuleRegistry registry)
        {
            this.registry = registry;
            return this;
        }

        /**
         * Decode with the schema at {@code schema} instead of the one the platform ships as a
         * classpath resource. Only for a caller that deliberately wants a different schema —
         * the archives and the schema that describes them have to match.
         */
        public Builder withSchema(Path schema)
        {
            this.schema = schema;
            return this;
        }

        /**
         * The languages this runtime can parse and compile — the Pure language, and every extension the
         * host chose: {@code PureLanguage.all().newWithAll(DiagramLanguage.all())}.
         *
         * <p>Named by the host rather than discovered, so the ORDER is explicit ({@code ###Pure} before
         * anything else) and a test can build a runtime with one language rather than two.</p>
         */
        public Builder withLanguages(ListIterable<Language> languages)
        {
            this.languages = languages;
            return this;
        }

        /**
         * Where the repo-relative module paths a language declares are resolved from. Required as soon
         * as a language declares any.
         */
        public Builder withModuleRoot(Path root)
        {
            this.root = root;
            return this;
        }

        /**
         * Archives of the host's own, beyond what the languages declare — test content, an extension's
         * module, anything a caller names itself. Registered AFTER the declared ones, because the order
         * modules are registered in is the order the metadata opens them and core.pdb has to be first.
         */
        public Builder withArchives(ListIterable<Path> archives)
        {
            this.archives = archives;
            return this;
        }

        public PureRuntime build()
        {
            if (registry == null)
            {
                throw new IllegalStateException("PureRuntime needs withRegistry(...)");
            }
            try
            {
                return new PureRuntime(registry, schema, languages, root, archives);
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
            "meta::pure::compiler::compile_PureFile_MANY__CompilerExtension_MANY__Boolean_1__CompilationResult_1_";
    private static final String NEW_CONTEXT = "meta::pure::compiler::newCompilerContext_Boolean_1__CompilerContext_1_";
}
