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

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.NodeInfo;
import meta.pure.metamodel.valuespecification.Collection;
import meta.pure.metamodel.valuespecification.ValueSpecification;
import org.eclipse.collections.api.list.MutableList;
import org.finos.legend.pure.truffle.ast.PureNode;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code map(T[*], Function<{T[1]->V[*]}>[1]) : V[*]} — Pure's map is
 * flat-map: each lambda result that is itself a {@code Collection} has its
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
    private LambdaCallNode callNode = new LambdaCallNode();

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
        MutableList<ValueSpecification> values = CollectionHelper.values(col);
        List<ValueSpecification> results = new ArrayList<>(values.size());
        int size = values.size();
        for (int i = 0; i < size; i++)
        {
            ValueSpecification result = callNode.call(fn, values.get(i));
            if (result instanceof Collection resultCol)
            {
                addAll(results, resultCol);
            }
            else if (!CollectionHelper.isEmpty(result))
            {
                results.add(result);
            }
        }
        return CollectionHelper.makeCollection(results);
    }

    @TruffleBoundary
    private static void addAll(List<ValueSpecification> results, Collection resultCol)
    {
        results.addAll(resultCol._values());
    }
}
