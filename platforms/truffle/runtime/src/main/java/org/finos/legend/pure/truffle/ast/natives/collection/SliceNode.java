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
import org.finos.legend.pure.truffle.ast.natives.math.IntegerHelper;
import org.finos.legend.pure.truffle.types.ObjectSequence;
import org.finos.legend.pure.truffle.types.PureNull;

import java.util.Arrays;

/**
 * {@code slice(T[*], Integer[1], Integer[1]) : T[*]} -- elements in {@code [from, to)}.
 * Matches the bridged native's clamping rules.
 */
@NodeInfo(shortName = "slice")
public final class SliceNode extends PureNode
{
    private static final String SIG = "slice_T_MANY__Integer_1__Integer_1__T_MANY_";

    @Child
    private PureNode collection;

    @Child
    private PureNode fromNode;

    @Child
    private PureNode toNode;

    public SliceNode(PureNode collection, PureNode fromNode, PureNode toNode)
    {
        this.collection = collection;
        this.fromNode = fromNode;
        this.toNode = toNode;
    }

    @Override
    public Object executeGeneric(VirtualFrame frame)
    {
        Object col = collection.executeGeneric(frame);
        long from = IntegerHelper.asLong(fromNode.executeGeneric(frame), SIG);
        long to = IntegerHelper.asLong(toNode.executeGeneric(frame), SIG);
        Object[] arr = CollectionHelper.toArray(col);
        int fromI = (int) Math.max(0, from);
        int toI = (int) Math.min(arr.length, to);
        fromI = Math.min(fromI, arr.length);
        toI = Math.max(fromI, toI);
        if (fromI >= toI)
        {
            return PureNull.INSTANCE;
        }
        return new ObjectSequence(Arrays.copyOfRange(arr, fromI, toI));
    }
}
