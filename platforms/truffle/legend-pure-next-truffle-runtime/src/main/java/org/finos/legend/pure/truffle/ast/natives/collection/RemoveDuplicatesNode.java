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
 * {@code removeDuplicates(T[*], Function[0..1], Function[0..1]) : T[*]}
 * -- removes duplicate elements, with optional key extractor and equality functions.
 */
@NodeInfo(shortName = "removeDuplicates")
public final class RemoveDuplicatesNode extends PureNode
{
    @Child
    private PureNode collectionArg;

    @Child
    private PureNode keyFnArg;

    @Child
    private PureNode eqlFnArg;

    @Child
    private org.finos.legend.pure.truffle.ast.RawLambdaCallNode keyCallNode = new org.finos.legend.pure.truffle.ast.RawLambdaCallNode();

    @Child
    private org.finos.legend.pure.truffle.ast.RawLambdaCallNode eqlCallNode = new org.finos.legend.pure.truffle.ast.RawLambdaCallNode();

    public RemoveDuplicatesNode(PureNode collectionArg, PureNode keyFnArg, PureNode eqlFnArg)
    {
        this.collectionArg = collectionArg;
        this.keyFnArg = keyFnArg;
        this.eqlFnArg = eqlFnArg;
    }

    @Override
    public Object executeGeneric(VirtualFrame frame)
    {
        Object col = collectionArg.executeGeneric(frame);
        Object keyFn = keyFnArg != null ? keyFnArg.executeGeneric(frame) : null;
        Object eqlFn = eqlFnArg != null ? eqlFnArg.executeGeneric(frame) : null;

        int sz = CollectionHelper.size(col);
        boolean hasKeyFn = keyFn != null && !CollectionHelper.isEmpty(keyFn);
        boolean hasEqlFn = eqlFn != null && !CollectionHelper.isEmpty(eqlFn);

        Object[] result = new Object[sz];
        Object[] resultKeys = new Object[sz];
        int count = 0;

        for (int i = 0; i < sz; i++)
        {
            Object item = CollectionHelper.at(col, i);
            Object itemKey = hasKeyFn ? keyCallNode.call(keyFn, item) : item;
            boolean found = false;
            for (int j = 0; j < count; j++)
            {
                if (hasEqlFn)
                {
                    Object eqlResult = eqlCallNode.call(eqlFn, resultKeys[j], itemKey);
                    if (eqlResult instanceof Boolean b ? b : false)
                    {
                        found = true;
                        break;
                    }
                }
                else
                {
                    if (pureEquals(resultKeys[j], itemKey))
                    {
                        found = true;
                        break;
                    }
                }
            }
            if (!found)
            {
                result[count] = item;
                resultKeys[count] = itemKey;
                count++;
            }
        }
        if (count == 0)
        {
            return PureSequence.EMPTY;
        }
        return new ObjectSequence(Arrays.copyOf(result, count));
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
