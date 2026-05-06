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

package org.finos.legend.pure.truffle.ast.natives.math;

import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.NodeInfo;
import org.finos.legend.pure.truffle.ast.PureNode;

import java.util.function.DoubleBinaryOperator;

/**
 * Shared Truffle node for generic two-argument {@code Number[1], Number[1] -> Number[1]}
 * operations (plus_Number, minus_Number, times_Number). Both operands are
 * coerced to {@code double} via {@link FloatHelper#asDouble}.
 */
@NodeInfo(shortName = "binaryNum")
public final class BinaryNumberNode extends PureNode
{
    private final String signature;
    private final DoubleBinaryOperator op;

    @Child
    private PureNode left;

    @Child
    private PureNode right;

    public BinaryNumberNode(PureNode left, PureNode right, String signature, DoubleBinaryOperator op)
    {
        this.left = left;
        this.right = right;
        this.signature = signature;
        this.op = op;
    }

    @Override
    public Object executeGeneric(VirtualFrame frame)
    {
        Object rawA = left.executeGeneric(frame);
        Object rawB = right.executeGeneric(frame);
        // BigDecimal path is routed through a @TruffleBoundary helper so
        // Graal's inliner stops at the boundary instead of trying to inline
        // through BigDecimal.add/subtract/multiply. Those JDK methods reach
        // BigInteger arithmetic where `squareKaratsuba` is genuinely recursive
        // (`squareKaratsuba` → `square` → `squareKaratsuba`) — without the
        // boundary, Graal walks ~490 levels deep before bailing out with
        // `PermanentBailoutException: Too deep inlining`. Even with Double
        // operands at runtime, Graal compiles the BigDecimal branch
        // statically, so the boundary must guard the call site.
        if (rawA instanceof java.math.BigDecimal || rawB instanceof java.math.BigDecimal)
        {
            return bigDecimalOp(rawA, rawB);
        }
        double a = FloatHelper.asDouble(rawA, signature);
        double b = FloatHelper.asDouble(rawB, signature);
        return op.applyAsDouble(a, b);
    }

    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    private Object bigDecimalOp(Object rawA, Object rawB)
    {
        java.math.BigDecimal bdA = toBigDecimal(rawA);
        java.math.BigDecimal bdB = toBigDecimal(rawB);
        if (signature.contains("times"))
        {
            return bdA.multiply(bdB);
        }
        if (signature.contains("plus"))
        {
            return bdA.add(bdB);
        }
        if (signature.contains("minus"))
        {
            return bdA.subtract(bdB);
        }
        return op.applyAsDouble(bdA.doubleValue(), bdB.doubleValue());
    }

    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    private static java.math.BigDecimal toBigDecimal(Object v)
    {
        if (v instanceof java.math.BigDecimal bd) return bd;
        if (v instanceof Long l) return java.math.BigDecimal.valueOf(l);
        if (v instanceof Double d) return new java.math.BigDecimal(d.toString());
        if (v instanceof Number n) return new java.math.BigDecimal(n.toString());
        return new java.math.BigDecimal(v.toString());
    }
}
