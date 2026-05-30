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

import meta.pure.metamodel.Package;
import meta.pure.metamodel.PackageableElement;
import org.eclipse.collections.api.factory.Lists;
import org.eclipse.collections.api.list.MutableList;
import org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._PackageableElement;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Splits {@code Package._children()} across the lean and tests partitions
 * when {@link TestElementFilter.Mode#SPLIT} is in effect.
 *
 * <p>Compile output produces one canonical Package PDO per package path; its
 * {@code children} list mixes lean and test elements. If the lean PDB serialises
 * this Package as-is, its children-pointer vector will contain paths that don't
 * exist in the lean archive — those test children live only in the companion
 * tests PDB. A reader loading lean alone (e.g. a non-test runtime) then fails
 * to resolve them.</p>
 *
 * <p>This helper produces two filtered views:</p>
 * <ul>
 *   <li>{@link #filterPackageChildren} — keeps each Package's children list
 *       narrowed to paths in the same partition;</li>
 *   <li>{@link #withShadowPackages} — appends a <em>shadow</em> Package PDO to
 *       the tests partition for every parent path whose canonical Package
 *       lives in lean. The shadow carries only its test children; the runtime
 *       registry unions both halves via {@link MergedPackage} when both PDBs
 *       are loaded.</li>
 * </ul>
 */
public final class PackageSplitFilter
{
    private PackageSplitFilter() {}

    public static List<PackageableElement> filterPackageChildren(
            List<? extends PackageableElement> partition, Set<String> keepPaths)
    {
        List<PackageableElement> out = new ArrayList<>(partition.size());
        for (PackageableElement e : partition)
        {
            if (e instanceof Package pkg)
            {
                MutableList<PackageableElement> filtered = Lists.mutable.empty();
                MutableList<PackageableElement> original = pkg._children();
                if (original != null)
                {
                    for (PackageableElement child : original)
                    {
                        String cp = _PackageableElement.path(child);
                        if (cp != null && keepPaths.contains(cp)) filtered.add(child);
                    }
                }
                out.add(((Package) pkg._copy())._children(filtered));
            }
            else
            {
                out.add(e);
            }
        }
        return out;
    }

    public static List<PackageableElement> withShadowPackages(
            List<? extends PackageableElement> testElements,
            List<? extends PackageableElement> allElements,
            Set<String> testPaths)
    {
        List<PackageableElement> out = new ArrayList<>(testElements);
        Map<String, List<PackageableElement>> testKidsByParent = new LinkedHashMap<>();
        for (PackageableElement t : testElements)
        {
            Package parent = t._package();
            if (parent == null) continue;
            String parentPath = _PackageableElement.path(parent);
            if (parentPath == null) continue;
            testKidsByParent.computeIfAbsent(parentPath, k -> new ArrayList<>()).add(t);
        }
        Map<String, Package> packageByPath = new HashMap<>();
        for (PackageableElement e : allElements)
        {
            if (e instanceof Package pkg)
            {
                String p = _PackageableElement.path(e);
                if (p != null) packageByPath.put(p, pkg);
            }
        }
        for (Map.Entry<String, List<PackageableElement>> entry : testKidsByParent.entrySet())
        {
            String parentPath = entry.getKey();
            if (testPaths.contains(parentPath))
            {
                // The parent Package itself is a test element — already in
                // `out` with its full children list. No shadow needed.
                continue;
            }
            Package canonical = packageByPath.get(parentPath);
            if (canonical == null) continue;
            MutableList<PackageableElement> shadowKids = Lists.mutable.empty();
            shadowKids.addAllIterable(entry.getValue());
            out.add(((Package) canonical._copy())._children(shadowKids));
        }
        return out;
    }
}
