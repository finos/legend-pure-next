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

import java.math.BigDecimal;
import java.util.function.BinaryOperator;

/**
 * Shared Truffle node for two-argument Decimal operations
 * (plus_Decimal, minus_Decimal, times_Decimal). Operates on
 * {@link BigDecimal} for full precision.
 */
@NodeInfo(shortName = "decBinary")
public final class DecimalBinaryNode extends PureNode
{
    private final String signature;
    private final BinaryOperator<BigDecimal> op;

    @Child
    private PureNode left;

    @Child
    private PureNode right;

    public DecimalBinaryNode(PureNode left, PureNode right, String signature, BinaryOperator<BigDecimal> op)
    {
        this.left = left;
        this.right = right;
        this.signature = signature;
        this.op = op;
    }

    @Override
    public Object executeGeneric(VirtualFrame frame)
    {
        BigDecimal a = asBigDecimal(left.executeGeneric(frame));
        BigDecimal b = asBigDecimal(right.executeGeneric(frame));
        return op.apply(a, b);
    }

    private BigDecimal asBigDecimal(Object v)
    {
        if (v instanceof meta.pure.metamodel.valuespecification.AtomicValue av)
        {
            v = av._value();
        }
        if (v instanceof BigDecimal bd)
        {
            return bd;
        }
        if (v instanceof Number n)
        {
            return BigDecimal.valueOf(n.doubleValue());
        }
        return new BigDecimal(v.toString());
    }
}
