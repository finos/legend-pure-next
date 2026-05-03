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

@NodeInfo(shortName = "indexOf")
public final class IndexOfNode extends PureNode
{
    private final String sig;

    @Child
    private PureNode strArg;

    @Child
    private PureNode searchArg;

    @Child
    private PureNode fromArg;

    public IndexOfNode(PureNode strArg, PureNode searchArg, PureNode fromArg, String sig)
    {
        this.strArg = strArg;
        this.searchArg = searchArg;
        this.fromArg = fromArg;
        this.sig = sig;
    }

    @Override
    public long executeLong(VirtualFrame frame)
    {
        String s = StringHelper.asString(strArg.executeGeneric(frame), sig);
        String search = StringHelper.asString(searchArg.executeGeneric(frame), sig);
        if (fromArg != null)
        {
            int from = (int) fromArg.executeLong(frame);
            return s.indexOf(search, from);
        }
        return s.indexOf(search);
    }

    @Override
    public Object executeGeneric(VirtualFrame frame)
    {
        return executeLong(frame);
    }
}
