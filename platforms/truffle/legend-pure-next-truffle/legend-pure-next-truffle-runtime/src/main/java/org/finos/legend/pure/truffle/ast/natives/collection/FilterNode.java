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
 * {@code filter(T[*], Function<{T[1]->Boolean[1]}>[1]) : T[*]}.
 */
@NodeInfo(shortName = "filter")
public final class FilterNode extends PureNode
{
    @Child
    private PureNode collection;

    @Child
    private PureNode lambda;

    @Child
    private org.finos.legend.pure.truffle.ast.RawLambdaCallNode callNode = new org.finos.legend.pure.truffle.ast.RawLambdaCallNode();

    public FilterNode(PureNode collection, PureNode lambda)
    {
        this.collection = collection;
        this.lambda = lambda;
    }

    @Override
    public Object executeGeneric(VirtualFrame frame)
    {
        Object col = collection.executeGeneric(frame);
        int sz = CollectionHelper.size(col);
        if (sz == 0)
        {
            return PureSequence.EMPTY;
        }
        Object fn = lambda.executeGeneric(frame);
        Object[] kept = new Object[sz];
        int count = 0;
        for (int i = 0; i < sz; i++)
        {
            Object item = CollectionHelper.at(col, i);
            Object test = callNode.call(fn, item);
            if (test instanceof Boolean b ? b : false)
            {
                kept[count++] = item;
            }
        }
        if (count == 0)
        {
            return PureSequence.EMPTY;
        }
        return new ObjectSequence(Arrays.copyOf(kept, count));
    }
}
