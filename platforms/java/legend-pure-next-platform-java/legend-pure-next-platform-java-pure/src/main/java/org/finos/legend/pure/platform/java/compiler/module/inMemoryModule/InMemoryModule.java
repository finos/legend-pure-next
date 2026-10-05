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

package org.finos.legend.pure.platform.java.compiler.module.inMemoryModule;

import org.finos.legend.pure.platform.java.compiler.module.Module;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A module whose code is held in memory rather than read from an archive: sources are
 * put in with {@link #setSource}, and
 * {@link org.finos.legend.pure.platform.java.runtime.PureRuntime#compile} turns them into
 * elements.
 *
 * <p>Its elements are remembered by path so a recompile can withdraw the previous ones
 * before the new ones are contributed — otherwise an element deleted from the source
 * would keep resolving.</p>
 */
public final class InMemoryModule implements Module
{
    private final String name;
    private final List<String> dependencies;
    private final Map<String, String> sources = new LinkedHashMap<>();
    private final Map<String, Object> elements = new LinkedHashMap<>();

    public InMemoryModule(String name, List<String> dependencies)
    {
        this.name = name;
        this.dependencies = List.copyOf(dependencies);
    }

    @Override
    public String name()
    {
        return name;
    }

    @Override
    public List<String> dependencies()
    {
        return dependencies;
    }

    /** Add or replace {@code sourceId}. */
    public void setSource(String sourceId, String content)
    {
        sources.put(sourceId, content);
    }

    /** Remove {@code sourceId}; a following compile no longer sees it. */
    public void removeSource(String sourceId)
    {
        sources.remove(sourceId);
    }

    /** The sources, in the order they were first added. */
    public Map<String, String> sources()
    {
        return Map.copyOf(sources);
    }

    public boolean hasCode()
    {
        return !sources.isEmpty();
    }

    /** What the last compile produced, by element path. */
    public Map<String, Object> elements()
    {
        return Map.copyOf(elements);
    }

    public void setElements(Map<String, Object> compiled)
    {
        elements.clear();
        elements.putAll(compiled);
    }

    @Override
    public String toString()
    {
        return "InMemoryModule(" + name + ", " + sources.size() + " source(s))";
    }
}
