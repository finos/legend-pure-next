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
import org.eclipse.collections.api.list.ListIterable;
import org.eclipse.collections.api.list.MutableList;
import org.finos.legend.pure.m3.module.localModule.topLevel.CompilationContext;
import org.finos.legend.pure.m3.module.localModule.topLevel.CompilationError;
import org.finos.legend.pure.m3.module.MetadataAccess;
import meta.pure.metamodel.SourceInformation;
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
     */
    public static String path(PackageableElement element)
    {
        Package pkg = element._package();
        String name = element._name();
        if (pkg == null || name == null)
        {
            return name != null ? name : "";
        }
        String pkgPath = packagePath(pkg);
        return pkgPath.isEmpty() ? name : pkgPath + "::" + name;
    }

    private static String packagePath(Package pkg)
    {
        if (pkg._package() == null || pkg._name() == null)
        {
            return "";
        }
        String parentPath = packagePath(pkg._package());
        return parentPath.isEmpty() ? pkg._name() : parentPath + "::" + pkg._name();
    }
}
