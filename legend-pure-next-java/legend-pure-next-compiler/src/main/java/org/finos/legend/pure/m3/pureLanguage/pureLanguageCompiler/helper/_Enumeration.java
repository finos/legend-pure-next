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

import meta.pure.metamodel.function.LambdaFunction;
import meta.pure.metamodel.function.property.Property;
import meta.pure.metamodel.type.Enumeration;
import meta.pure.metamodel.valuespecification.AtomicValue;
import org.finos.legend.pure.m3.module.MetadataAccess;

/**
 * Helper methods for {@link Enumeration}.
 */
public final class _Enumeration
{
    private _Enumeration()
    {
    }

    /**
     * Resolve an enum value by name from an Enumeration loaded in the model.
     * <p>
     * Looks up the Enumeration by path, finds the property with the matching name,
     * and returns the Enum instance from its default value.
     *
     * @param enumerationPath the full metamodel path (e.g., "meta::pure::metamodel::function::property::AggregationKind")
     * @param valueName       the enum value name (e.g., "None")
     * @param model           the metadata access for element lookup
     * @return the Enum instance, or null if not found
     */
    public static meta.pure.metamodel.type.Enum resolveEnumValue(String enumerationPath, String valueName, MetadataAccess model)
    {
        meta.pure.metamodel.PackageableElement element = model.getElement(enumerationPath);
        if (!(element instanceof Enumeration enumeration))
        {
            return null;
        }
        return resolveEnumValue(enumeration, valueName);
    }

    /**
     * Find an enum value by name within an Enumeration.
     * <p>
     * Each enum value is a property on the Enumeration whose default value
     * contains the Enum instance.
     *
     * @param enumeration the Enumeration to search
     * @param valueName   the enum value name (e.g., "Composite")
     * @return the Enum instance, or null if not found
     */
    public static meta.pure.metamodel.type.Enum resolveEnumValue(Enumeration enumeration, String valueName)
    {
        if (enumeration._properties() == null)
        {
            return null;
        }
        for (Property prop : enumeration._properties())
        {
            if (valueName.equals(prop._name()))
            {
                // The enum instance is in: property.defaultValue.expressionSequence[0].value
                if (prop._defaultValue() instanceof LambdaFunction lambda
                        && lambda._expressionSequence() != null
                        && lambda._expressionSequence().notEmpty())
                {
                    Object lastExpr = lambda._expressionSequence().getLast();
                    if (lastExpr instanceof AtomicValue av && av._value() instanceof meta.pure.metamodel.type.Enum enumValue)
                    {
                        return enumValue;
                    }
                }
            }
        }
        return null;
    }
}
