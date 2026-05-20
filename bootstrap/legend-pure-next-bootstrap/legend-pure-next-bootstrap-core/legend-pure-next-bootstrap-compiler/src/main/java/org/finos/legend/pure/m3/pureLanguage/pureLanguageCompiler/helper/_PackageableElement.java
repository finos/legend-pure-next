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

package org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper;

import meta.pure.metamodel.Package;
import meta.pure.metamodel.PackageableElement;
import meta.pure.metamodel.SourceInformation;
import meta.pure.metamodel.pointer.TempCompilerPointer;
import org.eclipse.collections.api.list.ListIterable;
import org.eclipse.collections.api.list.MutableList;
import org.finos.legend.pure.m3.module.MetadataAccess;
import org.finos.legend.pure.m3.module.localModule.topLevel.CompilationContext;
import org.finos.legend.pure.m3.module.localModule.topLevel.CompilationError;
import org.jspecify.annotations.Nullable;

/**
 * Helper methods for {@link PackageableElement}.
 */
public class _PackageableElement
{
    private _PackageableElement()
    {
        // static utility
    }

    /**
     * Find an element by name, trying the fully-qualified path first,
     * then prepending each import package prefix.
     * <p>
     * If the name resolves to multiple distinct elements via different imports,
     * returns {@code null} (ambiguity).
     * <p>
     * This is an internal helper; external callers should use
     * {@link #findElementOrReportError} for proper ambiguity error reporting.
     */
    private static @Nullable PackageableElement findElement(String name, ListIterable<String> imports, MetadataAccess model)
    {
        // Try fully-qualified path first
        PackageableElement element = model.getElement(name);
        if (element != null)
        {
            return element;
        }
        // Try each import prefix, collecting all matches for ambiguity detection
        PackageableElement found = null;
        for (String importPkg : imports)
        {
            element = model.getElement(importPkg + "::" + name);
            if (element != null)
            {
                if (found == null)
                {
                    found = element;
                }
                else if (found != element)
                {
                    // Ambiguous: multiple distinct elements match
                    return null;
                }
            }
        }
        return found;
    }

    /**
     * Find an element by name using imports, reporting a descriptive error
     * if the name is ambiguous (matched by multiple imports).
     * <p>
     * Delegates to {@link #findElement} for the actual lookup; only performs
     * ambiguity analysis when that returns {@code null}.
     *
     * @return the resolved element, or {@code null} if not found or ambiguous
     */
    public static @Nullable PackageableElement findElementOrReportError(
            String name, ListIterable<String> imports, MetadataAccess model,
            CompilationContext context, SourceInformation sourceInformation)
    {
        PackageableElement result = findElement(name, imports, model);
        if (result != null)
        {
            return result;
        }
        // findElement returned null — check whether this is ambiguity
        MutableList<String> matchingPaths = imports
                .select(imp -> model.getElement(imp + "::" + name) != null)
                .collect(imp -> imp + "::" + name)
                .toList();
        if (matchingPaths.size() > 1)
        {
            context.addError(new CompilationError(
                    "The element '" + name + "' is ambiguous, found in multiple imports: " +
                    matchingPaths.makeString("'", "', '", "'"),
                    sourceInformation));
        }
        return null;
    }


    /**
     * Return the fully-qualified path of a packageable element,
     * e.g. {@code "my::package::MyClass"}.
     * Returns the element name alone if it is in the root package.
     *
     * <p>Pointer-aware: a {@link TempCompilerPointer} carries its canonical
     * path in {@code _path()} and leaves the inherited {@code _name} /
     * {@code _package} fields unset; same for any {@code PackagePointer}
     * encountered while walking the parent chain. Without this short-circuit,
     * Pure-compiler skeletons whose {@code package} is a {@code PackagePointer}
     * (the pass-1 wrap that keeps parent refs identity-stable) would resolve
     * to the bare element name and corrupt every downstream
     * {@code toClassPointer(...)} site.</p>
     */
    public static String path(PackageableElement element)
    {
        if (element instanceof TempCompilerPointer p)
        {
            return p._path();
        }
        Package pkg = element._package();
        String name = element._name();
        if (pkg == null || name == null)
        {
            return name != null ? name : "";
        }
        String pkgPath = packagePath(pkg);
        return pkgPath.isEmpty() ? name : pkgPath + "::" + name;
    }

    /**
     * Cached overload — uses {@link MetadataAccess#elementPathCache()} so
     * repeat calls on the same element (very common in self-host: every
     * type reference in error messages, debug, equality) return the
     * pre-computed path without re-walking the parent chain. JFR flagged
     * the uncached version at 2–8% of self-host CPU before this overload.
     */
    public static String path(PackageableElement element, MetadataAccess resolver)
    {
        if (resolver == null)
        {
            return path(element);
        }
        java.util.Map<PackageableElement, String> cache = resolver.elementPathCache();
        String cached = cache.get(element);
        if (cached != null)
        {
            return cached;
        }
        String computed = path(element);
        cache.put(element, computed);
        return computed;
    }

    // Identity-keyed package-path cache. {@link #packagePath(Package)} is
    // recursive (one call per ancestor), so callers that don't have a
    // {@link MetadataAccess} to thread through the cached {@link #path}
    // overload still pay O(depth) per call. Packages are loaded once and
    // never mutated, so {@code IdentityHashMap} (wrapped in
    // {@code ConcurrentHashMap}-equivalent semantics via synchronized
    // wrapping) is safe and bypasses {@code equals}/{@code hashCode} on
    // the package wrapper.
    private static final java.util.Map<Package, String> PACKAGE_PATH_CACHE =
            java.util.Collections.synchronizedMap(new java.util.IdentityHashMap<>());

    private static String packagePath(Package pkg)
    {
        String cached = PACKAGE_PATH_CACHE.get(pkg);
        if (cached != null)
        {
            return cached;
        }
        String computed = computePackagePath(pkg);
        PACKAGE_PATH_CACHE.put(pkg, computed);
        return computed;
    }

    private static String computePackagePath(Package pkg)
    {
        if (pkg instanceof TempCompilerPointer p)
        {
            return p._path();
        }
        if (pkg._package() == null || pkg._name() == null)
        {
            return "";
        }
        String parentPath = packagePath(pkg._package());
        return parentPath.isEmpty() ? pkg._name() : parentPath + "::" + pkg._name();
    }
}
