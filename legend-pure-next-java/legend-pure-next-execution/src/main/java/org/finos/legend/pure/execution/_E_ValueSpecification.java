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
import org.eclipse.collections.api.list.MutableList;
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
                            ._multiplicity((PackageableMultiplicity) resolver.getElement("meta::pure::metamodel::multiplicity::PureOne"))
                    :
                    new CollectionImpl(resolver)
                            ._values(Lists.mutable.with(vs))
                            ._genericType(vs._genericType())
                            ._multiplicity((PackageableMultiplicity) resolver.getElement("meta::pure::metamodel::multiplicity::PureZero"));
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
    public static Object unwrap(ValueSpecification vs)
    {
        if (vs == null)
        {
            return null;
        }
        if (vs instanceof AtomicValue av)
        {
            return av._value();
        }
        // Collections — unwrap to a list of values
        if (vs instanceof Collection col)
        {
            List<Object> results = new ArrayList<>();
            for (ValueSpecification v : col._values())
            {
                results.add(unwrap(v));
            }
            return results;
        }
        return vs;
    }

    /**
     * Wrap a raw Java value in an AtomicValueImpl with the given type info.
     */

    public static ValueSpecification wrap(Object value,
                                   meta.pure.metamodel.type.generics.GenericType genericType,
                                   meta.pure.metamodel.multiplicity.Multiplicity multiplicity,
                                   MetadataAccess resolver)
    {
        if (value instanceof ValueSpecification vs)
        {
            return vs;
        }
        if (value instanceof List<?> list)
        {
            MutableList<ValueSpecification> wrapped = Lists.mutable.ofInitialCapacity(list.size());
            for (Object item : list)
            {
                wrapped.add(wrap(item, genericType, multiplicity, resolver));
            }
            return new CollectionImpl(resolver)
                    ._values(wrapped)
                    ._genericType(genericType)
                    ._multiplicity(multiplicity);
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
        Object value = unwrap(vs);
        // DynamicInstance — use classifierGenericType
        if (value instanceof DynamicInstance di)
        {
            return _GenericType.type(di.getClassifierGenericType());
        }
        // All metamodel elements implement Any which has _classifierGenericType()
        if (value instanceof meta.pure.metamodel.type.Any any)
        {
            // GenericType objects' own CGT is the meta-meta type (GenericType itself),
            // which isn't useful for getValueOriginalType. Use VS metadata instead.
            if (value instanceof meta.pure.metamodel.type.generics.GenericType && vs != null && vs._genericType() != null)
            {
                return _GenericType.type(vs._genericType());
            }
            GenericType cgt = any._classifierGenericType();
            if (cgt == null)
            {
                throw new RuntimeException("classifierGenericType is null for " + value.getClass().getName());
            }
            Type type = _GenericType.type(cgt);
            // If the CGT resolves to a TypeParameter (unresolved generic like T),
            // fall back to the VS's genericType which has the concrete binding
            if (type instanceof meta.pure.metamodel.type.generics.TypeParameter && vs != null && vs._genericType() != null)
            {
                return _GenericType.type(vs._genericType());
            }
            return type;
        }
        // Fallback for Java primitives
        if (vs != null && vs._genericType() != null)
        {
            return _GenericType.type(vs._genericType());
        }
        throw new RuntimeException("Cannot determine type of value: " + value.getClass().getName());
    }
}
