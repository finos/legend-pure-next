// Copyright 2024 Goldman Sachs
// ©2026 JP Morgan Chase & Co. All rights reserved.
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

package org.finos.legend.pure.truffle.execution.natives.lang;

import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import com.oracle.truffle.api.nodes.NodeInfo;
import org.finos.legend.pure.truffle.execution.ast.PureNode;
import org.finos.legend.pure.truffle.execution.types.RawClosure;
import org.finos.legend.pure.truffle.execution.ast.call.RawLambdaCallNode;
import org.finos.legend.pure.truffle.execution.natives.collection.CollectionHelper;
import org.finos.legend.pure.truffle.compiler.module.MetadataAccess;
import org.finos.legend.pure.truffle.compiler.helper._GenericType;
import org.finos.legend.pure.truffle.compiler.helper._Type;

/**
 * {@code match(Any[m], Function<{T[n]->P[o]}>[*]) : P[o]} — type-based
 * function dispatch. Iterates over match functions, checking parameter types
 * against the value's runtime type, invoking the first match. Has a 3-arg
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
    private org.finos.legend.pure.truffle.execution.PureContext cachedContext;

    @Override
    public Object executeGeneric(VirtualFrame frame)
    {
        Object[] values = evaluateChildren(frame);
        org.finos.legend.pure.truffle.execution.PureContext ctx = cachedContext;
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
    private static Object invokeMatch(Object[] values, org.finos.legend.pure.truffle.execution.PureContext context, RawLambdaCallNode matchCallNode)
    {
        MetadataAccess resolver = context.resolver();
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
            // RawClosure is the overwhelmingly common case — short-circuit it
            // with a single `instanceof` before doing any pureTypeIs/pureTypeOf
            // work (JFR: the per-iteration type probing showed up on warm wall
            // before this short-circuit).
            Object fd;
            if (mfRaw instanceof RawClosure rc)
            {
                fd = rc.lambda();
            }
            else
            {
                // Unwrap AtomicValue wrapper — FlatBuffer-based collections may
                // deliver match lambdas still wrapped in their VS envelope.
                if (org.finos.legend.pure.truffle.compiler.helper._Any.pureTypeIs(mfRaw,
                        "meta::pure::metamodel::valuespecification::AtomicValue"))
                {
                    Object inner = org.finos.legend.pure.truffle.compiler.helper._Any.readBySlot(mfRaw, SLOT_VALUE);
                    if (inner != null)
                    {
                        mfRaw = inner;
                    }
                }
                if (mfRaw instanceof RawClosure rc2)
                {
                    fd = rc2.lambda();
                }
                else if (org.finos.legend.pure.truffle.compiler.helper._Any.isType(mfRaw,
                        "meta::pure::metamodel::function::FunctionDefinition", resolver))
                {
                    fd = mfRaw;
                }
                else
                {
                    throw new RuntimeException("Not possible");
                }
            }

            if (!matchesBranch(fd, valueType, valueCount, resolver))
            {
                continue;
            }

            // Build args: [value, optionalExtra]
            Object[] args;
            Object fdParams = org.finos.legend.pure.truffle.compiler.helper._Any.readBySlot(fd, SLOT_PARAMETERS);
            if (values.length > 2
                    && fdParams instanceof org.finos.legend.pure.truffle.execution.types.PureSequence fdParamSeq
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
                ? org.finos.legend.pure.truffle.compiler.helper._PackageableElement.path(valueType, resolver)
                : "n/a";
        if (vtPath == null) vtPath = "n/a";
        Object cgt = context.classifierGenericType(value);
        StringBuilder dbg = new StringBuilder();
        for (int di = 0; di < fnCount; di++)
        {
            Object mfRawD = CollectionHelper.at(matchFns, di);
            if (org.finos.legend.pure.truffle.compiler.helper._Any.pureTypeIs(mfRawD,
                    "meta::pure::metamodel::valuespecification::AtomicValue"))
            {
                Object inner = org.finos.legend.pure.truffle.compiler.helper._Any.readBySlot(mfRawD, SLOT_VALUE);
                if (inner != null) mfRawD = inner;
            }
            Object fdD = mfRawD instanceof RawClosure rcD ? rcD.lambda() : mfRawD;
            Object pParams = org.finos.legend.pure.truffle.compiler.helper._Any.readBySlot(fdD, SLOT_PARAMETERS);
            dbg.append("\n  branch ").append(di).append(": params=").append(pParams);
            if (pParams instanceof org.finos.legend.pure.truffle.execution.types.PureSequence ps && !ps.isEmpty())
            {
                Object p = ps.getBoxed(0);
                Object mul = org.finos.legend.pure.truffle.compiler.helper._Any.readBySlot(p, SLOT_MULTIPLICITY);
                Object lb = mul != null ? org.finos.legend.pure.truffle.compiler.helper._Any.readBySlot(mul, SLOT_LOWER_BOUND) : null;
                Object ub = mul != null ? org.finos.legend.pure.truffle.compiler.helper._Any.readBySlot(mul, SLOT_UPPER_BOUND) : null;
                Object lbV = lb != null ? org.finos.legend.pure.truffle.compiler.helper._Any.readBySlot(lb, SLOT_VALUE) : null;
                Object ubV = ub != null ? org.finos.legend.pure.truffle.compiler.helper._Any.readBySlot(ub, SLOT_VALUE) : null;
                dbg.append(" mul=").append(mul).append(" lb.value=").append(lbV).append(" ub.value=").append(ubV);
            }
        }
        throw new RuntimeException("No match function matched the value: " + value
                + " [valueType=" + vtPath + ", cgt=" + (cgt == null ? "NULL" : cgt.getClass().getName()) + ", fnCount=" + fnCount + "]" + dbg);
    }

    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    private static int getRawValueCount(Object value)
    {
        if (value == null || (value instanceof org.finos.legend.pure.truffle.execution.types.PureSequence ps && ps.isEmpty()))
        {
            return 0;
        }
        return CollectionHelper.size(value);
    }

    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    private static Object getRawValueType(Object value,
                                          org.finos.legend.pure.truffle.execution.PureContext context)
    {
        return org.finos.legend.pure.truffle.execution.natives.meta.MetaHelper.getRawValueType(value, context.resolver());
    }

    private static final int SLOT_PARAMETERS =
            org.finos.legend.pure.truffle.compiler.module.PureClassRegistry.globalSlot("parameters");
    private static final int SLOT_MULTIPLICITY =
            org.finos.legend.pure.truffle.compiler.module.PureClassRegistry.globalSlot("multiplicity");
    private static final int SLOT_GENERIC_TYPE =
            org.finos.legend.pure.truffle.compiler.module.PureClassRegistry.globalSlot("genericType");
    private static final int SLOT_LOWER_BOUND =
            org.finos.legend.pure.truffle.compiler.module.PureClassRegistry.globalSlot("lowerBound");
    private static final int SLOT_UPPER_BOUND =
            org.finos.legend.pure.truffle.compiler.module.PureClassRegistry.globalSlot("upperBound");
    private static final int SLOT_VALUE =
            org.finos.legend.pure.truffle.compiler.module.PureClassRegistry.globalSlot("value");

    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    private static boolean matchesBranch(Object fd,
                                         Object valueType,
                                         int valueCount,
                                         MetadataAccess resolver)
    {
        Object paramsObj = fd instanceof org.finos.legend.pure.truffle.execution.PureDynamicObject fdPdo
                ? fdPdo.readSlot(SLOT_PARAMETERS)
                : org.finos.legend.pure.truffle.compiler.helper._Any.readBySlot(fd, SLOT_PARAMETERS);
        if (!(paramsObj instanceof org.finos.legend.pure.truffle.execution.types.PureSequence params) || params.isEmpty())
        {
            return true;
        }

        Object param = params.getBoxed(0);
        if (!org.finos.legend.pure.truffle.compiler.helper._Any.pureTypeIs(param,
                "meta::pure::metamodel::valuespecification::VariableExpression"))
        {
            throw new RuntimeException("Error");
        }

        Object paramMul = param instanceof org.finos.legend.pure.truffle.execution.PureDynamicObject pPdo
                ? pPdo.readSlot(SLOT_MULTIPLICITY)
                : org.finos.legend.pure.truffle.compiler.helper._Any.readBySlot(param, SLOT_MULTIPLICITY);
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

        Object paramGT = param instanceof org.finos.legend.pure.truffle.execution.PureDynamicObject pPdo2
                ? pPdo2.readSlot(SLOT_GENERIC_TYPE)
                : org.finos.legend.pure.truffle.compiler.helper._Any.readBySlot(param, SLOT_GENERIC_TYPE);
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
        boolean multIsPdo = mult instanceof org.finos.legend.pure.truffle.execution.PureDynamicObject;
        Object lb = multIsPdo
                ? ((org.finos.legend.pure.truffle.execution.PureDynamicObject) mult).readSlot(SLOT_LOWER_BOUND)
                : org.finos.legend.pure.truffle.compiler.helper._Any.readBySlot(mult, SLOT_LOWER_BOUND);
        Object ub = multIsPdo
                ? ((org.finos.legend.pure.truffle.execution.PureDynamicObject) mult).readSlot(SLOT_UPPER_BOUND)
                : org.finos.legend.pure.truffle.compiler.helper._Any.readBySlot(mult, SLOT_UPPER_BOUND);
        if (lb == null && ub == null)
        {
            return true;
        }

        long lower = 0;
        if (lb != null)
        {
            Object v = lb instanceof org.finos.legend.pure.truffle.execution.PureDynamicObject lbPdo
                    ? lbPdo.readSlot(SLOT_VALUE)
                    : org.finos.legend.pure.truffle.compiler.helper._Any.readBySlot(lb, SLOT_VALUE);
            if (v instanceof Number n) lower = n.longValue();
        }

        long upper = -1;
        if (ub != null)
        {
            Object v = ub instanceof org.finos.legend.pure.truffle.execution.PureDynamicObject ubPdo
                    ? ubPdo.readSlot(SLOT_VALUE)
                    : org.finos.legend.pure.truffle.compiler.helper._Any.readBySlot(ub, SLOT_VALUE);
            if (v instanceof Number n) upper = n.longValue();
        }

        if (count < lower)
        {
            return false;
        }
        return upper == -1 || count <= upper;
    }
}
