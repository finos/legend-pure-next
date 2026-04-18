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
import meta.pure.metamodel.valuespecification.AtomicValue;
import org.finos.legend.pure.truffle.ast.PureNode;

import java.math.BigDecimal;

/**
 * Truffle node for {@code rem_Number_1__Number_1__Number_1_}.
 * If both operands are Long, returns {@code long % long};
 * otherwise uses BigDecimal remainder and returns the result as double.
 */
@NodeInfo(shortName = "remNum")
public final class RemNumberNode extends PureNode
{
    private static final String SIG = "rem_Number_1__Number_1__Number_1_";

    @Child
    private PureNode left;

    @Child
    private PureNode right;

    public RemNumberNode(PureNode left, PureNode right)
    {
        this.left = left;
        this.right = right;
    }

    @Override
    public Object executeGeneric(VirtualFrame frame)
    {
        Object rawA = left.executeGeneric(frame);
        Object rawB = right.executeGeneric(frame);
        Long la = asLongOrNull(rawA);
        Long lb = asLongOrNull(rawB);
        if (la != null && lb != null)
        {
            return la % lb;
        }
        double a = FloatHelper.asDouble(rawA, SIG);
        double b = FloatHelper.asDouble(rawB, SIG);
        return BigDecimal.valueOf(a).remainder(BigDecimal.valueOf(b)).doubleValue();
    }

    private static Long asLongOrNull(Object v)
    {
        if (v instanceof Long l)
        {
            return l;
        }
        if (v instanceof AtomicValue av && av._value() instanceof Long l)
        {
            return l;
        }
        return null;
    }
}
