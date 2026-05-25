// Copyright 2026 Goldman Sachs
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
import org.finos.legend.pure.truffle.ast.natives.boolean_.EqualNode;
import org.finos.legend.pure.truffle.runtime.TruffleMetadataAccess;

/**
 * {@code contains(Any[*], Any[1]) : Boolean[1]} — native fast path for the
 * Pure-level {@code collection->exists(x | $value == $x)} body.
 *
 * <p>The Pure profiler showed the inner equality-lambda invoked 263M times
 * over the metamodel_factories compile (15% of warm CPU all on
 * {@link org.finos.legend.pure.truffle.ast.RawLambdaCallNode#dispatch}).
 * Inlining the loop + the equality check eliminates the per-element lambda
 * dispatch and the captured-closure allocation.</p>
 */
@NodeInfo(shortName = "contains")
public final class ContainsNode extends PureNode
{
    @Child
    private PureNode collection;

    @Child
    private PureNode value;

    public ContainsNode(PureNode collection, PureNode value)
    {
        this.collection = collection;
        this.value = value;
    }

    @Override
    public boolean executeBoolean(VirtualFrame frame)
    {
        Object col = collection.executeGeneric(frame);
        int sz = CollectionHelper.size(col);
        if (sz == 0) return false;
        Object val = value.executeGeneric(frame);
        TruffleMetadataAccess resolver = getResolver();
        for (int i = 0; i < sz; i++)
        {
            if (EqualNode.equalsStatic(val, CollectionHelper.at(col, i), resolver))
            {
                return true;
            }
        }
        return false;
    }

    @Override
    public Object executeGeneric(VirtualFrame frame)
    {
        return executeBoolean(frame);
    }
}
