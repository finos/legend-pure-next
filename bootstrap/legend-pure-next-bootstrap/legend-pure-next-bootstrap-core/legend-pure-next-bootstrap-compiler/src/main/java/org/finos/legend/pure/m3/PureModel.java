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
import org.finos.legend.pure.m3.module.localModule.LocalModule;
import org.finos.legend.pure.m3.module.localModule.topLevel.TopLevelCompiler;
import org.finos.legend.pure.m3.pureLanguage.metadata.lazyFunctions.FunctionIndexEntry;
import org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._GenericType;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;


/**
 * The compiled Pure model.
 *
 * <p>
 * Provides a unified view across all modules: element resolution,
 * function lookup, and the package tree.  Compilation state for
 * each {@link LocalModule} is managed by its own {@link TopLevelCompiler}.
 * </p>
 */
public class PureModel
{
    private final MutableList<Module> modules;
    private final MutableList<LanguageExtension> extensions;

    /** Root package for locally compiled elements. */
    private final Package root;

    private PureModel(MutableList<Module> modules, MutableList<LanguageExtension> extensions)
    {
        this.modules = modules;
        this.extensions = extensions;
        this.root = new PackageImpl()._name("::");
    }

    public MutableList<LanguageExtension> extensions()
    {
        return extensions;
    }

    // -----------------------------------------------------------------------
    // Builder
    // -----------------------------------------------------------------------

    /**
     * Start building a PureModel with the given modules.
     */
    public static PureModelBuilder withModules(MutableList<Module> modules)
    {
        return new PureModelBuilder(modules);
    }

    /**
     * Builder for {@link PureModel}.
     */
    public static class PureModelBuilder
    {
        private final MutableList<Module> modules;
        private MutableList<LanguageExtension> extensions = Lists.mutable.empty();

        private PureModelBuilder(MutableList<Module> modules)
        {
            this.modules = modules;
        }

        public PureModelBuilder withExtensions(MutableList<LanguageExtension> extensions)
        {
            this.extensions = extensions;
            return this;
        }

        public PureModel build()
        {
            PureModel model = new PureModel(modules, extensions);
            model.sortAndInitModules();
            return model;
        }
    }

    /**
     * Get a module by name.
     */
    public Module getModule(String name)
    {
        for (Module m : this.modules)
        {
            if (m.getName().equals(name))
            {
                return m;
            }
        }
        return null;
    }

    /**
     * Sort modules topologically and call setPureModel on each.
     */
    private void sortAndInitModules()
    {
        List<Module> sorted = topologicalSort();
        // Replace with sorted order
        this.modules.clear();
        this.modules.addAll(sorted);
        for (Module module : this.modules)
        {
            module.setPureModel(this);
         }
    }

    /**
     * Topological sort of modules based on their dependency graph
     * (Kahn's algorithm). Dependencies are placed before dependents.
     */
    private List<Module> topologicalSort()
    {
        // Build adjacency: module → dependents, and in-degree count
        Map<Module, List<Module>> dependents = new LinkedHashMap<>();
        Map<Module, Integer> inDegree = new LinkedHashMap<>();
        for (Module m : this.modules)
        {
            dependents.putIfAbsent(m, new ArrayList<>());
            inDegree.putIfAbsent(m, 0);
        }
        for (Module m : this.modules)
        {
            for (String depName : m.getDependencies())
            {
                Module dep = getModule(depName);
                if (dep != null)
                {
                    dependents.get(dep).add(m);
                    inDegree.merge(m, 1, Integer::sum);
                }
            }
        }
        // Kahn's: start with nodes that have no in-module dependencies
        Deque<Module> queue = new ArrayDeque<>();
        for (Module m : this.modules)
        {
            if (inDegree.get(m) == 0)
            {
                queue.add(m);
            }
        }
        List<Module> sorted = new ArrayList<>(this.modules.size());
        while (!queue.isEmpty())
        {
            Module m = queue.poll();
            sorted.add(m);
            for (Module dependent : dependents.get(m))
            {
                int remaining = inDegree.merge(dependent, -1, Integer::sum);
                if (remaining == 0)
                {
                    queue.add(dependent);
                }
            }
        }
        if (sorted.size() != this.modules.size())
        {
            throw new IllegalStateException("Cyclic module dependency detected");
        }
        return sorted;
    }

    /**
     * Compile the configured modules.
     *
     * <p>
     * The modules list should include any {@code PdbModule}s for
     * pre-compiled dependencies and {@code LocalModule}s for source
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
            long parsingTotal = 0;
            long firstPassTotal = 0;
            long secondPassTotal = 0;
            long thirdPassTotal = 0;
            int elementTotal = 0;
            int sourceFileTotal = 0;
            long memoryDeltaTotal = 0;
            int rollbackTotal = 0;
            int candidateTotal = 0;
            MutableMap<String, ElementStatistics> allElementStats = Maps.mutable.empty();
            MutableMap<String, Integer> allRollbackSites = Maps.mutable.empty();
            java.util.LinkedHashMap<String, java.util.Set<String>> aggregatedRefIndex = new java.util.LinkedHashMap<>();

            // Compile each module in dependency order
            for (Module module : modules)
            {
                CompilationResult result = module.compile();
                if (!result.errors().isEmpty())
                {
                    return result;
                }
                CompilationStatistics s = result.statistics();
                if (s != null)
                {
                    parsingTotal += s.parsingDurationNanos();
                    firstPassTotal += s.firstPassDurationNanos();
                    secondPassTotal += s.secondPassDurationNanos();
                    thirdPassTotal += s.thirdPassDurationNanos();
                    elementTotal += s.elementCount();
                    sourceFileTotal += s.sourceFileCount();
                    memoryDeltaTotal += s.memoryDeltaBytes();
                    rollbackTotal += s.inferenceRollbackCount();
                    candidateTotal += s.candidateEvaluationCount();
                    allElementStats.putAll(s.elementStatistics());
                    s.rollbackSites().forEachKeyValue((k, v) -> allRollbackSites.merge(k, v, Integer::sum));
                }
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

            long totalDuration = System.nanoTime() - totalStart;
            CompilationStatistics aggregated = new CompilationStatistics(
                    totalDuration, parsingTotal, firstPassTotal, secondPassTotal, thirdPassTotal,
                    elementTotal, sourceFileTotal, memoryDeltaTotal, rollbackTotal, candidateTotal,
                    allElementStats, allRollbackSites);
            return new CompilationResult(List.of(), aggregated, aggregatedRefIndex);
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
     * @return the root element of the model
     */
    public Package _root()
    {
        return this.root;
    }

    /**
     * @return all modules in this model
     */
    public MutableList<Module> modules()
    {
        return this.modules;
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
        for (Module m : modules)
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
