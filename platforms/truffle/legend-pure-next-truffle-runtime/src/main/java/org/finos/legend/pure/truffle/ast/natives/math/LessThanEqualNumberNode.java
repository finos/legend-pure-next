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

@NodeInfo(shortName = "lessThanEqualNum")
public final class LessThanEqualNumberNode extends PureNode
{
    private static final String SIG = "lessThanEqual_Number_1__Number_1__Boolean_1_";

    @Child
    private PureNode left;

    @Child
    private PureNode right;

    public LessThanEqualNumberNode(PureNode left, PureNode right)
    {
        this.left = left;
        this.right = right;
    }

    @Override
    public Object executeGeneric(VirtualFrame frame)
    {
        double a = FloatHelper.asDouble(left.executeGeneric(frame), SIG);
        double b = FloatHelper.asDouble(right.executeGeneric(frame), SIG);
        return a <= b;
    }
}
