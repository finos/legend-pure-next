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

package org.finos.legend.pure.truffle.runtime;

import org.finos.legend.pure.truffle.runtime.helper.TypeCache;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Owns the set of {@link TruffleModule}s a {@code PureContext} can resolve
 * against, and provides the composite {@link TruffleMetadataAccess} that
 * FlatBuffer wrappers, native nodes, and helpers consume.
 *
 * <p>Replaces the anonymous-class composite resolvers that used to be
 * defined inline in {@code TrufflePureTestRunner}, {@code TruffleCompileToPdbTest},
 * and {@code TruffleCompilerBinaryBuilder}. Centralising them here:</p>
 * <ul>
 *   <li>gives every module a stable {@link TruffleModule#name() name} and
 *       declared {@link TruffleModule#dependencies() dependencies};</li>
 *   <li>makes the reverse lookup ({@link #moduleOf(Object)} / {@link
 *       #moduleOfPath(String)}) cheap — needed by per-module caches
 *       (compiled function bodies, classifier-generic-type singletons,
 *       and shapes for user-defined classes);</li>
 *   <li>gives the system a real {@link #unregister(String) unregister}
 *       hook so a recompile can drop a module's state without touching
 *       unrelated modules.</li>
 * </ul>
 *
 * <p>Resolution walks modules in registration order (insertion-ordered
 * map). Callers register dependencies before dependents — typically
 * {@code core} first, then {@code compiler}, then any user PDBs.</p>
 */
public final class TruffleModuleRegistry implements TruffleMetadataAccess
{
    private final LinkedHashMap<String, TruffleModule> modules = new LinkedHashMap<>();
    private final TypeCache typeCache = new TypeCache();

    /**
     * Register a module. Validates that all declared dependencies are already
     * present so resolution order is unambiguous.
     */
    public void register(TruffleModule module)
    {
        for (String dep : module.dependencies())
        {
            if (!modules.containsKey(dep))
            {
                throw new IllegalStateException(
                        "Module '" + module.name() + "' declares dependency '" + dep
                                + "' which is not registered. Register dependencies first.");
            }
        }
        if (modules.containsKey(module.name()))
        {
            throw new IllegalStateException(
                    "Module '" + module.name() + "' is already registered. "
                            + "Call unregister(name) before re-registering.");
        }
        modules.put(module.name(), module);
    }

    /**
     * Unregister a module and cascade-invalidate any module that depends on
     * it. After this returns, the named module and every transitive dependent
     * are gone from the registry; their associated state (caches, shapes,
     * etc.) becomes garbage as soon as the caller drops references.
     */
    public void unregister(String name)
    {
        if (!modules.containsKey(name))
        {
            return;
        }
        // Snapshot dependents before removing, so we can recurse.
        List<String> dependents = new ArrayList<>();
        for (TruffleModule m : modules.values())
        {
            if (m.dependencies().contains(name))
            {
                dependents.add(m.name());
            }
        }
        modules.remove(name);
        for (String d : dependents)
        {
            unregister(d);
        }
    }

    /** All registered modules, in dependency-first order. */
    public Collection<TruffleModule> modules()
    {
        return modules.values();
    }

    public TruffleModule module(String name)
    {
        return modules.get(name);
    }

    /**
     * Find which module owns the given Pure path. Walks modules in registration
     * order and returns the first that {@link TruffleMetadataAccess#hasElement
     * hasElement(path)}.
     */
    public TruffleModule moduleOfPath(String path)
    {
        for (TruffleModule m : modules.values())
        {
            if (m.hasElement(path))
            {
                return m;
            }
        }
        return null;
    }

    /**
     * Find which module owns the given element instance (one previously
     * returned from {@link #getElement}). Uses each module's reverse-lookup
     * map; returns null for runtime-created instances that no module owns.
     */
    public TruffleModule moduleOf(Object element)
    {
        for (TruffleModule m : modules.values())
        {
            if (m.pathOf(element) != null)
            {
                return m;
            }
        }
        return null;
    }

    @Override
    public Object getElement(String path)
    {
        for (TruffleModule m : modules.values())
        {
            Object e = m.getElement(path);
            if (e != null)
            {
                return e;
            }
        }
        return null;
    }

    @Override
    public boolean hasElement(String path)
    {
        for (TruffleModule m : modules.values())
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
        Set<String> all = new LinkedHashSet<>();
        for (TruffleModule m : modules.values())
        {
            all.addAll(m.elementPaths());
        }
        return all;
    }

    @Override
    public String pathOf(Object element)
    {
        for (TruffleModule m : modules.values())
        {
            String p = m.pathOf(element);
            if (p != null)
            {
                return p;
            }
        }
        return null;
    }

    @Override
    public TruffleTypeCache typeCache()
    {
        return typeCache;
    }
}
