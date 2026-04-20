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
import org.finos.legend.pure.truffle.ast.PureNode;
import org.finos.legend.pure.truffle.types.ObjectSequence;
import org.finos.legend.pure.truffle.types.PureSequence;

import java.util.Arrays;

/**
 * {@code sort(T[m], Function<{T[1]->U[1]}>[0..1], Function<{U[1],U[1]->Integer[1]}>[0..1]) : T[m]}
 * -- sort with optional key extractor and comparator lambdas.
 *
 * <p>Element access and key extraction are boundary-free. The actual
 * {@link Arrays#sort} call is behind a single {@code @TruffleBoundary}
 * since it allocates internally.</p>
 */
@NodeInfo(shortName = "sort")
public final class SortNode extends PureNode
{
    @Child
    private PureNode collectionArg;

    @Child
    private PureNode keyFnArg;

    @Child
    private PureNode compFnArg;

    @Child
    private org.finos.legend.pure.truffle.ast.RawLambdaCallNode keyCallNode = new org.finos.legend.pure.truffle.ast.RawLambdaCallNode();

    @Child
    private org.finos.legend.pure.truffle.ast.RawLambdaCallNode compCallNode = new org.finos.legend.pure.truffle.ast.RawLambdaCallNode();

    public SortNode(PureNode collectionArg, PureNode keyFnArg, PureNode compFnArg)
    {
        this.collectionArg = collectionArg;
        this.keyFnArg = keyFnArg;
        this.compFnArg = compFnArg;
    }

    @Override
    public Object executeGeneric(VirtualFrame frame)
    {
        Object col = collectionArg.executeGeneric(frame);
        Object keyFn = keyFnArg != null ? keyFnArg.executeGeneric(frame) : null;
        Object compFn = compFnArg != null ? compFnArg.executeGeneric(frame) : null;

        Object[] arr = CollectionHelper.toArray(col);
        if (arr.length <= 1)
        {
            return arr.length == 0 ? PureSequence.EMPTY : arr[0];
        }

        boolean hasKeyFn = keyFn != null && !CollectionHelper.isEmpty(keyFn);
        boolean hasCompFn = compFn != null && !CollectionHelper.isEmpty(compFn);

        // Extract keys once if key function supplied
        Object[] keys = hasKeyFn ? extractKeys(arr, keyFn) : arr;

        // Sort via insertion sort (boundary-free, stable, no allocation)
        if (hasCompFn)
        {
            insertionSortWithComp(arr, keys, compFn);
        }
        else
        {
            insertionSortNatural(arr, keys);
        }
        return new ObjectSequence(arr);
    }

    private Object[] extractKeys(Object[] arr, Object keyFn)
    {
        Object[] keys = new Object[arr.length];
        for (int i = 0; i < arr.length; i++)
        {
            keys[i] = keyCallNode.call(keyFn, arr[i]);
        }
        return keys;
    }

    private void insertionSortWithComp(Object[] arr, Object[] keys, Object compFn)
    {
        for (int i = 1; i < arr.length; i++)
        {
            Object elem = arr[i];
            Object key = keys[i];
            int j = i - 1;
            while (j >= 0)
            {
                Object cmpResult = compCallNode.call(compFn, keys[j], key);
                int cmp = cmpResult instanceof Number n ? n.intValue() : 0;
                if (cmp <= 0)
                {
                    break;
                }
                arr[j + 1] = arr[j];
                keys[j + 1] = keys[j];
                j--;
            }
            arr[j + 1] = elem;
            keys[j + 1] = key;
        }
    }

    @TruffleBoundary
    @SuppressWarnings("unchecked")
    private static void insertionSortNatural(Object[] arr, Object[] keys)
    {
        for (int i = 1; i < arr.length; i++)
        {
            Object elem = arr[i];
            Object key = keys[i];
            int j = i - 1;
            while (j >= 0 && naturalCompare(keys[j], key) > 0)
            {
                arr[j + 1] = arr[j];
                keys[j + 1] = keys[j];
                j--;
            }
            arr[j + 1] = elem;
            keys[j + 1] = key;
        }
    }

    @SuppressWarnings("unchecked")
    private static int naturalCompare(Object a, Object b)
    {
        if (a instanceof Number nA && b instanceof Number nB)
        {
            return Double.compare(nA.doubleValue(), nB.doubleValue());
        }
        if (a instanceof Comparable c && a.getClass().isInstance(b))
        {
            return c.compareTo(b);
        }
        int typeCmp = a.getClass().getSimpleName().compareTo(b.getClass().getSimpleName());
        if (typeCmp != 0)
        {
            return typeCmp;
        }
        return String.valueOf(a).compareTo(String.valueOf(b));
    }
}
