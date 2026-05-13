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

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import org.finos.legend.pure.truffle.runtime.helper.TypeCache;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
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

    private static final int SLOT_FUNCTION_NAME = org.finos.legend.pure.truffle.runtime.dynobj.PureClassRegistry.globalSlot("functionName");
    private static final int SLOT_PARAMETERS = org.finos.legend.pure.truffle.runtime.dynobj.PureClassRegistry.globalSlot("parameters");
    private final LinkedHashMap<String, TruffleModule> modules = new LinkedHashMap<>();
    private final TypeCache typeCache = new TypeCache();
    // Lazy index keyed by (function shortName, arity) → resolved
    //   {@code PackageableFunction}s. Built on first access from
    //   {@link #functionsByNameAndArity(String,int)} by walking every module's
    //   {@link TruffleModule#elementPaths()} once and resolving each function
    //   path to confirm its name (path mangling is ambiguous, e.g.
    //   {@code unify} vs {@code unify_step1_pairwiseBind}) and parameter count.
    //   Replaces a per-call linear scan in
    //   {@code FindFunctionsByNameAndArityNode} (8.5% of self-host CPU when
    //   measured); after this index every lookup is two map gets. Invalidated
    //   on register / unregister so newly registered modules' functions become
    //   visible without forcing callers through a rebuild.
    private Map<String, Map<Integer, List<Object>>> functionsByNameArity;

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
        functionsByNameArity = null;
    }

    /**
     * Unregister a module and cascade-invalidate any module that depends on
     * it. After this returns, the named module and every transitive dependent
     * are gone from the registry; their associated state (caches, shapes,
     * etc.) becomes garbage as soon as the caller drops references.
     */
    public void unregister(String name)
    {
        TruffleModule removed = modules.get(name);
        if (removed == null)
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
        // Drop cached element lookups owned by this module — otherwise
        // re-registering with fresh content (e.g. an IDE recompile of
        // welcome.pure) would still serve the old objects from the cache.
        // The cache documents itself as "stable for the registry's lifetime,"
        // which holds for PDB modules but not for in-memory modules we
        // unregister + re-register.
        for (String path : removed.elementPaths())
        {
            elementCache.remove(path);
        }
        modules.remove(name);
        functionsByNameArity = null;
        for (String d : dependents)
        {
            unregister(d);
        }
    }

    /**
     * Lookup resolved {@code PackageableFunction}s by short name and arity.
     * O(1) — backed by an index keyed by (functionName, parameterCount) that's
     * built lazily on first access by resolving every module's element paths.
     * Returns the empty list when no function matches; never falls back to a
     * scan.
     */
    @TruffleBoundary
    public List<Object> functionsByNameAndArity(String name, int arity)
    {
        Map<String, Map<Integer, List<Object>>> idx = functionsByNameArity;
        if (idx == null)
        {
            idx = buildFunctionIndex();
            functionsByNameArity = idx;
        }
        Map<Integer, List<Object>> byArity = idx.get(name);
        if (byArity == null)
        {
            return List.of();
        }
        List<Object> hits = byArity.get(arity);
        return hits != null ? hits : List.of();
    }

    @TruffleBoundary
    private Map<String, Map<Integer, List<Object>>> buildFunctionIndex()
    {
        Map<String, Map<Integer, List<Object>>> idx = new HashMap<>();
        for (TruffleModule m : modules.values())
        {
            for (String path : m.elementPaths())
            {
                Object element = getElement(path);
                if (!org.finos.legend.pure.truffle.runtime.dynobj.PureObj.isType(element,
                        "meta::pure::metamodel::function::PackageableFunction", this))
                {
                    continue;
                }
                Object fnNameObj = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.readBySlot(element, SLOT_FUNCTION_NAME);
                if (!(fnNameObj instanceof String fnName))
                {
                    continue;
                }
                Object paramsObj = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.readBySlot(element, SLOT_PARAMETERS);
                int arity = paramsObj instanceof org.finos.legend.pure.truffle.types.PureSequence ps ? ps.size() : 0;
                idx.computeIfAbsent(fnName, k -> new HashMap<>())
                   .computeIfAbsent(arity, k -> new ArrayList<>())
                   .add(element);
            }
        }
        return idx;
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
        // Cache: string-keyed lookups dominate the JFR for the
        // metamodel_factories.pure compile (~40 samples on cumulative
        // resolver.getElement paths). The result is stable for the
        // registry's lifetime — once a module has registered an element,
        // it's the canonical instance. ConcurrentHashMap keeps the read
        // path lock-free; the {@link #ABSENT_PATH} sentinel caches
        // not-found results so repeated lookups for missing paths don't
        // re-iterate the module list.
        Object cached = elementCache.get(path);
        if (cached != null)
        {
            return cached == ABSENT_PATH ? null : cached;
        }
        for (TruffleModule m : modules.values())
        {
            Object e = m.getElement(path);
            if (e != null)
            {
                elementCache.put(path, e);
                return e;
            }
        }
        elementCache.put(path, ABSENT_PATH);
        return null;
    }

    private static final Object ABSENT_PATH = new Object();
    private final java.util.concurrent.ConcurrentHashMap<String, Object> elementCache =
            new java.util.concurrent.ConcurrentHashMap<>();

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
