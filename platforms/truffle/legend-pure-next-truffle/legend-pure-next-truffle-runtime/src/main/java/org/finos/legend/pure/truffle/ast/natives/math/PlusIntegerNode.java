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
 * Specialized Truffle node for {@code plus_Integer_1__Integer_1__Integer_1_}.
 *
 * <p>The long happy path unwraps both children directly as raw Long
 * values (see {@link IntegerHelper#asLong}).</p>
 */
@NodeInfo(shortName = "plusInt")
public final class PlusIntegerNode extends PureNode
{
    private static final String SIG = "plus_Integer_1__Integer_1__Integer_1_";

    @Child
    private PureNode left;

    @Child
    private PureNode right;

    public PlusIntegerNode(PureNode left, PureNode right)
    {
        this.left = left;
        this.right = right;
    }

    @Override
    public long executeLong(VirtualFrame frame)
    {
        return left.executeLong(frame) + right.executeLong(frame);
    }

    @Override
    public Object executeGeneric(VirtualFrame frame)
    {
        return executeLong(frame);
    }
}
