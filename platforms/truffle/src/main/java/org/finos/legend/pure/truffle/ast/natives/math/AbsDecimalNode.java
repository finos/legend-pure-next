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

@NodeInfo(shortName = "absDec")
public final class AbsDecimalNode extends PureNode
{
    private static final String SIG = "abs_Decimal_1__Decimal_1_";

    @Child
    private PureNode operand;

    public AbsDecimalNode(PureNode operand)
    {
        this.operand = operand;
    }

    @Override
    public Object executeGeneric(VirtualFrame frame)
    {
        Object v = operand.executeGeneric(frame);
        if (v instanceof meta.pure.metamodel.valuespecification.AtomicValue av)
        {
            v = av._value();
        }
        if (v instanceof BigDecimal bd)
        {
            return bd.abs();
        }
        BigDecimal bd = v instanceof Number n ? BigDecimal.valueOf(n.doubleValue()) : new BigDecimal(v.toString());
        return bd.abs();
    }
}
