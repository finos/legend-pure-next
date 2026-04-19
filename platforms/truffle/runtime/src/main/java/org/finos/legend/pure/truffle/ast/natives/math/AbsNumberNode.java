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

/**
 * Truffle node for {@code abs_Number_1__Number_1_}.
 * If the operand is an Integer (Long), returns {@code Math.abs(long)};
 * otherwise returns {@code Math.abs(double)}.
 */
@NodeInfo(shortName = "absNum")
public final class AbsNumberNode extends PureNode
{
    private static final String SIG = "abs_Number_1__Number_1_";

    @Child
    private PureNode operand;

    public AbsNumberNode(PureNode operand)
    {
        this.operand = operand;
    }

    @Override
    public Object executeGeneric(VirtualFrame frame)
    {
        Object v = operand.executeGeneric(frame);
        if (v instanceof Long l)
        {
            return Math.abs(l);
        }
        return Math.abs(FloatHelper.asDouble(v, SIG));
    }
}
