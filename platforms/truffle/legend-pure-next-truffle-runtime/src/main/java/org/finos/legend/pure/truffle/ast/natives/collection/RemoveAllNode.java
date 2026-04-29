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
 * {@code removeAll(T[*], T[*]) : T[*]} -- set difference using structural equality.
 */
@NodeInfo(shortName = "removeAll")
public final class RemoveAllNode extends PureNode
{
    @Child
    private PureNode setArg;

    @Child
    private PureNode otherArg;

    public RemoveAllNode(PureNode setArg, PureNode otherArg)
    {
        this.setArg = setArg;
        this.otherArg = otherArg;
    }

    @Override
    public Object executeGeneric(VirtualFrame frame)
    {
        Object set = setArg.executeGeneric(frame);
        Object other = otherArg.executeGeneric(frame);
        int setSz = CollectionHelper.size(set);
        int otherSz = CollectionHelper.size(other);
        Object[] buf = new Object[setSz];
        int count = 0;
        for (int i = 0; i < setSz; i++)
        {
            Object item = CollectionHelper.at(set, i);
            boolean found = false;
            for (int j = 0; j < otherSz; j++)
            {
                if (pureEquals(item, CollectionHelper.at(other, j)))
                {
                    found = true;
                    break;
                }
            }
            if (!found)
            {
                buf[count++] = item;
            }
        }
        if (count == 0)
        {
            return PureSequence.EMPTY;
        }
        return new ObjectSequence(Arrays.copyOf(buf, count));
    }

    private static boolean pureEquals(Object a, Object b)
    {
        if (a == b)
        {
            return true;
        }
        if (a == null || b == null)
        {
            return false;
        }
        return java.util.Objects.equals(a, b);
    }
}
