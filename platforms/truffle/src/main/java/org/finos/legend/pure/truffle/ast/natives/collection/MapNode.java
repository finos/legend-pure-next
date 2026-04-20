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
import org.finos.legend.pure.truffle.types.ObjectSequence;
import org.finos.legend.pure.truffle.types.PureSequence;

import java.util.Arrays;

/**
 * {@code map(T[*], Function<{T[1]->V[*]}>[1]) : V[*]} -- Pure's map is
 * flat-map: each lambda result that is itself a collection has its
 * elements spliced into the output.
 */
@NodeInfo(shortName = "map")
public final class MapNode extends PureNode
{
    @Child
    private PureNode collection;

    @Child
    private PureNode lambda;

    @Child
    private org.finos.legend.pure.truffle.ast.RawLambdaCallNode callNode = new org.finos.legend.pure.truffle.ast.RawLambdaCallNode();

    public MapNode(PureNode collection, PureNode lambda)
    {
        this.collection = collection;
        this.lambda = lambda;
    }

    @Override
    public Object executeGeneric(VirtualFrame frame)
    {
        Object col = collection.executeGeneric(frame);
        Object fn = lambda.executeGeneric(frame);
        int sz = CollectionHelper.size(col);
        Object[] buf = new Object[sz];
        int count = 0;
        for (int i = 0; i < sz; i++)
        {
            Object item = CollectionHelper.at(col, i);
            Object result = callNode.call(fn, item);
            int rSz = CollectionHelper.size(result);
            if (rSz == 0)
            {
                continue;
            }
            if (rSz == 1)
            {
                if (count == buf.length)
                {
                    buf = Arrays.copyOf(buf, buf.length * 2);
                }
                buf[count++] = CollectionHelper.at(result, 0);
            }
            else
            {
                // flatten multi-valued result
                int needed = count + rSz;
                if (needed > buf.length)
                {
                    buf = Arrays.copyOf(buf, Math.max(buf.length * 2, needed));
                }
                for (int j = 0; j < rSz; j++)
                {
                    buf[count++] = CollectionHelper.at(result, j);
                }
            }
        }
        if (count == 0)
        {
            return PureSequence.EMPTY;
        }
        return new ObjectSequence(Arrays.copyOf(buf, count));
    }
}
