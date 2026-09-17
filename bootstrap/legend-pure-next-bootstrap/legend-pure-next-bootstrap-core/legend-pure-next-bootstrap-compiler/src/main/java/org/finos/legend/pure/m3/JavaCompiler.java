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

package org.finos.legend.pure.m3;

import meta.pure.metamodel.Package;
import meta.pure.metamodel.PackageImpl;
import meta.pure.metamodel.PackageableElement;
import org.eclipse.collections.api.factory.Lists;
import org.eclipse.collections.api.list.MutableList;
import org.eclipse.collections.api.map.MutableMap;
import org.eclipse.collections.impl.factory.Maps;
import org.finos.legend.pure.m3.module.CompilationResult;
import org.finos.legend.pure.m3.module.CompilationStatistics;
import org.finos.legend.pure.m3.module.ElementStatistics;
import org.finos.legend.pure.m3.module.MetadataAccess;
import org.finos.legend.pure.m3.module.Module;
import org.finos.legend.pure.m3.module.ModuleRegistry;
import org.finos.legend.pure.m3.module.sourceModule.SourceModule;
import org.finos.legend.pure.m3.module.sourceModule.topLevel.TopLevelCompiler;
import org.finos.legend.pure.m3.pureLanguage.metadata.lazyFunctions.FunctionIndexEntry;
import org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._GenericType;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;


/**
 * Bootstrap's Java implementation of the Pure compiler.
 *
 * <p>
 * Compiles the modules of a {@link ModuleRegistry} (attached to it with the language
 * extensions when the compiler is built) in dependency order, or one module against the
 * registry. Queries go to the registry ({@link #registry()}); compilation state for each
 * {@link SourceModule} is managed by its own {@link TopLevelCompiler}.
 * </p>
 */
public class JavaCompiler
{
    private final ModuleRegistry registry;
    private final MutableList<LanguageExtension> extensions;

    /** Root package for the elements of the current compile (fresh for each compile). */
    private Package root = new PackageImpl()._name("::");

    private JavaCompiler(ModuleRegistry registry, MutableList<LanguageExtension> extensions)
    {
        this.registry = registry;
        this.extensions = extensions;
    }

    // -----------------------------------------------------------------------
    // Builder
    // -----------------------------------------------------------------------

    /**
     * Start building a compiler over the given modules.
     */
    public static Builder withModules(MutableList<Module> modules)
    {
        return new Builder(new ModuleRegistry(modules));
    }

    /**
     * Start building a compiler over an already-populated registry.
     */
    public static Builder withRegistry(ModuleRegistry registry)
    {
        return new Builder(registry);
    }

    /**
     * Builder for {@link JavaCompiler}. {@link #build()} attaches the registry's modules
     * (sorted dependency-first) with the extensions.
     */
    public static class Builder
    {
        private final ModuleRegistry registry;
        private MutableList<LanguageExtension> extensions = Lists.mutable.empty();

        private Builder(ModuleRegistry registry)
        {
            this.registry = registry;
        }

        public Builder withExtensions(MutableList<LanguageExtension> extensions)
        {
            this.extensions = extensions;
            return this;
        }

        public JavaCompiler build()
        {
            JavaCompiler compiler = new JavaCompiler(registry, extensions);
            registry.attach(extensions);
            return compiler;
        }
    }

    /**
     * Compile the configured modules.
     *
     * <p>
     * The modules list should include any {@code PdbModule}s for
     * pre-compiled dependencies and {@code SourceModule}s for source
     * files to compile.  A {@code BootstrapModule} is only required
     * when no {@code PdbModule} contains bootstrap M3 types.
     * </p>
     *
     * @return the compilation result
     */
    public CompilationResult compile()
    {
        FunctionIndexEntry.setFailOnResolve(true);
        try
        {
            long totalStart = System.nanoTime();
            this.root = new PackageImpl()._name("::");
            List<CompilationStatistics> moduleStatistics = new ArrayList<>();
            java.util.LinkedHashMap<String, java.util.Set<String>> aggregatedRefIndex = new java.util.LinkedHashMap<>();
            List<PackageableElement> compiledElements = new ArrayList<>();

            // Compile each module in dependency order
            for (Module module : registry.modules())
            {
                CompilationResult result = module.compile(this);
                if (!result.errors().isEmpty())
                {
                    return result;
                }
                compiledElements.addAll(result.elements());
                moduleStatistics.add(result.statistics());
                // Merge each module's reverse index. Same target can appear
                // in multiple modules' indexes (each module sees its own
                // callers); union the caller sets.
                if (result.referencedBy() != null)
                {
                    result.referencedBy().forEach((target, callers) ->
                            aggregatedRefIndex.computeIfAbsent(target, k -> new java.util.LinkedHashSet<>())
                                    .addAll(callers));
                }
            }

            // Set classifierGenericType on Root package (and any in-memory packages)
            // now that the Package type is available from PDB
            setPackageClassifierGenericType();

            CompilationStatistics aggregated = CompilationStatistics.combine(moduleStatistics)
                    .withTotalDurationNanos(System.nanoTime() - totalStart);
            return new CompilationResult(compiledElements, List.of(), aggregated, aggregatedRefIndex);
        }
        finally
        {
            FunctionIndexEntry.setFailOnResolve(false);
        }
    }


    /**
     * Compile one module against the registry — e.g. a source module that is not itself
     * registered. The module must be attached to the registry first.
     */
    public CompilationResult compile(Module module)
    {
        FunctionIndexEntry.setFailOnResolve(true);
        try
        {
            long start = System.nanoTime();
            this.root = new PackageImpl()._name("::");
            CompilationResult result = module.compile(this);
            if (!result.errors().isEmpty())
            {
                return result;
            }
            setPackageClassifierGenericType();
            CompilationStatistics statistics = CompilationStatistics.combine(List.of(result.statistics()))
                    .withTotalDurationNanos(System.nanoTime() - start);
            return new CompilationResult(result.elements(), List.of(), statistics, result.referencedBy());
        }
        finally
        {
            FunctionIndexEntry.setFailOnResolve(false);
        }
    }

    // -----------------------------------------------------------------------
    // Query API
    // -----------------------------------------------------------------------

    /**
     * @return the root package of the current compile
     */
    public Package root()
    {
        return this.root;
    }

    public MutableList<LanguageExtension> extensions()
    {
        return extensions;
    }

    /**
     * @return the registry holding the compiled modules (registry-wide metadata access)
     */
    public ModuleRegistry registry()
    {
        return this.registry;
    }

    /**
     * After compilation, set classifierGenericType on the Root package
     * and any intermediate PackageImpl instances that were created in-memory.
     */
    private void setPackageClassifierGenericType()
    {
        // Look up the Package type and a MetadataAccess from any module
        meta.pure.metamodel.type.Type packageType = null;
        MetadataAccess model = null;
        for (Module m : registry.modules())
        {
            PackageableElement pe = m.getElement("meta::pure::metamodel::Package");
            if (pe instanceof meta.pure.metamodel.type.Type t)
            {
                packageType = t;
                model = m;
                break;
            }
        }
        if (packageType != null)
        {
            setClassifierOnPackage(root, packageType, model);
        }
    }

    private static void setClassifierOnPackage(Package pkg, meta.pure.metamodel.type.Type packageType, MetadataAccess model)
    {
        if (pkg instanceof PackageImpl pi && pi._classifierGenericType() == null)
        {
            // Anchor at canonical GenericType_<fullPath> (UDPGT) so the chain
            // matches Pure's `^Package(...)` (platform fix in MetaNatives.new
            // substitutes canonical). Full path encoded with `::` -> `_`.
            Object canonical = model.getElement("meta::pure::metamodel::type::generics::optimization::GenericType_meta_pure_metamodel_Package");
            if (canonical instanceof meta.pure.metamodel.type.generics.GenericTypeValue canonicalGT)
            {
                pi._classifierGenericType(canonicalGT);
            }
            else
            {
                pi._classifierGenericType(_GenericType.buildUserDefinedGenericType(packageType, model));
            }
        }
        // Recurse into children
        if (pkg._children() != null)
        {
            for (PackageableElement child : pkg._children())
            {
                if (child instanceof Package childPkg)
                {
                    setClassifierOnPackage(childPkg, packageType, model);
                }
            }
        }
    }
}
