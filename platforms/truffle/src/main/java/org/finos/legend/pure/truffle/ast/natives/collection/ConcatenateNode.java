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

/**
 * {@code concatenate(T[*], T[*]) : T[*]} -- append both element lists into
 * a fresh collection.
 */
@NodeInfo(shortName = "concatenate")
public final class ConcatenateNode extends PureNode
{
    @Child
    private PureNode left;

    @Child
    private PureNode right;

    public ConcatenateNode(PureNode left, PureNode right)
    {
        this.left = left;
        this.right = right;
    }

    @Override
    public Object executeGeneric(VirtualFrame frame)
    {
        Object a = left.executeGeneric(frame);
        Object b = right.executeGeneric(frame);
        Object[] aArr = CollectionHelper.toArray(a);
        Object[] bArr = CollectionHelper.toArray(b);
        int total = aArr.length + bArr.length;
        if (total == 0)
        {
            return PureSequence.EMPTY;
        }
        Object[] merged = new Object[total];
        System.arraycopy(aArr, 0, merged, 0, aArr.length);
        System.arraycopy(bArr, 0, merged, aArr.length, bArr.length);
        return new ObjectSequence(merged);
    }
}
