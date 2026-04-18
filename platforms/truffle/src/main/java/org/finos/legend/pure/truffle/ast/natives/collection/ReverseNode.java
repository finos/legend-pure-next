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
import org.finos.legend.pure.truffle.types.PureNull;

/**
 * {@code reverse(T[m]) : T[m]}.
 */
@NodeInfo(shortName = "reverse")
public final class ReverseNode extends PureNode
{
    @Child
    private PureNode arg;

    public ReverseNode(PureNode arg)
    {
        this.arg = arg;
    }

    @Override
    public Object executeGeneric(VirtualFrame frame)
    {
        Object v = arg.executeGeneric(frame);
        Object[] arr = CollectionHelper.toArray(v);
        if (arr.length == 0)
        {
            return PureNull.INSTANCE;
        }
        // reverse in-place
        for (int lo = 0, hi = arr.length - 1; lo < hi; lo++, hi--)
        {
            Object tmp = arr[lo];
            arr[lo] = arr[hi];
            arr[hi] = tmp;
        }
        return new ObjectSequence(arr);
    }
}
