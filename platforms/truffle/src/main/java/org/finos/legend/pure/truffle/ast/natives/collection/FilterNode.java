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
import meta.pure.metamodel.valuespecification.ValueSpecification;
import org.eclipse.collections.api.list.MutableList;
import org.finos.legend.pure.truffle.ast.PureNode;

import java.util.ArrayList;
import java.util.List;

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
    private LambdaCallNode callNode = new LambdaCallNode();

    public FilterNode(PureNode collection, PureNode lambda)
    {
        this.collection = collection;
        this.lambda = lambda;
    }

    @Override
    public Object executeGeneric(VirtualFrame frame)
    {
        Object col = collection.executeGeneric(frame);
        Object fn = lambda.executeGeneric(frame);
        MutableList<ValueSpecification> values = CollectionHelper.values(col);
        List<ValueSpecification> kept = new ArrayList<>();
        int size = values.size();
        for (int i = 0; i < size; i++)
        {
            ValueSpecification item = values.get(i);
            ValueSpecification test = callNode.call(fn, item);
            if (Boolean.TRUE.equals(org.finos.legend.pure.execution._E_ValueSpecification.unwrap(test)))
            {
                kept.add(item);
            }
        }
        return CollectionHelper.makeCollection(kept);
    }
}
