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
import org.finos.legend.pure.truffle.ast.natives.math.IntegerHelper;
import org.finos.legend.pure.truffle.types.ObjectSequence;

/**
 * {@code add(T[*], T[1]) : T[1..*]} -- append element to collection.
 * {@code add(T[*], Integer[1], T[1]) : T[1..*]} -- insert at index.
 */
@NodeInfo(shortName = "add")
public final class AddNode extends PureNode
{
    private static final String SIG = "add";

    @Child
    private PureNode collectionArg;

    @Child
    private PureNode indexOrElementArg;

    @Child
    private PureNode elementArg;

    public AddNode(PureNode collectionArg, PureNode indexOrElementArg, PureNode elementArg)
    {
        this.collectionArg = collectionArg;
        this.indexOrElementArg = indexOrElementArg;
        this.elementArg = elementArg;
    }

    @Override
    public Object executeGeneric(VirtualFrame frame)
    {
        Object col = collectionArg.executeGeneric(frame);
        Object second = indexOrElementArg.executeGeneric(frame);
        Object[] arr = CollectionHelper.toArray(col);
        if (elementArg != null)
        {
            // Three-arg: add(col, index, element)
            long index = IntegerHelper.asLong(second, SIG);
            Object element = elementArg.executeGeneric(frame);
            Object[] result = new Object[arr.length + 1];
            int idx = (int) index;
            System.arraycopy(arr, 0, result, 0, idx);
            result[idx] = element;
            System.arraycopy(arr, idx, result, idx + 1, arr.length - idx);
            return new ObjectSequence(result);
        }
        // Two-arg: add(col, element)
        Object[] result = new Object[arr.length + 1];
        System.arraycopy(arr, 0, result, 0, arr.length);
        result[arr.length] = second;
        return new ObjectSequence(result);
    }
}
