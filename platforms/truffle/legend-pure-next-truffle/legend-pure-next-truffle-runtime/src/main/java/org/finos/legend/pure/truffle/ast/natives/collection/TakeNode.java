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
import org.finos.legend.pure.truffle.types.PureSequence;

import java.util.Arrays;

/**
 * {@code take(T[*], Integer[1]) : T[*]} -- first {@code count} elements.
 */
@NodeInfo(shortName = "take")
public final class TakeNode extends PureNode
{
    private static final String SIG = "take_T_MANY__Integer_1__T_MANY_";

    @Child
    private PureNode collection;

    @Child
    private PureNode count;

    public TakeNode(PureNode collection, PureNode count)
    {
        this.collection = collection;
        this.count = count;
    }

    @Override
    public Object executeGeneric(VirtualFrame frame)
    {
        Object col = collection.executeGeneric(frame);
        long n = IntegerHelper.asLong(count.executeGeneric(frame), SIG);
        int sz = CollectionHelper.size(col);
        int to = (int) Math.min(Math.max(n, 0), sz);
        if (to == 0) return PureSequence.EMPTY;
        // Single allocation — skip the full-collection {@code toArray} +
        // {@code Arrays.copyOf} double-copy that JFR identified at 13 leaf
        // samples on the warm wall.
        Object[] out = new Object[to];
        for (int i = 0; i < to; i++) out[i] = CollectionHelper.at(col, i);
        return new ObjectSequence(out);
    }
}
