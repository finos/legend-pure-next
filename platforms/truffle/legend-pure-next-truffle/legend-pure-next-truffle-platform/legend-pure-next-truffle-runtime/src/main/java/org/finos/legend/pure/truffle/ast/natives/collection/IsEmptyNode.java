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

package org.finos.legend.pure.truffle.ast.natives.collection;

import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.NodeInfo;
import org.finos.legend.pure.truffle.ast.PureNode;

@NodeInfo(shortName = "isEmpty")
public final class IsEmptyNode extends PureNode
{
    @Child
    private PureNode arg;

    public IsEmptyNode(PureNode arg)
    {
        this.arg = arg;
    }

    @Override
    public Object executeGeneric(VirtualFrame frame)
    {
        return CollectionHelper.isEmpty(arg.executeGeneric(frame));
    }
}
