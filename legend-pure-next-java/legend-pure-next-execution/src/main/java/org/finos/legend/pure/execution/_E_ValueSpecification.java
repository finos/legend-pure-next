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

package org.finos.legend.pure.execution;

import meta.pure.metamodel.multiplicity.PackageableMultiplicity;
import meta.pure.metamodel.type.Type;
import meta.pure.metamodel.type.generics.GenericType;
import meta.pure.metamodel.valuespecification.AtomicValue;
import meta.pure.metamodel.valuespecification.AtomicValueImpl;
import meta.pure.metamodel.valuespecification.Collection;
import meta.pure.metamodel.valuespecification.CollectionImpl;
import meta.pure.metamodel.valuespecification.ValueSpecification;
import org.eclipse.collections.api.factory.Lists;
import org.finos.legend.pure.m3.module.MetadataAccess;
import org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._GenericType;

import java.util.ArrayList;
import java.util.List;

/**
 * Utility for querying Pure types and multiplicities from {@link ValueSpecification}
 * instances, without relying on Java {@code instanceof} checks on unwrapped values.
 */
public class _E_ValueSpecification
{
    private _E_ValueSpecification()
    {
    }

    // =========================================================================
    // Multiplicity checks
    // =========================================================================

    /**
     * Ensure a ValueSpecification is a Collection.
     * If it already is, return it as-is. Otherwise wrap it in a single-element Collection.
     */
    public static Collection toCollection(ValueSpecification vs, MetadataAccess resolver)
    {
        if (vs instanceof Collection col)
        {
            return col;
        }
        else if (vs instanceof AtomicValue)
        {
            Object value = ((AtomicValue) vs)._value();
            return value == null ?
                    new CollectionImpl(resolver)
                            ._values(Lists.mutable.empty())
                            ._genericType(vs._genericType())
                            ._multiplicity((PackageableMultiplicity) resolver.getElement("meta::pure::metamodel::multiplicity::PureZero"))
                    :
                    new CollectionImpl(resolver)
                            ._values(Lists.mutable.with(vs))
                            ._genericType(vs._genericType())
                            ._multiplicity((PackageableMultiplicity) resolver.getElement("meta::pure::metamodel::multiplicity::PureOne"));
        }
        else
        {
            return new CollectionImpl(resolver)
                    ._values(Lists.mutable.with(vs))
                    ._genericType(vs._genericType())
                    ._multiplicity((PackageableMultiplicity) resolver.getElement("meta::pure::metamodel::multiplicity::PureOne"));
        }
    }

    /**
     * Extract the raw Java value from a ValueSpecification.
     * Returns null for null inputs or GenericTypeAndMultiplicityHolder values.
     */
    public static Object unwrap(Object p)
    {
        if (p == null)
        {
            return null;
        }
        if (!(p instanceof ValueSpecification vs))
        {
            return p;
        }
        if (vs instanceof AtomicValue av)
        {
            return av._value();
        }
        // Collections — unwrap one level: each child unwrapped once
        if (vs instanceof Collection col)
        {
            List<Object> results = new ArrayList<>();
            for (Object v : col._values())
            {
                if (v instanceof AtomicValue childAv)
                {
                    results.add(childAv._value());
                }
                else
                {
                    results.add(v);
                }
            }
            return results;
        }
        return vs;
    }

    /**
     * Wrap a raw Java value in a ValueSpecification.
     * Scalars become AtomicValues; Lists become Collections.
     */
    public static ValueSpecification wrap(Object value,
                                   meta.pure.metamodel.type.generics.GenericType genericType,
                                   meta.pure.metamodel.multiplicity.Multiplicity multiplicity,
                                   MetadataAccess resolver)
    {
        if (value instanceof List<?> list)
        {
            java.util.List<ValueSpecification> items = new java.util.ArrayList<>(list.size());
            for (Object item : list)
            {
                items.add(wrap(item, genericType, null, resolver));
            }
            return org.finos.legend.pure.execution.natives.collection.CollectionNatives.makeCollection(items, resolver);
        }
        return new AtomicValueImpl(resolver)
                ._value(value)
                ._genericType(genericType)
                ._multiplicity(multiplicity);
    }

    /**
     * Determine the Type of a runtime value.
     * Uses classifierGenericType for metamodel elements and DynamicInstances,
     * and the ValueSpecification's genericType for Java primitives.
     * Throws if the type cannot be determined.
     */
    public static Type getValueOriginalType(ValueSpecification vs)
    {
        return getValueOriginalType(vs, null);
    }

    /**
     * Determine the Type of a runtime value, with optional resolver for
     * self-referential classifierGenericType fallback.
     */
    public static Type getValueOriginalType(ValueSpecification vs, MetadataAccess resolver)
    {
        Object value = (vs instanceof AtomicValue av) ? av._value() : vs;
        // Collection — compute common type from actual elements
        if (vs instanceof Collection col)
        {
            if (col._values().isEmpty())
            {
                return resolver != null ? (Type) resolver.getElement("meta::pure::metamodel::type::Nil") : null;
            }
            org.eclipse.collections.api.list.MutableList<GenericType> elementTypes =
                    col._values().collect(ValueSpecification::_genericType).select(gt -> gt != null);
            if (elementTypes.notEmpty() && resolver != null)
            {
                return _GenericType.type(_GenericType.findCommonGenericType(elementTypes, resolver));
            }
            return col._genericType() != null ? _GenericType.type(col._genericType()) : null;
        }
        // DynamicInstance — use classifierGenericType
        if (value instanceof DynamicInstance di)
        {
            return _GenericType.type(di.getClassifierGenericType());
        }
        // All metamodel elements implement Any which has _classifierGenericType()
        if (value instanceof meta.pure.metamodel.type.Any any)
        {
            GenericType cgt = any._classifierGenericType();
            if (cgt == null && any instanceof ValueSpecification vsVal && vsVal._genericType() != null)
            {
                return _GenericType.type(vsVal._genericType());
            }
            if (cgt == null)
            {
                throw new RuntimeException("classifierGenericType is null for " + value.getClass().getName()
                        + (value instanceof meta.pure.metamodel.PackageableElement pe ? " name='" + pe._name() + "'" : ""));
            }
            // FlatBuffer GenericTypeValue wrappers return 'this' from _classifierGenericType()
            // when the classifier pointer is unset (uType==0 self-referential).
            // In that case cgt._type() returns the *inner* wrapped type (e.g., Number)
            // rather than the meta-class (UserDefinedGenericType). Detect and resolve properly.
            // IMPORTANT: Do NOT use vs._genericType() here — that gives the declared static type
            // (e.g., GenericType) which is too broad. We need the actual runtime type
            // (e.g., UserDefinedGenericType) resolved via the Java interface name.
            return _GenericType.type(cgt);
        }
        // Java primitives and collections — use the VS genericType
        if (vs != null && vs._genericType() != null)
        {
            return _GenericType.type(vs._genericType());
        }
        throw new RuntimeException("Cannot determine type of value: " + value.getClass().getName());
    }
}

