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

@NodeInfo(shortName = "roundDec")
public final class RoundDecimalNode extends PureNode
{
    private static final String SIG = "round_Decimal_1__Integer_1__Decimal_1_";

    @Child
    private PureNode value;

    @Child
    private PureNode scale;

    public RoundDecimalNode(PureNode value, PureNode scale)
    {
        this.value = value;
        this.scale = scale;
    }

    @Override
    public Object executeGeneric(VirtualFrame frame)
    {
        Object v = value.executeGeneric(frame);
        int s = (int) IntegerHelper.asLong(scale.executeGeneric(frame), SIG);
        if (v instanceof org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.valuespecification.AtomicValue av)
        {
            v = av._value();
        }
        BigDecimal bd;
        if (v instanceof BigDecimal d)
        {
            bd = d;
        }
        else if (v instanceof Number n)
        {
            bd = BigDecimal.valueOf(n.doubleValue());
        }
        else
        {
            bd = new BigDecimal(v.toString());
        }
        return bd.setScale(s, RoundingMode.HALF_UP);
    }
}
