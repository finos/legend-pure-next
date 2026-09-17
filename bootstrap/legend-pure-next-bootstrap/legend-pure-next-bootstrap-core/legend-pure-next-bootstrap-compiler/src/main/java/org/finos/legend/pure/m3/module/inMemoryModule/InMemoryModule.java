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

package org.finos.legend.pure.m3.module.inMemoryModule;

import org.finos.legend.pure.m3.LanguageExtension;
import org.finos.legend.pure.m3.module.ModuleRegistry;
import org.finos.legend.pure.m3.module.sourceModule.PureContent;
import meta.pure.metamodel.PackageableElement;
import org.eclipse.collections.api.factory.Lists;
import org.eclipse.collections.api.list.MutableList;
import org.finos.legend.pure.m3.module.MetadataAccess;
import org.finos.legend.pure.m3.module.MetadataAccessExtension;
import org.finos.legend.pure.m3.module.Module;
import org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._PackageableElement;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * A module that lives in memory: its code (sources, modifiable at run time) and the elements
 * compiled from it — {@code PureRuntime.setSource(...)} then {@code PureRuntime.compile()} — or
 * elements added directly. Same name and members as the Truffle and JavaScript
 * {@code InMemoryModule}. It has no package pattern: it accepts elements in any package.
 */
public final class InMemoryModule implements Module
{
    private final String name;
    private final List<String> dependencies;
    private final Map<String, PackageableElement> elements = new LinkedHashMap<>();
    private final Map<String, String> sources = new LinkedHashMap<>();
    private boolean hasCode;
    private MetadataAccess resolver;
    private MutableList<MetadataAccessExtension> metadataAccessExtensions = Lists.mutable.empty();

    public InMemoryModule(String name, List<String> dependencies)
    {
        this(name, dependencies, List.of());
    }

    public InMemoryModule(String name, List<String> dependencies, Iterable<? extends PackageableElement> elements)
    {
        this.name = name;
        this.dependencies = List.copyOf(dependencies);
        addElements(elements);
    }

    @Override
    public String name()
    {
        return this.name;
    }

    @Override
    public List<String> dependencies()
    {
        return this.dependencies;
    }

    @Override
    public String packagePattern()
    {
        return null;
    }

    /** Add or replace the source {@code sourceId}; takes effect at the next compile. */
    public void setSource(String sourceId, String content)
    {
        this.sources.put(sourceId, content);
        this.hasCode = true;
    }

    /** Remove the source {@code sourceId}; takes effect at the next compile. */
    public void removeSource(String sourceId)
    {
        this.sources.remove(sourceId);
    }

    public List<PureContent> sources()
    {
        return this.sources.entrySet().stream().map(e -> new PureContent(e.getValue(), e.getKey())).toList();
    }

    /** Whether this module's elements come from its sources (so compiling replaces them). */
    public boolean hasCode()
    {
        return this.hasCode;
    }

    public void clearElements()
    {
        this.elements.clear();
    }

    public void addElement(PackageableElement element)
    {
        this.elements.put(_PackageableElement.path(element), element);
    }

    public void addElements(Iterable<? extends PackageableElement> elements)
    {
        elements.forEach(this::addElement);
    }

    public void removeElement(String path)
    {
        this.elements.remove(path);
    }

    public List<PackageableElement> elements()
    {
        return List.copyOf(this.elements.values());
    }

    @Override
    public PackageableElement getElement(String path)
    {
        return this.elements.get(path);
    }

    @Override
    public boolean hasElement(String path)
    {
        return this.elements.containsKey(path);
    }

    @Override
    public Set<String> elementPaths()
    {
        return Collections.unmodifiableSet(this.elements.keySet());
    }

    /** The registry this module was last attached to (null before). */
    public MetadataAccess resolver()
    {
        return this.resolver;
    }

    @Override
    public void attach(ModuleRegistry registry, List<LanguageExtension> extensions)
    {
        this.resolver = registry;
        this.metadataAccessExtensions = Lists.mutable.withAll(extensions).collect(e -> e.buildMetadataExtensionForModule(this)).select(Objects::nonNull);
    }

    @Override
    public <T extends MetadataAccessExtension> MutableList<T> getMetadataAccessExtension(Class<T> clz)
    {
        return this.metadataAccessExtensions.selectInstancesOf(clz);
    }
}
