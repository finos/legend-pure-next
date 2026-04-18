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

import meta.pure.metamodel.type.Type;
import meta.pure.metamodel.type.generics.GenericType;
import meta.pure.metamodel.valuespecification.AtomicValue;
import org.finos.legend.pure.m3.module.MetadataAccess;
import org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._GenericType;
import org.finos.legend.pure.truffle.ast.natives.collection.CollectionHelper;
import org.finos.legend.pure.truffle.types.PureNull;
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
     * generated Impl classes (Any), AtomicValue (dates), and PureSequence.
     */
    public static Type getRawValueType(Object value, MetadataAccess resolver)
    {
        if (value == null || value instanceof PureNull)
        {
            return (Type) resolver.getElement("meta::pure::metamodel::type::Nil");
        }
        // AtomicValue wrapper (dates kept as AV) — use its genericType
        if (value instanceof AtomicValue av && av._genericType() != null)
        {
            Type t = _GenericType.type(av._genericType());
            if (t != null)
            {
                return t;
            }
        }
        if (value instanceof meta.pure.metamodel.type.Any any && any._classifierGenericType() != null)
        {
            return _GenericType.type(any._classifierGenericType());
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
        if (value instanceof String)
        {
            return (Type) resolver.getElement("meta::pure::metamodel::type::primitives::String");
        }
        if (value instanceof java.math.BigDecimal)
        {
            return (Type) resolver.getElement("meta::pure::metamodel::type::primitives::Decimal");
        }
        if (value instanceof PureSequence seq)
        {
            if (seq.size() > 0)
            {
                return getRawValueType(seq.getBoxed(0), resolver);
            }
            return (Type) resolver.getElement("meta::pure::metamodel::type::Nil");
        }
        return (Type) resolver.getElement("meta::pure::metamodel::type::Any");
    }

    /**
     * Get the GenericType of a raw value.
     * For primitive types, builds a GenericType from the resolver.
     * For instances (Any), returns the classifierGenericType.
     */
    public static GenericType getRawGenericType(Object value)
    {
        if (value instanceof AtomicValue av && av._genericType() != null)
        {
            return av._genericType();
        }
        if (value instanceof meta.pure.metamodel.type.Any any)
        {
            return any._classifierGenericType();
        }
        return null;
    }

    /**
     * Get the GenericType of a raw value, constructing one from the resolver
     * for primitive types that don't carry their own GenericType.
     */
    public static GenericType getRawGenericType(Object value, MetadataAccess resolver)
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
            return _GenericType.buildUserDefinedGenericType(type, resolver);
        }
        return null;
    }
}
