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
 * Truffle node for {@code round_Float_1__Integer_1__Float_1_}.
 * Rounds a float to the given number of decimal places using HALF_UP.
 */
@NodeInfo(shortName = "roundFloat")
public final class RoundFloatNode extends PureNode
{
    private static final String SIG = "round_Float_1__Integer_1__Float_1_";

    @Child
    private PureNode value;

    @Child
    private PureNode scale;

    public RoundFloatNode(PureNode value, PureNode scale)
    {
        this.value = value;
        this.scale = scale;
    }

    @Override
    public double executeDouble(VirtualFrame frame)
    {
        double v = value.executeDouble(frame);
        int s = (int) scale.executeLong(frame);
        return doRound(v, s);
    }

    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    private static double doRound(double v, int s)
    {
        return BigDecimal.valueOf(v).setScale(s, RoundingMode.HALF_UP).doubleValue();
    }

    @Override
    public Object executeGeneric(VirtualFrame frame)
    {
        return executeDouble(frame);
    }
}
