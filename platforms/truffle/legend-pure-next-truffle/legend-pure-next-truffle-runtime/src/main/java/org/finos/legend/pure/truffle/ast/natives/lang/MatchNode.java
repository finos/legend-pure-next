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
import org.finos.legend.pure.truffle.ast.PureNode;
import org.finos.legend.pure.truffle.ast.RawLambdaCallNode;
import org.finos.legend.pure.truffle.ast.natives.collection.CollectionHelper;
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

    @com.oracle.truffle.api.CompilerDirectives.CompilationFinal
    private org.finos.legend.pure.truffle.PureContext cachedContext;

    @Override
    public Object executeGeneric(VirtualFrame frame)
    {
        Object[] values = evaluateChildren(frame);
        org.finos.legend.pure.truffle.PureContext ctx = cachedContext;
        if (ctx == null)
        {
            // First-call population — flag the field as no-longer-default so
            // PE constant-folds subsequent reads.
            com.oracle.truffle.api.CompilerDirectives.transferToInterpreterAndInvalidate();
            ctx = getContext();
            cachedContext = ctx;
        }
        return invokeMatch(values, ctx, matchCallNode);
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

        Object valueType = getRawValueType(value, context);
        int valueCount = getRawValueCount(value);

        // Iterate over match functions
        int fnCount = CollectionHelper.size(matchFns);
        for (int i = 0; i < fnCount; i++)
        {
            Object mfRaw = CollectionHelper.at(matchFns, i);
            // Unwrap AtomicValue wrapper — FlatBuffer-based collections may
            // deliver match lambdas still wrapped in their VS envelope.
            if (org.finos.legend.pure.truffle.runtime.dynobj.PureObj.pureTypeIs(mfRaw,
                    "meta::pure::metamodel::valuespecification::AtomicValue"))
            {
                Object inner = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.read(mfRaw, "value");
                if (inner != null)
                {
                    mfRaw = inner;
                }
            }
            // Extract the FunctionDefinition for type matching, but keep the
            // original (possibly RawClosure) for invocation so captured open
            // variables are preserved.
            Object fd;
            if (mfRaw instanceof RawClosure rc)
            {
                fd = rc.lambda();
            }
            else if (org.finos.legend.pure.truffle.runtime.dynobj.PureObj.isType(mfRaw,
                    "meta::pure::metamodel::function::FunctionDefinition", resolver))
            {
                fd = mfRaw;
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
            Object fdParams = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.read(fd, "parameters");
            if (values.length > 2
                    && fdParams instanceof org.finos.legend.pure.truffle.types.PureSequence fdParamSeq
                    && fdParamSeq.size() >= 2)
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
        String vtPath = valueType != null
                ? org.finos.legend.pure.truffle.runtime.helper._PackageableElement.path(valueType, resolver)
                : "n/a";
        if (vtPath == null) vtPath = "n/a";
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
    private static Object getRawValueType(Object value,
                                          org.finos.legend.pure.truffle.PureContext context)
    {
        TruffleMetadataAccess resolver = context.resolver();
        if (value == null || (value instanceof org.finos.legend.pure.truffle.types.PureSequence ps && ps.isEmpty()))
        {
            return resolver.getElement("meta::pure::metamodel::type::Nil");
        }
        if (value instanceof org.finos.legend.pure.truffle.types.PureSequence seq)
        {
            if (seq.isEmpty())
            {
                return resolver.getElement("meta::pure::metamodel::type::Nil");
            }
            // Compute the most common type across all elements
            java.util.List<Object> types = new java.util.ArrayList<>();
            for (int i = 0; i < seq.size(); i++)
            {
                Object t = getRawValueType(seq.getBoxed(i), context);
                if (t != null)
                {
                    types.add(t);
                }
            }
            if (types.isEmpty())
            {
                return resolver.getElement("meta::pure::metamodel::type::Nil");
            }
            return org.finos.legend.pure.truffle.runtime.helper._Type.findCommonType(types, false, resolver);
        }
        // Pure metamodel object (any subtype of Any) — read CGT via context
        if (org.finos.legend.pure.truffle.runtime.dynobj.PureObj.pureTypeOf(value) != null)
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
        if (value instanceof org.finos.legend.pure.truffle.types.PureDate.StrictDate)
        {
            return resolver.getElement("meta::pure::metamodel::type::primitives::StrictDate");
        }
        if (value instanceof org.finos.legend.pure.truffle.types.PureDate.DateTime)
        {
            return resolver.getElement("meta::pure::metamodel::type::primitives::DateTime");
        }
        if (value instanceof org.finos.legend.pure.truffle.types.PureDate)
        {
            return resolver.getElement("meta::pure::metamodel::type::primitives::Date");
        }
        if (value instanceof String s)
        {
            // Detect date strings by format (fallback for dates not yet wrapped in PureDate)
            if (org.finos.legend.pure.truffle.ast.natives.string.ToStringNode.isDateString(s))
            {
                if (s.contains("T"))
                {
                    return resolver.getElement("meta::pure::metamodel::type::primitives::DateTime");
                }
                return resolver.getElement("meta::pure::metamodel::type::primitives::Date");
            }
            // Detect enum value strings
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
            return resolver.getElement("meta::pure::metamodel::type::primitives::String");
        }
        if (value instanceof java.math.BigDecimal)
        {
            return resolver.getElement("meta::pure::metamodel::type::primitives::Decimal");
        }
        return resolver.getElement("meta::pure::metamodel::type::Any");
    }

    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    private static boolean matchesBranch(Object fd,
                                         Object valueType,
                                         int valueCount,
                                         org.finos.legend.pure.truffle.runtime.TruffleMetadataAccess resolver)
    {
        Object paramsObj = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.read(fd, "parameters");
        if (!(paramsObj instanceof org.finos.legend.pure.truffle.types.PureSequence params) || params.isEmpty())
        {
            return true;
        }

        Object param = params.getBoxed(0);
        if (!org.finos.legend.pure.truffle.runtime.dynobj.PureObj.pureTypeIs(param,
                "meta::pure::metamodel::valuespecification::VariableExpression"))
        {
            throw new RuntimeException("Error");
        }

        Object paramMul = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.read(param, "multiplicity");
        // For null/empty values (count=0), skip type check — Nil is compatible with everything.
        // Only check multiplicity.
        if (valueCount == 0)
        {
            if (paramMul != null)
            {
                return multiplicityAccepts(paramMul, 0);
            }
            return true;
        }

        Object paramGT = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.read(param, "genericType");
        if (paramGT != null)
        {
            Object paramType = _GenericType.type(paramGT);
            if (paramType != null && valueType != null && !_Type.subtypeOf(valueType, paramType, resolver))
            {
                return false;
            }
        }

        if (paramMul != null)
        {
            if (!multiplicityAccepts(paramMul, valueCount))
            {
                return false;
            }
        }

        return true;
    }

    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    private static boolean multiplicityAccepts(Object mult, int count)
    {
        if (mult == null)
        {
            return true;
        }
        // Bound-driven — covers ConcreteMultiplicity and all its Pure subtypes
        // (UserDefinedAdHocMultiplicity, InferredAdHocMultiplicity, …) without
        // a resolver-backed isType check. Param-typed multiplicities have neither
        // lowerBound nor upperBound, so this returns true for them — matches the
        // original `!instanceof ConcreteMultiplicity → return true` shortcut.
        Object lb = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.read(mult, "lowerBound");
        Object ub = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.read(mult, "upperBound");
        if (lb == null && ub == null)
        {
            return true;
        }

        long lower = 0;
        if (lb != null)
        {
            Object v = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.read(lb, "value");
            if (v instanceof Number n) lower = n.longValue();
        }

        long upper = -1;
        if (ub != null)
        {
            Object v = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.read(ub, "value");
            if (v instanceof Number n) upper = n.longValue();
        }

        if (count < lower)
        {
            return false;
        }
        return upper == -1 || count <= upper;
    }
}
