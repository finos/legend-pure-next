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
import com.oracle.truffle.api.nodes.NodeInfo;
import org.finos.legend.pure.truffle.types.ObjectSequence;
import org.finos.legend.pure.truffle.types.PureSequence;

/**
 * Evaluates child nodes and produces a raw PureSequence/ObjectSequence
 * (or PureSequence.EMPTY for empty, or a single scalar for size=1).
 */
@NodeInfo(shortName = "collection")
public final class RawCollectionNode extends PureNode
{
    @Children
    private PureNode[] children;

    public RawCollectionNode(PureNode[] children)
    {
        this.children = children;
    }

    @Override
    public Object executeGeneric(VirtualFrame frame)
    {
        if (children.length == 0)
        {
            return PureSequence.EMPTY;
        }
        if (children.length == 1)
        {
            return children[0].executeGeneric(frame);
        }
        Object[] values = new Object[children.length];
        for (int i = 0; i < children.length; i++)
        {
            values[i] = children[i].executeGeneric(frame);
        }
        return new ObjectSequence(values);
    }
}
