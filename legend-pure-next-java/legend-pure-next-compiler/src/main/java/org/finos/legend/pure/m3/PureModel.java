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
import org.eclipse.collections.api.factory.Lists;
import org.eclipse.collections.api.list.MutableList;
import org.finos.legend.pure.m3.module.localModule.LocalModule;
import org.finos.legend.pure.m3.module.localModule.topLevel.TopLevelCompiler;
import org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.resolution.valueSpecification.functionExpressionResolver.LazyPackageableFunction;
import org.finos.legend.pure.m3.module.CompilationResult;
import org.finos.legend.pure.m3.module.Module;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;


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
        this.root = new PackageImpl()._name("Root");
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
        LazyPackageableFunction.setFailOnResolve(true);
        try
        {
            // Compile each module in dependency order
            for (Module module : modules)
            {
                CompilationResult result = module.compile();
                if (!result.errors().isEmpty())
                {
                    return result;
                }
            }

            return new CompilationResult(List.of());
        }
        finally
        {
            LazyPackageableFunction.setFailOnResolve(false);
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
}
