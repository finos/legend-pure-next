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

@NodeInfo(shortName = "divideDec")
public final class DivideDecimalNode extends PureNode
{
    private static final String SIG = "divide_Decimal_1__Decimal_1__Integer_1__Decimal_1_";

    @Child
    private PureNode dividend;

    @Child
    private PureNode divisor;

    @Child
    private PureNode scale;

    public DivideDecimalNode(PureNode dividend, PureNode divisor, PureNode scale)
    {
        this.dividend = dividend;
        this.divisor = divisor;
        this.scale = scale;
    }

    @Override
    public Object executeGeneric(VirtualFrame frame)
    {
        Object a = dividend.executeGeneric(frame);
        Object b = divisor.executeGeneric(frame);
        int s = (int) IntegerHelper.asLong(scale.executeGeneric(frame), SIG);
        return toBigDecimal(a).divide(toBigDecimal(b), s, RoundingMode.HALF_UP);
    }

    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    private static BigDecimal toBigDecimal(Object v)
    {
        if (v instanceof BigDecimal d)
        {
            return d;
        }
        if (v instanceof Number n)
        {
            return BigDecimal.valueOf(n.doubleValue());
        }
        return new BigDecimal(v.toString());
    }
}
