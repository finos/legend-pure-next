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

/**
 * {@code fold(T[*], Function<{T[1],V[*]->V[*]}>[1], V[*]) : V[*]} -- left fold.
 * The lambda receives {@code (element, accumulator)} (element first).
 */
@NodeInfo(shortName = "fold")
public final class FoldNode extends PureNode
{
    @Child
    private PureNode collection;

    @Child
    private PureNode lambda;

    @Child
    private PureNode seed;

    @Child
    private org.finos.legend.pure.truffle.ast.RawLambdaCallNode callNode = new org.finos.legend.pure.truffle.ast.RawLambdaCallNode();

    public FoldNode(PureNode collection, PureNode lambda, PureNode seed)
    {
        this.collection = collection;
        this.lambda = lambda;
        this.seed = seed;
    }

    @Override
    public Object executeGeneric(VirtualFrame frame)
    {
        Object col = collection.executeGeneric(frame);
        Object fn = lambda.executeGeneric(frame);
        Object acc = seed.executeGeneric(frame);
        int sz = CollectionHelper.size(col);
        for (int i = 0; i < sz; i++)
        {
            acc = callNode.call(fn, CollectionHelper.at(col, i), acc);
        }
        return acc;
    }
}
