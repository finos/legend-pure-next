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

package org.finos.legend.pure.truffle.ast.natives.lang;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.NodeInfo;
import meta.pure.metamodel.function.FunctionDefinition;
import meta.pure.metamodel.function.LambdaFunction;
import meta.pure.metamodel.valuespecification.AtomicValue;
import org.finos.legend.pure.truffle.ast.PureNode;
import org.finos.legend.pure.truffle.ast.natives.collection.CollectionHelper;
import org.finos.legend.pure.truffle.runtime.StandaloneEvaluatorHolder;
import org.finos.legend.pure.truffle.types.RawClosure;

/**
 * {@code match(Any[*], Function<{...}>[1..*]) : T[m]} and the two-parameter
 * variant with an extra parameter {@code P[o]}.
 *
 * <p>Evaluates all children, then performs type-based dispatch against the
 * match function collection.</p>
 */
@NodeInfo(shortName = "match")
public final class MatchNode extends PureNode
{
    @Children
    private PureNode[] children;

    public MatchNode(PureNode[] children)
    {
        this.children = children;
    }

    @Override
    public Object executeGeneric(VirtualFrame frame)
    {
        Object[] values = new Object[children.length];
        for (int i = 0; i < children.length; i++)
        {
            values[i] = children[i].executeGeneric(frame);
        }
        return invokeMatch(values);
    }

    @TruffleBoundary
    private static Object invokeMatch(Object[] values)
    {
        // values[0] = value to match
        // values[1] = match functions collection
        // values[2] = optional extra parameter
        Object value = values[0];
        // Unwrap AtomicValue (e.g., date values kept as AV)
        if (value instanceof AtomicValue av)
        {
            value = av._value();
        }
        Object matchFns = values[1];

        org.finos.legend.pure.m3.module.MetadataAccess resolver = StandaloneEvaluatorHolder.current().resolver();
        meta.pure.metamodel.type.Type valueType = getRawValueType(values[0], value, resolver);
        int valueCount = getRawValueCount(value);

        // Iterate over match functions
        int fnCount = CollectionHelper.size(matchFns);
        for (int i = 0; i < fnCount; i++)
        {
            Object mfRaw = CollectionHelper.at(matchFns, i);
            // Unwrap AtomicValue if needed
            if (mfRaw instanceof AtomicValue av)
            {
                mfRaw = av._value();
            }
            if (!(mfRaw instanceof FunctionDefinition fd))
            {
                continue;
            }
            if (!matchesBranch(fd, valueType, valueCount, resolver))
            {
                continue;
            }

            // Build args: [value, optionalExtra]
            Object[] args;
            if (values.length > 2 && fd._parameters() != null && fd._parameters().size() >= 2)
            {
                args = new Object[]{value, values[2]};
            }
            else
            {
                args = new Object[]{value};
            }
            return StandaloneEvaluatorHolder.current().executeFunction(fd, args);
        }
        throw new RuntimeException("No match function matched the value: " + value);
    }

    private static int getRawValueCount(Object value)
    {
        if (value == null || value instanceof org.finos.legend.pure.truffle.types.PureNull)
        {
            return 0;
        }
        return CollectionHelper.size(value);
    }

    private static meta.pure.metamodel.type.Type getRawValueType(Object original, Object unwrapped,
                                                                  org.finos.legend.pure.m3.module.MetadataAccess resolver)
    {
        // If original was AtomicValue with genericType, use that
        if (original instanceof AtomicValue av && av._genericType() != null)
        {
            meta.pure.metamodel.type.Type t =
                    org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._GenericType.type(av._genericType());
            if (t != null)
            {
                return t;
            }
        }
        Object value = unwrapped;
        if (value == null || value instanceof org.finos.legend.pure.truffle.types.PureNull)
        {
            return (meta.pure.metamodel.type.Type) resolver.getElement("meta::pure::metamodel::type::Nil");
        }
        if (value instanceof org.finos.legend.pure.truffle.types.PureSequence)
        {
            // For collections, use the first element's type if available
            int sz = CollectionHelper.size(value);
            if (sz > 0)
            {
                return getRawValueType(CollectionHelper.at(value, 0), CollectionHelper.at(value, 0), resolver);
            }
            return (meta.pure.metamodel.type.Type) resolver.getElement("meta::pure::metamodel::type::Nil");
        }
        if (value instanceof meta.pure.metamodel.type.Any any && any._classifierGenericType() != null)
        {
            return org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._GenericType.type(any._classifierGenericType());
        }
        if (value instanceof Long)
        {
            return (meta.pure.metamodel.type.Type) resolver.getElement("meta::pure::metamodel::type::primitives::Integer");
        }
        if (value instanceof Double)
        {
            return (meta.pure.metamodel.type.Type) resolver.getElement("meta::pure::metamodel::type::primitives::Float");
        }
        if (value instanceof Boolean)
        {
            return (meta.pure.metamodel.type.Type) resolver.getElement("meta::pure::metamodel::type::primitives::Boolean");
        }
        if (value instanceof String)
        {
            return (meta.pure.metamodel.type.Type) resolver.getElement("meta::pure::metamodel::type::primitives::String");
        }
        if (value instanceof java.math.BigDecimal)
        {
            return (meta.pure.metamodel.type.Type) resolver.getElement("meta::pure::metamodel::type::primitives::Decimal");
        }
        return (meta.pure.metamodel.type.Type) resolver.getElement("meta::pure::metamodel::type::Any");
    }

    private static boolean matchesBranch(FunctionDefinition fd,
                                         meta.pure.metamodel.type.Type valueType,
                                         int valueCount,
                                         org.finos.legend.pure.m3.module.MetadataAccess resolver)
    {
        if (fd._parameters() == null || fd._parameters().isEmpty())
        {
            return true;
        }

        meta.pure.metamodel.valuespecification.VariableExpression param = fd._parameters().getFirst();

        if (param._genericType() != null)
        {
            try
            {
                meta.pure.metamodel.type.Type paramType =
                        org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._GenericType.type(param._genericType());
                if (paramType != null && valueType != null
                        && !org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._Type.subtypeOf(valueType, paramType, resolver))
                {
                    return false;
                }
            }
            catch (NullPointerException ignored)
            {
                // GenericType may have null type in FlatBuffer wrappers.
                // When type information is unavailable, accept the branch
                // and let the runtime decide.
            }
        }

        if (param._multiplicity() != null)
        {
            if (!multiplicityAccepts(param._multiplicity(), valueCount))
            {
                return false;
            }
        }

        return true;
    }

    private static boolean multiplicityAccepts(meta.pure.metamodel.multiplicity.Multiplicity mult, int count)
    {
        if (!(mult instanceof meta.pure.metamodel.multiplicity.ConcreteMultiplicity cm))
        {
            return true;
        }

        long lower = 0;
        if (cm._lowerBound() != null)
        {
            lower = cm._lowerBound()._value();
        }

        long upper = -1;
        if (cm._upperBound() != null)
        {
            upper = cm._upperBound()._value();
        }

        if (count < lower)
        {
            return false;
        }
        return upper == -1 || count <= upper;
    }
}
