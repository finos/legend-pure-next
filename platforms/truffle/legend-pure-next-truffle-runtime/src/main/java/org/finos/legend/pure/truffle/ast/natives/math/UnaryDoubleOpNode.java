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

import java.util.function.DoubleUnaryOperator;

/**
 * Shared Truffle node for unary {@code Number[1] -> Float[1]} math functions
 * (sin, cos, tan, cot, asin, acos, atan, sqrt, cbrt, exp, log, log10).
 *
 * <p>Each instance captures a {@link DoubleUnaryOperator} that performs the
 * actual computation.  The operator is a compile-time constant from the
 * factory so the JIT can inline it.</p>
 */
@NodeInfo(shortName = "unaryDoubleOp")
public final class UnaryDoubleOpNode extends PureNode
{
    private final String signature;
    private final DoubleUnaryOperator op;

    @Child
    private PureNode operand;

    public UnaryDoubleOpNode(PureNode operand, String signature, DoubleUnaryOperator op)
    {
        this.operand = operand;
        this.signature = signature;
        this.op = op;
    }

    @Override
    public double executeDouble(VirtualFrame frame)
    {
        return op.applyAsDouble(operand.executeDouble(frame));
    }

    @Override
    public Object executeGeneric(VirtualFrame frame)
    {
        return executeDouble(frame);
    }
}
