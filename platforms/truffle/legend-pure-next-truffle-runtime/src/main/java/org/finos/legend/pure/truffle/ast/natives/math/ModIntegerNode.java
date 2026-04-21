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
 * Truffle node for {@code mod_Integer_1__Integer_1__Integer_1_}.
 * Computes {@code a % b}, adjusting for negative results (mathematical mod).
 */
@NodeInfo(shortName = "modInt")
public final class ModIntegerNode extends PureNode
{
    private static final String SIG = "mod_Integer_1__Integer_1__Integer_1_";

    @Child
    private PureNode left;

    @Child
    private PureNode right;

    public ModIntegerNode(PureNode left, PureNode right)
    {
        this.left = left;
        this.right = right;
    }

    @Override
    public Object executeGeneric(VirtualFrame frame)
    {
        long a = IntegerHelper.asLong(left.executeGeneric(frame), SIG);
        long b = IntegerHelper.asLong(right.executeGeneric(frame), SIG);
        long result = a % b;
        if (result < 0)
        {
            result += Math.abs(b);
        }
        return result;
    }
}
