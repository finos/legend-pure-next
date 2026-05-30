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
 * PDB ships the canonical Package; a companion {@code -tests} PDB ships a
 * shadow Package whose children are the test-only elements at that path —
 * other modules can equivalently share package paths). On the first lookup
 * for such a path, the contributors' children are folded into the first-
 * registered module's Package instance via {@code _children().add(…)} and
 * that anchor is cached; identity stays with the original PDO so {@code is}
 * checks across multiple resolution paths continue to hold. Non-Package
 * duplicates raise — that would indicate a serialisation bug.</p>
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
