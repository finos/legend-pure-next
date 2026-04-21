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

package org.finos.legend.pure.truffle.ast;

import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.NodeInfo;

/**
 * Executes a sequence of expressions, returns the last result.
 * Used for function bodies with multiple statements.
 */
@NodeInfo(shortName = "seq")
public final class SequenceNode extends PureNode
{
    @Children
    private final Node[] exprs;

    public SequenceNode(PureNode[] exprs)
    {
        this.exprs = java.util.Arrays.copyOf(exprs, exprs.length, Node[].class);
    }

    @Override
    @ExplodeLoop
    public Object executeGeneric(VirtualFrame frame)
    {
        Object result = org.finos.legend.pure.truffle.types.PureSequence.EMPTY;
        for (int i = 0; i < exprs.length; i++)
        {
            result = ((PureNode) exprs[i]).executeGeneric(frame);
        }
        return result;
    }
}
