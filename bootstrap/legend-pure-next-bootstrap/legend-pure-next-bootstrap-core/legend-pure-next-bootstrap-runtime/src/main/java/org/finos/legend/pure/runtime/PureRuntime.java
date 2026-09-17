// Copyright 2024 Goldman Sachs
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

package org.finos.legend.pure.runtime;

import org.finos.legend.pure.m3.pureLanguage.PureLanguageExtension;
import org.finos.legend.pure.m3.module.CompilationStatistics;
import java.util.Set;
import java.util.Map;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import org.finos.legend.pure.m3.module.Module;
import meta.pure.metamodel.PackageableElement;
import org.finos.legend.pure.m3.module.inMemoryModule.InMemoryModule;
import org.finos.legend.pure.m3.module.ModuleRegistry;
import meta.pure.metamodel.function.FunctionWithParameters;
import org.finos.legend.pure.execution.Execution;
import org.finos.legend.pure.execution.ExecutionContext;
import org.finos.legend.pure.execution.InterpretedExecution;
import org.finos.legend.pure.m3.LanguageExtension;
import org.finos.legend.pure.m3.compilation.Compilation;
import org.finos.legend.pure.m3.compilation.CompilationContext;
import org.finos.legend.pure.m3.module.CompilationResult;
import org.finos.legend.pure.m3.module.MetadataAccess;
import org.finos.legend.pure.m3.module.sourceModule.SourceModule;
import org.finos.legend.pure.next.parser.GrammarExtension;
import org.finos.legend.pure.runtime.compilation.PureCompilation;

import java.util.ArrayList;
import java.util.List;

/**
 * Brings everything together: a registry, the language extensions (parser + compiler
 * per section) and grammars, one {@link Compilation} strategy and one {@link Execution}
 * strategy. Compiling and executing both go through the runtime; natives are configured
 * on the execution strategy.
 *
 * <p>Usage:
 * <pre>{@code
 * PureRuntime runtime = PureRuntime.builder()
 *         .withRegistry(registry)
 *         .withLanguageExtensions(languageExtensions)
 *         .withCompilation(JavaCompilation.builder())          // default: PureCompilation
 *         .withExecution(InterpretedExecution.builder().withNativeExtensions(nativeExtensions))
 *         .build();
 * runtime.setSource("sample", "sample.pure", source);             // an InMemoryModule registered as "sample"
 * CompilationResult compiled = runtime.compile();
 * Object result = runtime.execute(myFunction, "World");
 * }</pre>
 */
public class PureRuntime
{
    private final MetadataAccess registry;
    private final Execution execution;
    private final Compilation compilation;
    /** The extensions the registry's modules are attached with: PureLanguageExtension + the language extensions. */
    private final List<LanguageExtension> moduleExtensions;

    private PureRuntime(MetadataAccess registry,
                        List<LanguageExtension> languageExtensions,
                        List<GrammarExtension> grammarExtensions,
                        Compilation.Builder compilation,
                        Execution.Builder execution)
    {
        this.registry = registry;
        this.moduleExtensions = withPureLanguage(languageExtensions);
        if (registry instanceof ModuleRegistry modules)
        {
            modules.attach(this.moduleExtensions);
        }
        // The compilation executes Pure functions through this runtime (resolved at call
        // time); the execution's natives (compileSource) compile through the compilation.
        this.compilation = compilation.build(new CompilationContext(registry, languageExtensions, this::execute));
        this.execution = execution.build(new ExecutionContext(registry, languageExtensions, grammarExtensions, () -> this.compilation));
    }

    public static Builder builder()
    {
        return new Builder();
    }

    public static class Builder
    {
        private MetadataAccess registry;
        private final List<LanguageExtension> languageExtensions = new ArrayList<>();
        private final List<GrammarExtension> grammarExtensions = new ArrayList<>();
        private Compilation.Builder compilation;
        private Execution.Builder execution;

        public Builder withRegistry(MetadataAccess registry)
        {
            this.registry = registry;
            return this;
        }

        /** Language extensions: the parser and compiler for each extra {@code ###Section}. */
        public Builder withLanguageExtensions(Iterable<? extends LanguageExtension> extensions)
        {
            if (extensions != null)
            {
                extensions.forEach(this.languageExtensions::add);
            }
            return this;
        }

        /**
         * Register grammar extensions consulted by the {@code parseAntlr}
         * native. Each registered extension answers to one
         * {@link GrammarExtension#grammarName()}; duplicates throw at build time.
         * M3 and Top are always available.
         */
        public Builder withGrammarExtensions(Iterable<? extends GrammarExtension> extensions)
        {
            if (extensions != null)
            {
                extensions.forEach(this.grammarExtensions::add);
            }
            return this;
        }

        /** The compilation strategy; defaults to {@link PureCompilation} (compiler.pdb must be loaded to compile). */
        public Builder withCompilation(Compilation.Builder compilation)
        {
            this.compilation = compilation;
            return this;
        }

        /** The execution strategy; defaults to {@link InterpretedExecution} with the built-in natives. */
        public Builder withExecution(Execution.Builder execution)
        {
            this.execution = execution;
            return this;
        }

        public PureRuntime build()
        {
            return new PureRuntime(this.registry, this.languageExtensions, this.grammarExtensions,
                    this.compilation != null ? this.compilation : PureCompilation.builder(),
                    this.execution != null ? this.execution : InterpretedExecution.builder());
        }
    }

    public MetadataAccess registry()
    {
        return this.registry;
    }

    public Compilation compilation()
    {
        return this.compilation;
    }

    public Execution execution()
    {
        return this.execution;
    }

    /** Compile a source module against the registry with this runtime's compilation strategy. */
    public CompilationResult compile(SourceModule module)
    {
        return this.compilation.compile(module);
    }

    /** Add or replace source {@code sourceId} in the registered in-memory module {@code moduleName}. */
    public void setSource(String moduleName, String sourceId, String content)
    {
        inMemoryModule(moduleName).setSource(sourceId, content);
    }

    /** Remove source {@code sourceId} from the registered in-memory module {@code moduleName}. */
    public void removeSource(String moduleName, String sourceId)
    {
        inMemoryModule(moduleName).removeSource(sourceId);
    }

    /**
     * Compile every module whose code lives in the runtime, with this runtime's compilation
     * strategy: first the registered {@link SourceModule}s, in place and in one pass; then, in
     * dependency order, the in-memory modules with code. Each module's elements are replaced by what its sources
     * compile to, so later modules compile against the fresh elements. Stops at the first module
     * that fails; that module keeps its previous elements.
     */
    public CompilationResult compile()
    {
        ModuleRegistry modules = moduleRegistry();
        modules.sortByDependencies();
        List<PackageableElement> compiled = new ArrayList<>();
        Map<String, Set<String>> referencedBy = new LinkedHashMap<>();
        List<CompilationStatistics> statistics = new ArrayList<>();
        if (modules.modules().stream().anyMatch(SourceModule.class::isInstance))
        {
            CompilationResult result = this.compilation.compileSourceModules();
            if (!result.errors().isEmpty())
            {
                return result;
            }
            modules.invalidate();
            compiled.addAll(result.elements());
            statistics.add(result.statistics());
            if (result.referencedBy() != null)
            {
                result.referencedBy().forEach((path, callers) -> referencedBy.computeIfAbsent(path, k -> new LinkedHashSet<>()).addAll(callers));
            }
        }
        for (Module module : List.copyOf(modules.modules()))
        {
            if (!(module instanceof InMemoryModule target) || !target.hasCode())
            {
                continue;
            }
            List<PackageableElement> previous = target.elements();
            target.clearElements();
            modules.invalidate();
            CompilationResult result = this.compilation.compile(
                    new SourceModule(target.name(), target.packagePattern(), target.dependencies(), target.sources()));
            if (!result.errors().isEmpty())
            {
                target.addElements(previous);
                modules.invalidate();
                return new CompilationResult(compiled, result.errors(), result.statistics(), result.referencedBy());
            }
            target.addElements(result.elements());
            target.attach(modules, this.moduleExtensions);
            modules.invalidate();
            compiled.addAll(result.elements());
            statistics.add(result.statistics());
            if (result.referencedBy() != null)
            {
                result.referencedBy().forEach((path, callers) -> referencedBy.computeIfAbsent(path, k -> new LinkedHashSet<>()).addAll(callers));
            }
        }
        return new CompilationResult(compiled, List.of(), CompilationStatistics.combine(statistics), referencedBy);
    }

    private static List<LanguageExtension> withPureLanguage(List<LanguageExtension> languageExtensions)
    {
        List<LanguageExtension> extensions = new ArrayList<>();
        if (languageExtensions.stream().noneMatch(PureLanguageExtension.class::isInstance))
        {
            extensions.add(new PureLanguageExtension());
        }
        extensions.addAll(languageExtensions);
        return List.copyOf(extensions);
    }

    private ModuleRegistry moduleRegistry()
    {
        if (!(this.registry instanceof ModuleRegistry modules))
        {
            throw new IllegalStateException("Modifying and compiling modules needs a ModuleRegistry as the runtime's registry");
        }
        return modules;
    }

    private InMemoryModule inMemoryModule(String moduleName)
    {
        if (!(moduleRegistry().module(moduleName) instanceof InMemoryModule module))
        {
            throw new IllegalArgumentException("No in-memory module '" + moduleName + "' is registered");
        }
        return module;
    }

    /**
     * Execute a compiled function (user-defined, native, or any other
     * {@link FunctionWithParameters}) with the given arguments, in parameter order.
     */
    public Object execute(FunctionWithParameters function, Object... args)
    {
        return this.execution.execute(function, args);
    }

    public void close()
    {
        this.execution.close();
    }
}
