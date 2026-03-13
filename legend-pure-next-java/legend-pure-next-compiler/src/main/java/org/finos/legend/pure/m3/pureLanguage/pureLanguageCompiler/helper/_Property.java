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

import meta.pure.metamodel.function.property.Property;
import meta.pure.metamodel.function.property.PropertyImpl;
import meta.pure.metamodel.multiplicity.Multiplicity;
import meta.pure.metamodel.type.Class;
import meta.pure.metamodel.type.generics.GenericType;
import org.finos.legend.pure.m3.module.MetadataAccess;

public class _Property
{
    /**
     * Create a new Property with resolved generic type and multiplicity.
     * Substitutes type parameters from the owner class with concrete type
     * arguments from the receiver's generic type.
     */
    public static Property resolveProperty(Property prop, GenericType receiverType, MetadataAccess model)
    {
        GenericType resolvedGenericType = prop._genericType();
        Multiplicity resolvedMultiplicity = prop._multiplicity();
        GenericType resolvedClassifierGenericType = prop._classifierGenericType();

        if (receiverType._rawType() instanceof Class ownerClass)
        {
            ParametersBinding bindings = _Class.buildBindingsFromGenericType(ownerClass, receiverType);
            if (!bindings.typeBindings().isEmpty() || !bindings.multiplicityBindings().isEmpty())
            {
                resolvedGenericType = _GenericType.makeAsConcreteAsPossible(resolvedGenericType, bindings, model);
                resolvedMultiplicity = _Multiplicity.makeAsConcreteAsPossible(resolvedMultiplicity, bindings);
                resolvedClassifierGenericType = _GenericType.makeAsConcreteAsPossible(resolvedClassifierGenericType, bindings, model);
            }
        }

        return new PropertyImpl()
                ._name(prop._name())
                ._genericType(resolvedGenericType)
                ._multiplicity(resolvedMultiplicity)
                ._owner(prop._owner())
                ._classifierGenericType(resolvedClassifierGenericType);
    }
}
