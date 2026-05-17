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
        return result + "PropertyUnion";
    }

    /**
     * Inline subtypes (from the type's {@code nonPointerSubtypes} ancestor)
     * that are also reachable from the property's declared type. Returns
     * empty if the property doesn't carry {@code @pointer} or
     * {@code @maybePointer}, or if no inline subtype is actually reachable.
     *
     * <p>Unlike {@link #getNonPointerSubtypesForType}, this method intersects
     * the inline list against the property's declared-type reachable set, so a
     * property typed {@code QualifiedProperty} (a subtype of FunctionDefinition,
     * which lists {@code LambdaFunction} as inline) gets an empty list — a
     * QualifiedProperty slot can't actually hold a LambdaFunction.</p>
     */
    public static MutableList<String> getNonPointerSubtypes(M3Model m3Model, PropertyInfo prop)
    {
        if (prop == null || prop.typeName == null)
        {
            return Lists.mutable.empty();
        }
        if (!hasStereotype(prop.stereotypes, "pointer")
                && !hasStereotype(prop.stereotypes, "maybePointer"))
        {
            return Lists.mutable.empty();
        }
        return Lists.mutable.withAll(inlineEligibleSubtypes(m3Model, prop));
    }

    /**
     * Look up the nonPointerSubtypes annotation reachable from the given type by
     * walking up the generalization chain. Returns the list declared on the
     * closest ancestor (including the type itself) that defines it; empty if
     * none in the chain do.
     */
    public static MutableList<String> getNonPointerSubtypesForType(M3Model m3Model, String typeName)
    {
        if (typeName == null)
        {
            return Lists.mutable.empty();
        }
        ClassInfo cur = m3Model.classInfoMap().get(typeName);
        MutableSet<String> seen = Sets.mutable.empty();
        while (cur != null && seen.add(cur.name))
        {
            for (TaggedValueEntry tv : cur.taggedValues)
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
            // Walk up to the first generalization that's a known class
            ClassInfo next = null;
            for (String parent : cur.generalizations)
            {
                ClassInfo parentInfo = m3Model.classInfoMap().get(parent);
                if (parentInfo != null)
                {
                    next = parentInfo;
                    break;
                }
            }
            cur = next;
        }
        return Lists.mutable.empty();
    }

    /**
     * Concrete subtypes reachable from {@code typeName} (its closure plus self if
     * concrete). The returned set contains only non-abstract class names. Classes
     * marked {@code @transientCompilerOnly} or carrying the {@code compiler.pointer}
     * stereotype are excluded — they exist only in the in-memory compile-time graph
     * and never participate in serialization as their own table.
     */
    public static MutableSet<String> reachableConcreteSubtypes(M3Model m3Model, String typeName)
    {
        MutableSet<String> result = Sets.mutable.empty();
        if (typeName == null)
        {
            return result;
        }
        ClassInfo self = m3Model.classInfoMap().get(typeName);
        if (self != null && !isAbstract(self) && !isTransientCompilerOnly(self) && !isCompilerPointer(self))
        {
            result.add(typeName);
        }
        for (String s : collectAllSubtypes(m3Model, typeName))
        {
            ClassInfo ci = m3Model.classInfoMap().get(s);
            if (ci == null || (!isTransientCompilerOnly(ci) && !isCompilerPointer(ci)))
            {
                result.add(s);
            }
        }
        return result;
    }

    /**
     * True iff the class carries the {@code @transientCompilerOnly} stereotype:
     * compile-time sentinel, never serialized.
     */
    public static boolean isTransientCompilerOnly(ClassInfo ci)
    {
        return ci != null && hasStereotype(ci.stereotypes, "transientCompilerOnly");
    }

    /**
     * True iff the class carries the {@code compiler.pointer} stereotype.
     *
     * <p>Pointer classes (e.g. {@code FunctionPointer}, {@code ClassPointer}) live
     * in m3.ttl so they get Java interfaces + XImpls — but their instances are
     * compile-time-only indirections and never serialize as their own table.
     * The pointer DI's resolution key collapses to a {@code PointerRef} at
     * slot-write time, in the slot where the pointer appears.</p>
     *
     * <p>So FBS schema gen, FBS Java wrapper/writer gen, and protocol gen all
     * skip pointer classes; only Java interface/XImpl codegen runs.</p>
     */
    public static boolean isCompilerPointer(ClassInfo ci)
    {
        return ci != null && ci.stereotypes.contains("compiler.pointer");
    }

    /**
     * Subtypes of the property's declared type that would be encoded as
     * pointers at runtime: reachable concrete subtypes that are not on the
     * taxonomy's {@code nonPointerSubtypes} list AND that extend
     * {@code PackageableElement} (only PEs are pointer-encodable). Empty means
     * no pointer can ever flow through this property — so {@code @pointer}
     * would be wrong to set.
     */
    public static MutableSet<String> pointerEligibleSubtypes(M3Model m3Model, PropertyInfo prop)
    {
        if (prop == null || prop.typeName == null)
        {
            return Sets.mutable.empty();
        }
        MutableSet<String> reachable = reachableConcreteSubtypes(m3Model, prop.typeName);
        MutableList<String> nps = getNonPointerSubtypesForType(m3Model, prop.typeName);
        MutableSet<String> inlineLeaves = Sets.mutable.empty();
        for (String npsName : nps)
        {
            inlineLeaves.addAll(reachableConcreteSubtypes(m3Model, npsName));
        }
        MutableSet<String> result = Sets.mutable.empty();
        for (String name : reachable)
        {
            if (!inlineLeaves.contains(name) && isPointerEncodable(m3Model, name))
            {
                result.add(name);
            }
        }
        return result;
    }

    /**
     * True iff the class itself carries the {@code @pointerSource} stereotype.
     * Pointer-source classes are foundational definitions whose instances have
     * addressable identity (path / owner+name / profile+name).
     */
    public static boolean isPointerSource(ClassInfo ci)
    {
        return ci != null && hasStereotype(ci.stereotypes, "pointerSource");
    }

    /**
     * True iff {@code className} is, or transitively extends, a class declared
     * {@code @pointerSource} — i.e., its instances are addressable and qualify
     * for pointer encoding in serialization.
     */
    public static boolean isPointerEncodable(M3Model m3Model, String className)
    {
        return findPointerSource(m3Model, className) != null;
    }

    /**
     * The closest {@code @pointerSource} ancestor of {@code className} (or the
     * class itself if it carries the stereotype). Null if no such ancestor.
     * This is the class's "definition taxonomy" — the canonical place where
     * its identity is anchored. The protocol generator uses this to decide
     * which inheritance edges to keep when emitting the protocol grammar:
     * edges to other (non-pointerSource) taxonomies are dropped, since those
     * relationships are M3-only and become path-references in the protocol.
     */
    public static String findPointerSource(M3Model m3Model, String className)
    {
        if (className == null)
        {
            return null;
        }
        MutableSet<String> seen = Sets.mutable.empty();
        return findPointerSourceRecursive(m3Model, className, seen);
    }

    private static String findPointerSourceRecursive(M3Model m3Model, String name, MutableSet<String> seen)
    {
        if (!seen.add(name))
        {
            return null;
        }
        ClassInfo ci = m3Model.classInfoMap().get(name);
        if (ci == null)
        {
            return null;
        }
        if (isPointerSource(ci))
        {
            return name;
        }
        for (String parent : ci.generalizations)
        {
            String found = findPointerSourceRecursive(m3Model, parent, seen);
            if (found != null)
            {
                return found;
            }
        }
        return null;
    }

    /**
     * True iff at least one value reachable through this property would have to
     * be pointer-encoded — i.e., the property's @pointer stereotype is required.
     */
    public static boolean needsPointerStereotype(M3Model m3Model, PropertyInfo prop)
    {
        return pointerEligibleSubtypes(m3Model, prop).notEmpty();
    }

    /**
     * Inline-eligible subtypes for this property: subtypes reachable from the
     * declared type that are also in the taxonomy's nonPointerSubtypes list.
     * Empty means no inline value could ever flow through this slot — even if
     * an ancestor type declares an inline list, none of those leaves are
     * reachable from this property's narrower declared type.
     */
    public static MutableSet<String> inlineEligibleSubtypes(M3Model m3Model, PropertyInfo prop)
    {
        if (prop == null || prop.typeName == null)
        {
            return Sets.mutable.empty();
        }
        MutableSet<String> reachable = reachableConcreteSubtypes(m3Model, prop.typeName);
        MutableList<String> nps = getNonPointerSubtypesForType(m3Model, prop.typeName);
        if (nps.isEmpty())
        {
            return Sets.mutable.empty();
        }
        MutableSet<String> inlineLeaves = Sets.mutable.empty();
        for (String npsName : nps)
        {
            inlineLeaves.addAll(reachableConcreteSubtypes(m3Model, npsName));
        }
        MutableSet<String> result = Sets.mutable.empty();
        for (String name : reachable)
        {
            if (inlineLeaves.contains(name))
            {
                result.add(name);
            }
        }
        return result;
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
     * Check if a class has subtypes.
     */
    public static boolean isMainTaxonomy(M3Model m3Model, ClassInfo classInfo)
    {
        return m3Model.classesWithSubtypes().contains(classInfo.name);
    }

    /**
     * Check if a class is marked as abstract.
     */
    public static boolean isAbstract(ClassInfo ci)
    {
        return ci != null && ci.stereotypes.anySatisfy(stereo -> bareName(stereo).equals("abstract") || stereo.endsWith("_abstract"));
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
        return result.reject(name ->
        {
            ClassInfo ci = m3Model.classInfoMap().get(name);
            return isAbstract(ci) || isCompilerPointer(ci);
        }).sortThis();
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
