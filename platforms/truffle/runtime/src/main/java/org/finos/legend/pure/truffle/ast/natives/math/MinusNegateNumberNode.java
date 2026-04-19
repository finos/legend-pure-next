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
import org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.valuespecification.AtomicValue;
import org.finos.legend.pure.truffle.ast.PureNode;

/**
 * Single-argument negate for {@code minus_Number_1__Number_1_}.
 * If the operand is an Integer (Long), negates as long; otherwise negates as double.
 */
@NodeInfo(shortName = "negateNum")
public final class MinusNegateNumberNode extends PureNode
{
    private static final String SIG = "minus_Number_1__Number_1_";

    @Child
    private PureNode operand;

    public MinusNegateNumberNode(PureNode operand)
    {
        this.operand = operand;
    }

    @Override
    public Object executeGeneric(VirtualFrame frame)
    {
        Object v = operand.executeGeneric(frame);
        if (v instanceof Long l)
        {
            return -l;
        }
        if (v instanceof AtomicValue av && av._value() instanceof Long l)
        {
            return -l;
        }
        return -FloatHelper.asDouble(v, SIG);
    }
}
