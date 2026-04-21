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
import java.math.RoundingMode;

/**
 * Truffle node for {@code round_Number_1__Integer_1_}.
 * Uses HALF_EVEN rounding (banker's rounding) to match the interpreter.
 */
@NodeInfo(shortName = "roundNum")
public final class RoundNumberNode extends PureNode
{
    private static final String SIG = "round_Number_1__Integer_1_";

    @Child
    private PureNode operand;

    public RoundNumberNode(PureNode operand)
    {
        this.operand = operand;
    }

    @Override
    public Object executeGeneric(VirtualFrame frame)
    {
        double v = FloatHelper.asDouble(operand.executeGeneric(frame), SIG);
        return BigDecimal.valueOf(v).setScale(0, RoundingMode.HALF_EVEN).longValue();
    }
}
