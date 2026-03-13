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

package org.finos.legend.pure.m3.module;

import meta.pure.metamodel.PackageableElement;
import org.eclipse.collections.api.factory.Lists;
import org.eclipse.collections.api.list.MutableList;
import org.finos.legend.pure.m3.PureModel;

import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * A {@link MetadataAccess} that combines a module with its resolved
 * dependencies.  Used during compilation so that the compiler can
 * see elements and functions from the module being compiled <em>and</em>
 * all of its declared dependencies, but nothing else.
 */
public final class ScopedMetadataAccess implements MetadataAccess
{
    private final Module self;
    private final MutableList<Module> dependencies;

    public ScopedMetadataAccess(Module self, PureModel model)
    {
        this.self = self;
        MutableList<Module> deps = Lists.mutable.empty();
        for (String depName : self.getDependencies())
        {
            Module dep = model.getModule(depName);
            if (dep != null)
            {
                deps.add(dep);
            }
        }
        this.dependencies = deps;
    }

    @Override
    public <T extends MetadataAccessExtension> MutableList<T> getMetadataAccessExtension(Class<T> clz)
    {
        return self.getMetadataAccessExtension(clz).withAll(this.dependencies.flatCollect(x -> x.getMetadataAccessExtension(clz)).select(Objects::nonNull));
    }

    @Override
    public PackageableElement getElement(String path)
    {
        PackageableElement found = null;
        int count = 0;
        if (self.hasElement(path))
        {
            found = self.getElement(path);
            count++;
        }
        for (Module dep : dependencies)
        {
            if (dep.hasElement(path))
            {
                if (count == 0)
                {
                    found = dep.getElement(path);
                }
                count++;
            }
        }
        if (count > 1 && !(found instanceof meta.pure.metamodel.Package))
        {
            throw new RuntimeException("Element '" + path + "' is defined in multiple modules");
        }
        return found;
    }

    @Override
    public boolean hasElement(String path)
    {
        if (self.hasElement(path))
        {
            return true;
        }
        for (Module dep : dependencies)
        {
            if (dep.hasElement(path))
            {
                return true;
            }
        }
        return false;
    }

    @Override
    public Set<String> elementPaths()
    {
        LinkedHashSet<String> all = new LinkedHashSet<>(self.elementPaths());
        for (Module dep : dependencies)
        {
            all.addAll(dep.elementPaths());
        }
        return all;
    }

}
