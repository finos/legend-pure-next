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

package org.finos.legend.pure.m3.module.bootstrapModule;

import meta.pure.metamodel.Package;
import meta.pure.metamodel.PackageableElement;
import meta.pure.metamodel.PackageImpl;
import org.eclipse.collections.api.factory.Lists;
import org.eclipse.collections.api.factory.Maps;
import org.eclipse.collections.api.list.MutableList;
import org.eclipse.collections.api.map.MutableMap;
import org.finos.legend.pure.m3.module.MetadataAccessExtension;
import org.finos.legend.pure.m3.module.Module;
import org.finos.legend.pure.m3.PureModel;

import java.util.List;
import java.util.Set;

/**
 * A module that provides the M3 bootstrap types loaded from {@code m3.ttl}.
 *
 * <p>This module wraps the {@link M3BootstrapReader} and exposes the
 * bootstrapped elements (classes, primitive types, enumerations, profiles,
 * multiplicities) through the {@link Module} interface.</p>
 *
 * <p>The function index is empty since the bootstrap graph contains
 * no user-defined or native functions.</p>
 */
public class BootstrapModule implements Module
{
    private final MutableMap<String, PackageableElement> index;
    private final Package root;

    /**
     * Create a bootstrap module, loading all M3 types from {@code m3.ttl}.
     */
    public BootstrapModule()
    {
        this.root = new PackageImpl()._name("::");
        this.index = Maps.mutable.empty();
        M3BootstrapReader.bootstrap(this.root, this.index);
    }

    @Override
    public void setPureModel(PureModel model)
    {
    }

    @Override
    public <T extends MetadataAccessExtension> MutableList<T> getMetadataAccessExtension(Class<T> clz)
    {
        return Lists.mutable.empty();
    }

    @Override
    public String getName()
    {
        return "m3";
    }

    @Override
    public List<String> getDependencies()
    {
        return List.of();
    }

    @Override
    public String getPackagePattern()
    {
        return "meta::*";
    }

    @Override
    public PackageableElement getElement(String path)
    {
        return index.get(path);
    }

    @Override
    public boolean hasElement(String path)
    {
        return index.containsKey(path);
    }

    @Override
    public Set<String> elementPaths()
    {
        return index.keySet();
    }

    /**
     * @return the root package of the bootstrap graph
     */
    public Package root()
    {
        return root;
    }
}
