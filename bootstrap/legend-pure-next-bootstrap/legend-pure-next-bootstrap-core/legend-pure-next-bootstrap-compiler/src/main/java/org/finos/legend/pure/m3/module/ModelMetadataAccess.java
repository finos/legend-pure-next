// Copyright 2026 Goldman Sachs
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
import meta.pure.metamodel.type.Type;
import org.eclipse.collections.api.factory.Lists;
import org.eclipse.collections.api.list.MutableList;
import org.finos.legend.pure.m3.PureModel;

import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A {@link MetadataAccess} that walks every module in a {@link PureModel}
 * (registry-wide visibility). Use this for runtime execution where all loaded
 * PDBs must be in scope — particularly for {@code Package.children} pointer
 * resolution, which fails silently when a child element lives in a sibling
 * PDB outside the active {@link ScopedMetadataAccess} chain.
 *
 * <p>Multiple modules may legitimately define the same Package path (the lean
 * PDB ships the Package element, companion {@code -tests} PDBs reference it
 * by path); only the first match is returned. Non-Package duplicates raise —
 * that would indicate a serialisation bug.
 */
public final class ModelMetadataAccess implements MetadataAccess
{
    private final MutableList<Module> modules;
    private final Map<Type, MutableList<Type>> linearizationCache = new IdentityHashMap<>();
    private final Map<Type, java.util.List<String>> equalityKeyPropertiesCache = new IdentityHashMap<>();
    private final Map<PackageableElement, String> elementPathCache = new IdentityHashMap<>();
    private final ConcurrentHashMap<String, PackageableElement> elementCache = new ConcurrentHashMap<>();

    public ModelMetadataAccess(PureModel model)
    {
        this.modules = model.modules();
    }

    @Override
    public <T extends MetadataAccessExtension> MutableList<T> getMetadataAccessExtension(Class<T> clz)
    {
        return Lists.mutable.<T>empty()
                .withAll(this.modules.flatCollect(m -> m.getMetadataAccessExtension(clz)).select(Objects::nonNull));
    }

    @Override
    public PackageableElement getElement(String path)
    {
        PackageableElement cached = elementCache.get(path);
        if (cached != null)
        {
            return cached;
        }
        PackageableElement found = null;
        int count = 0;
        for (Module m : modules)
        {
            if (m.hasElement(path))
            {
                if (count == 0)
                {
                    found = m.getElement(path);
                }
                count++;
            }
        }
        if (count > 1 && !(found instanceof meta.pure.metamodel.Package))
        {
            throw new RuntimeException("Element '" + path + "' is defined in multiple modules");
        }
        if (found != null)
        {
            elementCache.put(path, found);
        }
        return found;
    }

    @Override
    public boolean hasElement(String path)
    {
        if (elementCache.containsKey(path))
        {
            return true;
        }
        for (Module m : modules)
        {
            if (m.hasElement(path))
            {
                return true;
            }
        }
        return false;
    }

    @Override
    public Set<String> elementPaths()
    {
        LinkedHashSet<String> all = new LinkedHashSet<>();
        for (Module m : modules)
        {
            all.addAll(m.elementPaths());
        }
        return all;
    }

    @Override
    public Map<Type, MutableList<Type>> linearizationCache()
    {
        return linearizationCache;
    }

    @Override
    public Map<Type, java.util.List<String>> equalityKeyPropertiesCache()
    {
        return equalityKeyPropertiesCache;
    }

    @Override
    public Map<PackageableElement, String> elementPathCache()
    {
        return elementPathCache;
    }
}
