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

package org.finos.legend.pure.m3.module;

import org.finos.legend.pure.m3.LanguageExtension;
import meta.pure.metamodel.PackageableElement;
import meta.pure.metamodel.type.Type;
import org.eclipse.collections.api.factory.Lists;
import org.eclipse.collections.api.list.MutableList;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The set of loaded modules, and a {@link MetadataAccess} that walks every one
 * of them (registry-wide visibility). Same name and members as the Truffle and
 * JavaScript {@code ModuleRegistry}: {@link #register}, {@link #unregister},
 * {@link #validate}, {@link #modules}, {@link #module}. A {@code JavaCompiler}
 * owns one; use it for runtime execution where all loaded PDBs must be in scope — particularly for {@code Package.children} pointer
 * resolution, which fails silently when a child element lives in a sibling
 * PDB outside the active {@link ScopedMetadataAccess} chain.
 *
 * <p>Multiple modules may legitimately define the same Package path (the lean
 * PDB ships the canonical Package; a companion {@code -tests} PDB ships a
 * shadow Package whose children are the test-only elements at that path —
 * other modules can equivalently share package paths). On the first lookup
 * for such a path, the contributors' children are folded into the first-
 * registered module's Package instance via {@code _children().add(…)} and
 * that anchor is cached; identity stays with the original PDO so {@code is}
 * checks across multiple resolution paths continue to hold. Non-Package
 * duplicates raise — that would indicate a serialisation bug.</p>
 */
public final class ModuleRegistry implements MetadataAccess
{
    private final MutableList<Module> modules;
    private final Map<Type, MutableList<Type>> linearizationCache = new IdentityHashMap<>();
    private final Map<Type, java.util.List<String>> equalityKeyPropertiesCache = new IdentityHashMap<>();
    private final Map<PackageableElement, String> elementPathCache = new IdentityHashMap<>();
    private final ConcurrentHashMap<String, PackageableElement> elementCache = new ConcurrentHashMap<>();

    public ModuleRegistry()
    {
        this.modules = Lists.mutable.empty();
    }

    public ModuleRegistry(Iterable<? extends Module> modules)
    {
        this();
        modules.forEach(this::register);
    }

    /**
     * Register a module. Dependencies may be registered in any order; call
     * {@link #validate()} once every module is in.
     */
    public void register(Module module)
    {
        if (module(module.name()) != null)
        {
            throw new IllegalStateException(
                    "Module '" + module.name() + "' is already registered. "
                            + "Call unregister(name) before re-registering.");
        }
        this.modules.add(module);
        invalidateElementCache();
    }

    /**
     * Unregister a module and, transitively, every module that depends on it.
     * Package children the removed module folded into a remaining anchor
     * Package are taken back out, so the anchor returns to its own children.
     */
    public void unregister(String name)
    {
        Module removed = module(name);
        if (removed == null)
        {
            return;
        }
        List<String> dependents = new ArrayList<>();
        for (Module m : this.modules)
        {
            if (m.dependencies().contains(name))
            {
                dependents.add(m.name());
            }
        }
        for (Map.Entry<String, PackageableElement> e : this.elementCache.entrySet())
        {
            if (e.getValue() instanceof meta.pure.metamodel.Package anchor && removed.hasElement(e.getKey())
                    && removed.getElement(e.getKey()) instanceof meta.pure.metamodel.Package contributed && contributed != anchor)
            {
                removeChildrenFrom(anchor, contributed);
            }
        }
        this.modules.remove(removed);
        invalidateElementCache();
        dependents.forEach(this::unregister);
    }

    /**
     * Every registered module's declared dependencies must be registered too.
     * Reports every missing dependency, not just the first.
     */
    public void validate()
    {
        List<String> loaded = this.modules.collect(Module::name);
        List<String> errors = new ArrayList<>();
        for (Module m : this.modules)
        {
            for (String dep : m.dependencies())
            {
                if (!loaded.contains(dep))
                {
                    errors.add("Module '" + m.name() + "' declares dependency '" + dep
                            + "' but it was not loaded (loaded: " + loaded + ")");
                }
            }
        }
        if (errors.size() == 1)
        {
            throw new IllegalStateException(errors.get(0));
        }
        if (!errors.isEmpty())
        {
            StringBuilder sb = new StringBuilder("Registry validation failed (")
                    .append(errors.size()).append(" missing dependencies):");
            errors.forEach(e -> sb.append("\n  - ").append(e));
            throw new IllegalStateException(sb.toString());
        }
    }

    /** All registered modules, in resolution order (dependency-first once sorted). */
    public List<Module> modules()
    {
        return Collections.unmodifiableList(this.modules);
    }

    public Module module(String name)
    {
        return this.modules.detect(m -> m.name().equals(name));
    }

    /**
     * Reorder the modules topologically (Kahn's algorithm): dependencies before
     * dependents. Resolution order decides which module's Package anchors a
     * merged package path, so every consumer of this registry sees one order.
     */
    public void sortByDependencies()
    {
        Map<Module, List<Module>> dependents = new LinkedHashMap<>();
        Map<Module, Integer> inDegree = new LinkedHashMap<>();
        for (Module m : this.modules)
        {
            dependents.putIfAbsent(m, new ArrayList<>());
            inDegree.putIfAbsent(m, 0);
        }
        for (Module m : this.modules)
        {
            for (String depName : m.dependencies())
            {
                Module dep = module(depName);
                if (dep != null)
                {
                    dependents.get(dep).add(m);
                    inDegree.merge(m, 1, Integer::sum);
                }
            }
        }
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
                if (inDegree.merge(dependent, -1, Integer::sum) == 0)
                {
                    queue.add(dependent);
                }
            }
        }
        if (sorted.size() != this.modules.size())
        {
            throw new IllegalStateException("Cyclic module dependency detected");
        }
        this.modules.clear();
        this.modules.addAll(sorted);
        invalidateElementCache();
    }

    /**
     * Sort the modules dependency-first and attach each one to this registry with the language
     * extensions (resolvers, function indexes, PDB section decoding).
     */
    public void attach(List<? extends LanguageExtension> extensions)
    {
        sortByDependencies();
        List<LanguageExtension> attached = List.copyOf(extensions);
        for (Module module : List.copyOf(this.modules))
        {
            module.attach(this, attached);
        }
        invalidateElementCache();
    }

    /** Drop cached lookups after a registered module's elements changed (e.g. an in-memory module gained elements). */
    public void invalidate()
    {
        invalidateElementCache();
    }

    private void invalidateElementCache()
    {
        this.elementCache.clear();
    }

    private static void removeChildrenFrom(meta.pure.metamodel.Package anchor, meta.pure.metamodel.Package contributed)
    {
        synchronized (anchor)
        {
            MutableList<PackageableElement> contributedKids = contributed._children();
            if (contributedKids == null || contributedKids.isEmpty()) return;
            Set<PackageableElement> drop = Collections.newSetFromMap(new IdentityHashMap<>());
            drop.addAll(contributedKids);
            anchor._children().removeIf(drop::contains);
        }
    }

    @Override
    public <T extends MetadataAccessExtension> MutableList<T> getMetadataAccessExtension(Class<T> clz)
    {
        return Lists.mutable.<T>empty()
                .withAll(this.modules.flatCollect(m -> m.getMetadataAccessExtension(clz)).select(Objects::nonNull));
    }

    @Override
    public java.util.Set<String> moduleNames()
    {
        java.util.Set<String> names = new java.util.HashSet<>();
        this.modules.forEach(m -> names.add(m.name()));
        return names;
    }

    @Override
    public PackageableElement getElement(String path)
    {
        PackageableElement cached = elementCache.get(path);
        if (cached != null)
        {
            return cached;
        }
        // Resolve OUTSIDE any ConcurrentHashMap compute*-lambda. Reason:
        // resolveAndMerge ends up calling {@code other._children()} on the
        // contributing Package PDOs, which re-enters {@code getElement} to
        // resolve each child PointerRef. JDK ≥ 9 throws "Recursive update"
        // on any reentrant compute* call to the same ConcurrentHashMap, even
        // for a different key, so the lambda form is unusable here. Manual
        // get + putIfAbsent is fine: per-anchor synchronization inside
        // {@code addNewChildrenInto} serialises concurrent merges of the
        // same path, and the identity-set dedupe makes repeated folds a
        // no-op.
        PackageableElement resolved = resolveAndMerge(path);
        if (resolved == null) return null;
        PackageableElement existing = elementCache.putIfAbsent(path, resolved);
        return existing != null ? existing : resolved;
    }

    private PackageableElement resolveAndMerge(String path)
    {
        PackageableElement found = null;
        int count = 0;
        for (Module m : modules)
        {
            if (m.hasElement(path))
            {
                PackageableElement here = m.getElement(path);
                if (count == 0)
                {
                    found = here;
                }
                else if (found instanceof meta.pure.metamodel.Package anchorPkg
                        && here instanceof meta.pure.metamodel.Package otherPkg)
                {
                    // Lazy merge: mutate the anchor's children list in place
                    // so that subsequent reads — whether via getElement or a
                    // PointerRef resolve from any element's _package() — all
                    // see the same anchor instance with the full union of
                    // children. Identity is preserved (callers comparing two
                    // Packages at this path via {@code is} still get true);
                    // the alternative (wrapping in a fresh merged view) broke
                    // Pure-level identity checks that read package via two
                    // paths.
                    addNewChildrenInto(anchorPkg, otherPkg);
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

    private static void addNewChildrenInto(meta.pure.metamodel.Package anchor, meta.pure.metamodel.Package other)
    {
        // Synchronize on the anchor so two threads racing on the same path
        // can't double-add or interleave List mutations. {@code MutableList}
        // is not thread-safe; identity-set dedupe alone wouldn't protect
        // against partial writes.
        synchronized (anchor)
        {
            MutableList<PackageableElement> otherKids = other._children();
            if (otherKids == null || otherKids.isEmpty()) return;
            MutableList<PackageableElement> anchorKids = anchor._children();
            Set<PackageableElement> seen = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
            seen.addAll(anchorKids);
            for (PackageableElement c : otherKids)
            {
                if (c != null && seen.add(c))
                {
                    anchorKids.add(c);
                }
            }
        }
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
