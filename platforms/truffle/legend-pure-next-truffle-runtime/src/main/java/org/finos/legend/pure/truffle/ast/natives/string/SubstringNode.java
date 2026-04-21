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

package org.finos.legend.pure.truffle.ast.natives.string;

import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.NodeInfo;
import org.finos.legend.pure.truffle.ast.PureNode;
import org.finos.legend.pure.truffle.ast.natives.math.IntegerHelper;

@NodeInfo(shortName = "substring")
public final class SubstringNode extends PureNode
{
    private final String sig;

    @Child
    private PureNode strArg;

    @Child
    private PureNode startArg;

    @Child
    private PureNode endArg;

    public SubstringNode(PureNode strArg, PureNode startArg, PureNode endArg, String sig)
    {
        this.strArg = strArg;
        this.startArg = startArg;
        this.endArg = endArg;
        this.sig = sig;
    }

    @Override
    public Object executeGeneric(VirtualFrame frame)
    {
        String s = StringHelper.asString(strArg.executeGeneric(frame), sig);
        int start = (int) IntegerHelper.asLong(startArg.executeGeneric(frame), sig);
        if (endArg != null)
        {
            int end = (int) IntegerHelper.asLong(endArg.executeGeneric(frame), sig);
            return s.substring(start, end);
        }
        return s.substring(start);
    }
}
