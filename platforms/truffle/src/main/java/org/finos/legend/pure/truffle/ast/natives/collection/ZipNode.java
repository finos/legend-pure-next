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
import meta.pure.functions.collection.PairImpl;
import org.finos.legend.pure.truffle.ast.PureNode;
import org.finos.legend.pure.truffle.types.ObjectSequence;
import org.finos.legend.pure.truffle.types.PureNull;

/**
 * {@code zip(T[*], U[*]) : Pair<T,U>[*]} -- zips two collections into pairs.
 */
@NodeInfo(shortName = "zip")
public final class ZipNode extends PureNode
{
    @Child
    private PureNode leftArg;

    @Child
    private PureNode rightArg;

    public ZipNode(PureNode leftArg, PureNode rightArg)
    {
        this.leftArg = leftArg;
        this.rightArg = rightArg;
    }

    @Override
    public Object executeGeneric(VirtualFrame frame)
    {
        Object left = leftArg.executeGeneric(frame);
        Object right = rightArg.executeGeneric(frame);
        return doZip(left, right);
    }

    @TruffleBoundary
    private static Object doZip(Object left, Object right)
    {
        int leftSz = CollectionHelper.size(left);
        int rightSz = CollectionHelper.size(right);
        int sz = Math.min(leftSz, rightSz);
        if (sz == 0)
        {
            return PureNull.INSTANCE;
        }
        Object[] pairs = new Object[sz];
        for (int i = 0; i < sz; i++)
        {
            PairImpl pair = new PairImpl();
            pair._first(CollectionHelper.at(left, i));
            pair._second(CollectionHelper.at(right, i));
            pairs[i] = pair;
        }
        return new ObjectSequence(pairs);
    }
}
