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

package org.finos.legend.pure.specification.generation.model;

import org.eclipse.collections.api.factory.Lists;
import org.eclipse.collections.api.factory.Sets;
import org.eclipse.collections.api.list.MutableList;
import org.eclipse.collections.api.set.MutableSet;

/**
 * Utility methods for traversing and querying the M3 model.
 *
 * <p>Provides stereotype helpers, property hierarchy traversal,
 * FBS naming utilities, and other language-agnostic functions.</p>
 */
public final class ModelUtils
{
    private ModelUtils()
    {
    }

    // =========================================================================
    // Stereotype / Tagged Value Helpers
    // =========================================================================

    /**
     * Extract the bare name from a qualified display name
     * (e.g., "ProtocolInfo.excluded" → "excluded").
     */
    public static String bareName(String displayName)
    {
        int dot = displayName.indexOf('.');
        return dot >= 0 ? displayName.substring(dot + 1) : displayName;
    }

    /**
     * Check if any stereotype matches the given bare name.
     */
    public static boolean hasStereotype(MutableList<String> stereotypes, String bareName)
    {
        return stereotypes.anySatisfy(s -> bareName(s).equals(bareName));
    }

    // =========================================================================
    // Property Hierarchy
    // =========================================================================

    /**
     * Collect all properties for a class, including inherited properties.
     * Properties are collected in order from most ancestral to own.
     */
    public static MutableList<PropertyInfo> collectAllProperties(M3Model m3Model, ClassInfo classInfo)
    {
        MutableList<PropertyInfo> result = Lists.mutable.empty();
        MutableSet<String> seenPropertyNames = Sets.mutable.empty();

        if (!"Any".equals(classInfo.name))
        {
            MutableList<PropertyInfo> anyProps = m3Model.propertiesByOwner().getIfAbsentValue("Any", Lists.mutable.empty());
            anyProps.forEach(prop ->
            {
                if (!seenPropertyNames.contains(prop.name))
                {
                    seenPropertyNames.add(prop.name);
                    result.add(prop);
                }
            });
        }

        collectPropertiesFromHierarchy(m3Model, classInfo, result, seenPropertyNames);
        return result;
    }

    private static void collectPropertiesFromHierarchy(M3Model m3Model, ClassInfo classInfo,
                                                       MutableList<PropertyInfo> result, MutableSet<String> seenPropertyNames)
    {
        classInfo.generalizations.forEach(parentName ->
        {
            ClassInfo parentInfo = m3Model.classInfoMap().get(parentName);
            if (parentInfo != null)
            {
                collectPropertiesFromHierarchy(m3Model, parentInfo, result, seenPropertyNames);
            }
        });

        MutableList<PropertyInfo> ownProps = m3Model.propertiesByOwner().getIfAbsentValue(classInfo.name, Lists.mutable.empty());
        ownProps.forEach(prop ->
        {
            if (!seenPropertyNames.contains(prop.name))
            {
                seenPropertyNames.add(prop.name);
                result.add(prop);
            }
        });
    }

    // =========================================================================
    // FBS Naming Utilities
    // =========================================================================

    /**
     * Convert a camelCase property name to snake_case for FBS field naming.
     * Only escapes identifiers reserved by the FBS schema format itself
     * ({@code type}, {@code namespace}). Language-specific keyword escaping
     * (e.g., for Java) is handled by the target code generators.
     */
    public static String toFbsFieldName(String name)
    {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < name.length(); i++)
        {
            char c = name.charAt(i);
            if (Character.isUpperCase(c) && i > 0)
            {
                sb.append('_');
            }
            sb.append(Character.toLowerCase(c));
        }
        String result = sb.toString();
        if ("type".equals(result) || "namespace".equals(result))
        {
            return result + "_";
        }
        return result;
    }

    /**
     * Build the union type name for a pointer property with nonPointerSubtypes.
     * Converts snake_case field name to PascalCase and appends "Union".
     */
    public static String unionTypeName(String fbsFieldName)
    {
        StringBuilder result = new StringBuilder();
        boolean capitalizeNext = true;
        for (int i = 0; i < fbsFieldName.length(); i++)
        {
            char c = fbsFieldName.charAt(i);
            if (c == '_')
            {
                capitalizeNext = true;
            }
            else
            {
                result.append(capitalizeNext ? Character.toUpperCase(c) : c);
                capitalizeNext = false;
            }
        }
        return result + "Union";
    }

    /**
     * Look up the nonPointerSubtypes for a pointer property's declared type.
     * Returns an empty list if the property is not a pointer or has no subtypes.
     */
    public static MutableList<String> getNonPointerSubtypes(M3Model m3Model, PropertyInfo prop)
    {
        if (!hasStereotype(prop.stereotypes, "pointer") || prop.typeName == null)
        {
            return Lists.mutable.empty();
        }
        ClassInfo typeClass = m3Model.classInfoMap().get(prop.typeName);
        if (typeClass == null)
        {
            return Lists.mutable.empty();
        }
        for (TaggedValueEntry tv : typeClass.taggedValues)
        {
            if ("nonPointerSubtypes".equals(bareName(tv.tag)))
            {
                MutableList<String> result = Lists.mutable.empty();
                for (String s : tv.value.split(","))
                {
                    String trimmed = s.trim();
                    if (!trimmed.isEmpty())
                    {
                        result.add(trimmed);
                    }
                }
                return result;
            }
        }
        return Lists.mutable.empty();
    }

    /**
     * Capitalize the first character of a string.
     */
    public static String capitalize(String s)
    {
        if (s == null || s.isEmpty())
        {
            return s;
        }
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    /**
     * Check if a class is marked with the mainTaxonomy stereotype.
     */
    public static boolean isMainTaxonomy(ClassInfo classInfo)
    {
        return hasStereotype(classInfo.stereotypes, "mainTaxonomy");
    }

    /**
     * Collect all transitive subtypes of a given class name.
     * Walks the generalization graph in reverse (child → parent)
     * to find all classes that directly or transitively extend the given class.
     */
    public static MutableList<String> collectAllSubtypes(M3Model m3Model, String className)
    {
        MutableList<String> result = Lists.mutable.empty();
        collectSubtypesRecursive(m3Model, className, result);
        return result.sortThis();
    }

    private static void collectSubtypesRecursive(M3Model m3Model, String className, MutableList<String> result)
    {
        m3Model.classInfoMap().valuesView().forEach(ci ->
        {
            if (ci.generalizations.contains(className) && !result.contains(ci.name))
            {
                result.add(ci.name);
                collectSubtypesRecursive(m3Model, ci.name, result);
            }
        });
    }
}
