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
     * generated PDBHelper classes (Any), and PureSequence.
     */
    public static Object getRawValueType(Object value, TruffleMetadataAccess resolver)
    {
        if (value == null || (value instanceof PureSequence ps && ps.isEmpty()))
        {
            return resolver.getElement("meta::pure::metamodel::type::Nil");
        }
        // A RawClosure is the runtime form of a capturing lambda — a Java
        // record wrapping the underlying LambdaFunction PDO plus the capture
        // frame. Its Pure type IS the wrapped lambda's classifier, so delegate.
        // Without this, match/instanceOf/genericType on a capturing lambda
        // fall through every PDO/primitive branch and return Any, which
        // breaks match-on-LambdaFunction (see lang/flow/match.pure's
        // testMatchOnCapturingLambda regression).
        if (value instanceof org.finos.legend.pure.truffle.ast.RawClosure rc)
        {
            return getRawValueType(rc.lambda(), resolver);
        }
        if (org.finos.legend.pure.truffle.runtime.dynobj.PureObj.pureTypeOf(value) != null)
        {
            var cgt = org.finos.legend.pure.truffle.PureLanguage.get(null).classifierGenericType(value);
            if (cgt != null)
            {
                return org.finos.legend.pure.truffle.runtime.helper._GenericType.type(cgt);
            }
        }
        if (value instanceof Long)
        {
            return resolver.getElement("meta::pure::metamodel::type::primitives::Integer");
        }
        if (value instanceof Double)
        {
            return resolver.getElement("meta::pure::metamodel::type::primitives::Float");
        }
        if (value instanceof Boolean)
        {
            return resolver.getElement("meta::pure::metamodel::type::primitives::Boolean");
        }
        if (value instanceof PureDate.StrictDate)
        {
            return resolver.getElement("meta::pure::metamodel::type::primitives::StrictDate");
        }
        if (value instanceof PureDate.DateTime)
        {
            return resolver.getElement("meta::pure::metamodel::type::primitives::DateTime");
        }
        if (value instanceof PureDate)
        {
            return resolver.getElement("meta::pure::metamodel::type::primitives::Date");
        }
        if (value instanceof String s)
        {
            // Detect enum value strings: "package::EnumType.VALUE"
            int dotIdx = s.lastIndexOf('.');
            if (dotIdx > 0 && s.contains("::"))
            {
                String enumTypePath = s.substring(0, dotIdx);
                Object enumType = resolver.getElement(enumTypePath);
                if (enumType != null)
                {
                    return enumType;
                }
            }
            // A Java String value is genuinely a String — type/instanceOf/cast
            // must respect the runtime class, not the string content. Previously
            // a date-format heuristic returned DateTime for date-looking strings
            // (`'2014-12-31T23:00:00.000-0500'`); that broke legitimate
            // String→String casts in compile-pure's value-spec compiler. Dates
            // that originated as Pure date literals already arrive here as
            // {@link PureDate.StrictDate} / {@link PureDate.DateTime} instances
            // (the AtomicValue lowering at PureASTBuilder.lowerAtomicValue
            // wraps them based on the AtomicValue's genericType — see the
            // earlier branches above).
            return resolver.getElement("meta::pure::metamodel::type::primitives::String");
        }
        if (value instanceof java.math.BigDecimal)
        {
            return resolver.getElement("meta::pure::metamodel::type::primitives::Decimal");
        }
        if (value instanceof PureSequence seq)
        {
            return getCollectionType(seq.size(), i -> seq.getBoxed(i), resolver);
        }
        if (value instanceof org.eclipse.collections.api.list.MutableList<?> ml)
        {
            return getCollectionType(ml.size(), ml::get, resolver);
        }
        return resolver.getElement("meta::pure::metamodel::type::Any");
    }

    /**
     * Compute the most common (least upper bound) type of all elements
     * in a collection. Uses {@code _Type.findCommonType} for LUB computation.
     */
    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    private static Object getCollectionType(int size, java.util.function.IntFunction<Object> getter, TruffleMetadataAccess resolver)
    {
        if (size == 0)
        {
            return resolver.getElement("meta::pure::metamodel::type::Nil");
        }
        if (size == 1)
        {
            return getRawValueType(getter.apply(0), resolver);
        }
        org.eclipse.collections.api.list.MutableList<Object> types =
                org.eclipse.collections.api.factory.Lists.mutable.ofInitialCapacity(size);
        for (int i = 0; i < size; i++)
        {
            Object t = getRawValueType(getter.apply(i), resolver);
            if (t != null && !types.contains(t))
            {
                types.add(t);
            }
        }
        if (types.size() <= 1)
        {
            return types.isEmpty() ? resolver.getElement("meta::pure::metamodel::type::Any") : types.get(0);
        }
        Object common = org.finos.legend.pure.truffle.runtime.helper._Type.findCommonType(types, false, resolver);
        return common != null ? common : resolver.getElement("meta::pure::metamodel::type::Any");
    }

    /**
     * Get the GenericType of a raw value.
     * For primitive types, builds a GenericType from the resolver.
     * For instances (Any), returns the classifierGenericType — routed
     * through PureContext so Java enum constants pick up their per-context
     * cached CGT instead of relying on a JVM-singleton field.
     */
    public static Object getRawGenericType(Object value)
    {
        // RawClosure carries the wrapped lambda's classifier — see
        // `getRawValueType` above for why this delegation is required.
        if (value instanceof org.finos.legend.pure.truffle.ast.RawClosure rc)
        {
            return getRawGenericType(rc.lambda());
        }
        // Any Pure metamodel object (typed XPDBHelper OR PureDynamicObject) carries a
        // classifierGenericType. Detect via PureObj.pureTypeOf which works for
        // both representations — the typed `instanceof Any` Java guard misses
        // PureDynamicObject post-loader-flip, falling through to the
        // primitive-type fallback in the 2-arg overload that builds a fresh
        // UDGT with empty typeArguments (loses FB-encoded type args).
        if (value != null
                && org.finos.legend.pure.truffle.runtime.dynobj.PureObj.pureTypeOf(value) != null)
        {
            return org.finos.legend.pure.truffle.PureLanguage.get(null).classifierGenericType(value);
        }
        return null;
    }

    /**
     * Get the GenericType of a raw value, constructing one from the resolver
     * for primitive types that don't carry their own GenericType.
     */
    public static Object getRawGenericType(Object value, TruffleMetadataAccess resolver)
    {
        Object gt = getRawGenericType(value);
        if (gt != null)
        {
            return gt;
        }
        // Build a GenericType for primitive types
        Object type = getRawValueType(value, resolver);
        if (type != null)
        {
            return org.finos.legend.pure.truffle.runtime.helper._GenericType.buildUserDefinedGenericType(type, resolver);
        }
        return null;
    }
}
