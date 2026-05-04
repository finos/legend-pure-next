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

import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import com.oracle.truffle.api.nodes.NodeInfo;
import org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.function.FunctionDefinition;
import org.finos.legend.pure.truffle.ast.PureNode;
import org.finos.legend.pure.truffle.ast.RawLambdaCallNode;
import org.finos.legend.pure.truffle.ast.natives.collection.CollectionHelper;
import org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.type.Type;
import org.finos.legend.pure.truffle.runtime.TruffleMetadataAccess;
import org.finos.legend.pure.truffle.runtime.helper._GenericType;
import org.finos.legend.pure.truffle.runtime.helper._Type;
import org.finos.legend.pure.truffle.ast.RawClosure;

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

    @Child
    private RawLambdaCallNode matchCallNode = new RawLambdaCallNode();

    public MatchNode(PureNode[] children)
    {
        this.children = children;
    }

    @Override
    public Object executeGeneric(VirtualFrame frame)
    {
        Object[] values = evaluateChildren(frame);
        return invokeMatch(values, getContext(), matchCallNode);
    }

    @ExplodeLoop
    private Object[] evaluateChildren(VirtualFrame frame)
    {
        Object[] values = new Object[children.length];
        for (int i = 0; i < children.length; i++)
        {
            values[i] = children[i].executeGeneric(frame);
        }
        return values;
    }

    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    private static Object invokeMatch(Object[] values, org.finos.legend.pure.truffle.PureContext context, RawLambdaCallNode matchCallNode)
    {
        TruffleMetadataAccess resolver = context.resolver();
        // values[0] = value to match
        // values[1] = match functions collection
        // values[2] = optional extra parameter
        Object value = values[0];
        Object matchFns = values[1];

        org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.type.Type valueType = getRawValueType(value, context);
        int valueCount = getRawValueCount(value);

        // Iterate over match functions
        int fnCount = CollectionHelper.size(matchFns);
        for (int i = 0; i < fnCount; i++)
        {
            Object mfRaw = CollectionHelper.at(matchFns, i);
            // Unwrap AtomicValue wrapper — FlatBuffer-based collections may
            // deliver match lambdas still wrapped in their VS envelope.
            if (mfRaw instanceof org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.valuespecification.AtomicValue av
                    && av._value() != null)
            {
                mfRaw = av._value();
            }
            // Extract the FunctionDefinition for type matching, but keep the
            // original (possibly RawClosure) for invocation so captured open
            // variables are preserved.
            FunctionDefinition fd;
            if (mfRaw instanceof RawClosure rc)
            {
                fd = rc.lambda();
            }
            else if (mfRaw instanceof FunctionDefinition f)
            {
                fd = f;
            }
            else
            {
                throw new RuntimeException("Not possible");
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
            // Pass the original mfRaw (RawClosure or LambdaFunction) so
            // RawLambdaRootNode can bind captured open variables.
            Object matchResult = matchCallNode.callWithArgs(mfRaw, args);
            return matchResult;
        }
        String vtPath = (valueType instanceof org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.PackageableElement pe)
                ? org.finos.legend.pure.truffle.runtime.helper._PackageableElement.path(pe) : "n/a";
        Object cgt = context.classifierGenericType(value);
        throw new RuntimeException("No match function matched the value: " + value
                + " [valueType=" + vtPath + ", cgt=" + (cgt == null ? "NULL" : cgt.getClass().getName()) + ", fnCount=" + fnCount + "]");
    }

    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    private static int getRawValueCount(Object value)
    {
        if (value == null || (value instanceof org.finos.legend.pure.truffle.types.PureSequence ps && ps.isEmpty()))
        {
            return 0;
        }
        return CollectionHelper.size(value);
    }

    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    private static org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.type.Type getRawValueType(Object value,
                                                                                                   org.finos.legend.pure.truffle.PureContext context)
    {
        TruffleMetadataAccess resolver = context.resolver();
        if (value == null || (value instanceof org.finos.legend.pure.truffle.types.PureSequence ps && ps.isEmpty()))
        {
            return (org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.type.Type) resolver.getElement("meta::pure::metamodel::type::Nil");
        }
        if (value instanceof org.finos.legend.pure.truffle.types.PureSequence seq)
        {
            if (seq.isEmpty())
            {
                return (org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.type.Type) resolver.getElement("meta::pure::metamodel::type::Nil");
            }
            // Compute the most common type across all elements
            java.util.List<org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.type.Type> types = new java.util.ArrayList<>();
            for (int i = 0; i < seq.size(); i++)
            {
                org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.type.Type t = getRawValueType(seq.getBoxed(i), context);
                if (t != null)
                {
                    types.add(t);
                }
            }
            if (types.isEmpty())
            {
                return (org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.type.Type) resolver.getElement("meta::pure::metamodel::type::Nil");
            }
            return org.finos.legend.pure.truffle.runtime.helper._Type.findCommonType(types, false, resolver);
        }
        if (value instanceof org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.type.Any any)
        {
            var cgt = context.classifierGenericType(value);
            if (cgt == null)
            {
                throw new RuntimeException("No classifierGenericType on: " + value.getClass().getName()
                        + " id=" + System.identityHashCode(value));
            }
            return org.finos.legend.pure.truffle.runtime.helper._GenericType.type(cgt);
        }
        if (value instanceof Long)
        {
            return (org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.type.Type) resolver.getElement("meta::pure::metamodel::type::primitives::Integer");
        }
        if (value instanceof Double)
        {
            return (org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.type.Type) resolver.getElement("meta::pure::metamodel::type::primitives::Float");
        }
        if (value instanceof Boolean)
        {
            return (org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.type.Type) resolver.getElement("meta::pure::metamodel::type::primitives::Boolean");
        }
        if (value instanceof org.finos.legend.pure.truffle.types.PureDate.StrictDate)
        {
            return (org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.type.Type) resolver.getElement("meta::pure::metamodel::type::primitives::StrictDate");
        }
        if (value instanceof org.finos.legend.pure.truffle.types.PureDate.DateTime)
        {
            return (org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.type.Type) resolver.getElement("meta::pure::metamodel::type::primitives::DateTime");
        }
        if (value instanceof org.finos.legend.pure.truffle.types.PureDate)
        {
            return (org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.type.Type) resolver.getElement("meta::pure::metamodel::type::primitives::Date");
        }
        if (value instanceof String s)
        {
            // Detect date strings by format (fallback for dates not yet wrapped in PureDate)
            if (org.finos.legend.pure.truffle.ast.natives.string.ToStringNode.isDateString(s))
            {
                if (s.contains("T"))
                {
                    return (org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.type.Type) resolver.getElement("meta::pure::metamodel::type::primitives::DateTime");
                }
                return (org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.type.Type) resolver.getElement("meta::pure::metamodel::type::primitives::Date");
            }
            // Detect enum value strings
            int dotIdx = s.lastIndexOf('.');
            if (dotIdx > 0 && s.contains("::"))
            {
                String enumTypePath = s.substring(0, dotIdx);
                Object enumType = resolver.getElement(enumTypePath);
                if (enumType instanceof org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.type.Type t)
                {
                    return t;
                }
            }
            return (org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.type.Type) resolver.getElement("meta::pure::metamodel::type::primitives::String");
        }
        if (value instanceof java.math.BigDecimal)
        {
            return (org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.type.Type) resolver.getElement("meta::pure::metamodel::type::primitives::Decimal");
        }
        return (org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.type.Type) resolver.getElement("meta::pure::metamodel::type::Any");
    }

    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    private static boolean matchesBranch(FunctionDefinition fd,
                                         org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.type.Type valueType,
                                         int valueCount,
                                         org.finos.legend.pure.truffle.runtime.TruffleMetadataAccess resolver)
    {
        if (fd._parameters() == null || fd._parameters().isEmpty())
        {
            return true;
        }

        Object paramObj = fd._parameters().getBoxed(0);
        if (!(paramObj instanceof org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.valuespecification.VariableExpression param))
        {
            throw new RuntimeException("Error");
        }

        // For null/empty values (count=0), skip type check — Nil is compatible with everything.
        // Only check multiplicity.
        if (valueCount == 0)
        {
            if (param._multiplicity() != null)
            {
                return multiplicityAccepts(param._multiplicity(), 0);
            }
            return true;
        }

        if (param._genericType() != null)
        {
            Type paramType = _GenericType.type(param._genericType());
            if (paramType != null && valueType != null && !_Type.subtypeOf(valueType, paramType, resolver))
            {
                return false;
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

    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    private static boolean multiplicityAccepts(org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.multiplicity.Multiplicity mult, int count)
    {
        if (!(mult instanceof org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.multiplicity.ConcreteMultiplicity cm))
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
