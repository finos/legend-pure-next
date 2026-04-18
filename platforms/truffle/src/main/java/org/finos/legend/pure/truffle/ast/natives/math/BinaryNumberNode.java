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
        double a = FloatHelper.asDouble(left.executeGeneric(frame), signature);
        double b = FloatHelper.asDouble(right.executeGeneric(frame), signature);
        return op.applyAsDouble(a, b);
    }
}
