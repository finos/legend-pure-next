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

package org.finos.legend.pure.truffle.ast.natives.meta;

import org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.type.Type;
import org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.type.generics.GenericType;
import org.finos.legend.pure.truffle.runtime.TruffleMetadataAccess;
import org.finos.legend.pure.truffle.ast.natives.collection.CollectionHelper;
import org.finos.legend.pure.truffle.types.PureDate;
import org.finos.legend.pure.truffle.types.PureSequence;

/**
 * Shared helpers for meta-native Truffle nodes.
 */
public final class MetaHelper
{
    private MetaHelper()
    {
    }

    /**
     * Get the raw Pure Type of a raw value.
     * Handles raw Java values (Long, Double, etc.),
     * generated Impl classes (Any), and PureSequence.
     */
    public static Type getRawValueType(Object value, TruffleMetadataAccess resolver)
    {
        if (value == null || (value instanceof PureSequence ps && ps.isEmpty()))
        {
            return (Type) resolver.getElement("meta::pure::metamodel::type::Nil");
        }
        if (value instanceof org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.type.Any)
        {
            var cgt = org.finos.legend.pure.truffle.PureLanguage.get(null).classifierGenericType(value);
            if (cgt != null)
            {
                return org.finos.legend.pure.truffle.runtime.helper._GenericType.type(cgt);
            }
        }
        if (value instanceof Long)
        {
            return (Type) resolver.getElement("meta::pure::metamodel::type::primitives::Integer");
        }
        if (value instanceof Double)
        {
            return (Type) resolver.getElement("meta::pure::metamodel::type::primitives::Float");
        }
        if (value instanceof Boolean)
        {
            return (Type) resolver.getElement("meta::pure::metamodel::type::primitives::Boolean");
        }
        if (value instanceof PureDate.StrictDate)
        {
            return (Type) resolver.getElement("meta::pure::metamodel::type::primitives::StrictDate");
        }
        if (value instanceof PureDate.DateTime)
        {
            return (Type) resolver.getElement("meta::pure::metamodel::type::primitives::DateTime");
        }
        if (value instanceof PureDate)
        {
            return (Type) resolver.getElement("meta::pure::metamodel::type::primitives::Date");
        }
        if (value instanceof String s)
        {
            // Detect enum value strings: "package::EnumType.VALUE"
            int dotIdx = s.lastIndexOf('.');
            if (dotIdx > 0 && s.contains("::"))
            {
                String enumTypePath = s.substring(0, dotIdx);
                Object enumType = resolver.getElement(enumTypePath);
                if (enumType instanceof Type t)
                {
                    return t;
                }
            }
            // Detect date strings by format (fallback for dates not yet wrapped in PureDate)
            if (org.finos.legend.pure.truffle.ast.natives.string.ToStringNode.isDateString(s))
            {
                if (s.contains("T"))
                {
                    return (Type) resolver.getElement("meta::pure::metamodel::type::primitives::DateTime");
                }
                // yyyy-MM-dd = StrictDate, yyyy or yyyy-MM = Date
                long dashCount = s.chars().filter(c -> c == '-').count();
                if (dashCount >= 2)
                {
                    return (Type) resolver.getElement("meta::pure::metamodel::type::primitives::StrictDate");
                }
                return (Type) resolver.getElement("meta::pure::metamodel::type::primitives::Date");
            }
            return (Type) resolver.getElement("meta::pure::metamodel::type::primitives::String");
        }
        if (value instanceof java.math.BigDecimal)
        {
            return (Type) resolver.getElement("meta::pure::metamodel::type::primitives::Decimal");
        }
        if (value instanceof PureSequence seq)
        {
            return getCollectionType(seq.size(), i -> seq.getBoxed(i), resolver);
        }
        if (value instanceof org.eclipse.collections.api.list.MutableList<?> ml)
        {
            return getCollectionType(ml.size(), ml::get, resolver);
        }
        return (Type) resolver.getElement("meta::pure::metamodel::type::Any");
    }

    /**
     * Compute the most common (least upper bound) type of all elements
     * in a collection. Uses {@code _Type.findCommonType} for LUB computation.
     */
    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    private static Type getCollectionType(int size, java.util.function.IntFunction<Object> getter, TruffleMetadataAccess resolver)
    {
        if (size == 0)
        {
            return (Type) resolver.getElement("meta::pure::metamodel::type::Nil");
        }
        if (size == 1)
        {
            return getRawValueType(getter.apply(0), resolver);
        }
        org.eclipse.collections.api.list.MutableList<Type> types =
                org.eclipse.collections.api.factory.Lists.mutable.ofInitialCapacity(size);
        for (int i = 0; i < size; i++)
        {
            Type t = getRawValueType(getter.apply(i), resolver);
            if (t != null && !types.contains(t))
            {
                types.add(t);
            }
        }
        if (types.size() <= 1)
        {
            return types.isEmpty() ? (Type) resolver.getElement("meta::pure::metamodel::type::Any") : types.get(0);
        }
        Type common = org.finos.legend.pure.truffle.runtime.helper._Type.findCommonType(types, false, resolver);
        return common != null ? common : (Type) resolver.getElement("meta::pure::metamodel::type::Any");
    }

    /**
     * Get the GenericType of a raw value.
     * For primitive types, builds a GenericType from the resolver.
     * For instances (Any), returns the classifierGenericType — routed
     * through PureContext so Java enum constants pick up their per-context
     * cached CGT instead of relying on a JVM-singleton field.
     */
    public static GenericType getRawGenericType(Object value)
    {
        if (value instanceof org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.type.Any)
        {
            return org.finos.legend.pure.truffle.PureLanguage.get(null).classifierGenericType(value);
        }
        return null;
    }

    /**
     * Get the GenericType of a raw value, constructing one from the resolver
     * for primitive types that don't carry their own GenericType.
     */
    public static GenericType getRawGenericType(Object value, TruffleMetadataAccess resolver)
    {
        GenericType gt = getRawGenericType(value);
        if (gt != null)
        {
            return gt;
        }
        // Build a GenericType for primitive types
        Type type = getRawValueType(value, resolver);
        if (type != null)
        {
            return org.finos.legend.pure.truffle.runtime.helper._GenericType.buildUserDefinedGenericType(type, resolver);
        }
        return null;
    }
}
