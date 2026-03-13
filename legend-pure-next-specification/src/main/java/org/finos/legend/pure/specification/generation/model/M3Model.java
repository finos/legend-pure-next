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
import org.eclipse.collections.api.factory.Maps;
import org.eclipse.collections.api.factory.Sets;
import org.eclipse.collections.api.list.MutableList;
import org.eclipse.collections.api.map.MutableMap;
import org.eclipse.collections.api.set.MutableSet;

/**
 * Parsed intermediate model of the M3 metamodel.
 * Populated by {@link M3MetamodelReader} and consumed by the various generators
 * (Pure, Java, FlatBuffers, etc.).
 */
public class M3Model
{
    private final MutableMap<String, ClassInfo> classInfoMap = Maps.mutable.empty();
    private final MutableMap<String, EnumInfo> enumInfoMap = Maps.mutable.empty();
    private final MutableMap<String, MutableList<PropertyInfo>> propertiesByOwner = Maps.mutable.empty();
    private final MutableMap<String, ProfileInfo> profileInfoMap = Maps.mutable.empty();
    private final MutableSet<String> classesWithSubtypes = Sets.mutable.empty();
    private final MutableSet<String> mainTaxonomyClasses = Sets.mutable.empty();

    // Maps stereotype resource URI -> "ProfileName.stereotypeName"
    private final MutableMap<String, String> stereotypeDisplayNames = Maps.mutable.empty();
    // Maps tag resource URI -> "ProfileName.tagName"
    private final MutableMap<String, String> tagDisplayNames = Maps.mutable.empty();

    // =========================================================================
    // Accessors
    // =========================================================================

    public MutableMap<String, ClassInfo> classInfoMap()
    {
        return classInfoMap;
    }

    public MutableMap<String, EnumInfo> enumInfoMap()
    {
        return enumInfoMap;
    }

    public MutableMap<String, MutableList<PropertyInfo>> propertiesByOwner()
    {
        return propertiesByOwner;
    }

    public MutableMap<String, ProfileInfo> profileInfoMap()
    {
        return profileInfoMap;
    }

    public MutableSet<String> classesWithSubtypes()
    {
        return classesWithSubtypes;
    }

    public MutableSet<String> mainTaxonomyClasses()
    {
        return mainTaxonomyClasses;
    }

    public MutableMap<String, String> stereotypeDisplayNames()
    {
        return stereotypeDisplayNames;
    }

    public MutableMap<String, String> tagDisplayNames()
    {
        return tagDisplayNames;
    }

    // =========================================================================
    // Derived Computations
    // =========================================================================

    /**
     * Compute which classes have subtypes by analyzing inheritance.
     * Must be called after all classes have been populated.
     */
    public void computeClassesWithSubtypes()
    {
        classInfoMap.valuesView().forEach(classInfo ->
                classInfo.generalizations
                        .select(classInfoMap::containsKey)
                        .forEach(classesWithSubtypes::add));
        classesWithSubtypes.add("Any");
    }

    /**
     * Collect all properties for a class, including inherited properties.
     * Properties are collected in order from most ancestral to own.
     */
    public MutableList<PropertyInfo> collectAllProperties(ClassInfo classInfo)
    {
        MutableList<PropertyInfo> result = Lists.mutable.empty();
        MutableSet<String> seenPropertyNames = Sets.mutable.empty();

        // First add Any's properties (all classes implicitly extend Any)
        if (!"Any".equals(classInfo.name))
        {
            MutableList<PropertyInfo> anyProps = propertiesByOwner.getIfAbsentValue("Any", Lists.mutable.empty());
            anyProps.forEach(prop ->
            {
                if (!seenPropertyNames.contains(prop.name))
                {
                    seenPropertyNames.add(prop.name);
                    result.add(prop);
                }
            });
        }

        collectPropertiesFromHierarchy(classInfo, result, seenPropertyNames);

        return result;
    }

    private void collectPropertiesFromHierarchy(ClassInfo classInfo, MutableList<PropertyInfo> result, MutableSet<String> seenPropertyNames)
    {
        // First, collect from parent classes
        classInfo.generalizations.forEach(parentName ->
        {
            ClassInfo parentInfo = classInfoMap.get(parentName);
            if (parentInfo != null)
            {
                collectPropertiesFromHierarchy(parentInfo, result, seenPropertyNames);
            }
        });

        // Then add own properties (avoiding duplicates)
        MutableList<PropertyInfo> ownProps = propertiesByOwner.getIfAbsentValue(classInfo.name, Lists.mutable.empty());
        ownProps.forEach(prop ->
        {
            if (!seenPropertyNames.contains(prop.name))
            {
                seenPropertyNames.add(prop.name);
                result.add(prop);
            }
        });
    }
}
