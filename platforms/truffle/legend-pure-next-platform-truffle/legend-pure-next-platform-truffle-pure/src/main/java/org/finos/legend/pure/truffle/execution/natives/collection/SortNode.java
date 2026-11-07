// Copyright 2024 Goldman Sachs
// ©2026 JP Morgan Chase & Co. All rights reserved.
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

package org.finos.legend.pure.truffle.execution.natives.collection;

import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.NodeInfo;
import org.finos.legend.pure.truffle.execution.ast.PureNode;
import org.finos.legend.pure.truffle.execution.types.ObjectSequence;
import org.finos.legend.pure.truffle.execution.types.PureSequence;

import java.util.Arrays;

/**
 * {@code sort(T[m], Function<{T[1]->U[1]}>[0..1], Function<{U[1],U[1]->Integer[1]}>[0..1]) : T[m]}
 * -- sort with optional key extractor and comparator lambdas.
 *
 * <p>Keys are extracted once; the sort itself (a stable merge sort) runs behind a boundary,
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
    private org.finos.legend.pure.truffle.execution.ast.call.RawLambdaCallNode keyCallNode = new org.finos.legend.pure.truffle.execution.ast.call.RawLambdaCallNode();

    @Child
    private org.finos.legend.pure.truffle.execution.ast.call.RawLambdaCallNode compCallNode = new org.finos.legend.pure.truffle.execution.ast.call.RawLambdaCallNode();

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

        if (hasCompFn)
        {
            sortWithComp(arr, keys, compFn);
        }
        else
        {
            mergeSort(arr, keys, SortNode::naturalCompare);
        }
        return new ObjectSequence(arr);
    }

    private interface KeyComparator
    {
        int compare(Object a, Object b);
    }

    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    private void sortWithComp(Object[] arr, Object[] keys, Object compFn)
    {
        mergeSort(arr, keys, (a, b) -> compCallNode.call(compFn, a, b) instanceof Number n ? n.intValue() : 0);
    }

    /**
     * A stable bottom-up merge sort of {@code arr} by {@code keys}: O(n log n) comparisons, where the former
     * insertion sort made O(n²) (1.5 s of the self-host compile, sorting the reverse index). Not TimSort: it
     * throws on a comparator that breaks its contract, which a Pure comparator may do and insertion sort
     * tolerated; a merge sort tolerates it too. On equal keys the left element goes first, keeping the order.
     */
    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    private static void mergeSort(Object[] arr, Object[] keys, KeyComparator comparator)
    {
        int n = arr.length;
        Object[] arrBuffer = new Object[n];
        Object[] keyBuffer = new Object[n];
        for (int width = 1; width < n; width *= 2)
        {
            for (int lo = 0; lo < n - width; lo += 2 * width)
            {
                merge(arr, keys, arrBuffer, keyBuffer, lo, lo + width, Math.min(lo + 2 * width, n), comparator);
            }
        }
    }

    private static void merge(Object[] arr, Object[] keys, Object[] arrBuffer, Object[] keyBuffer, int lo, int mid, int hi, KeyComparator comparator)
    {
        int left = lo;
        int right = mid;
        int out = lo;
        while (left < mid && right < hi)
        {
            boolean takeLeft = comparator.compare(keys[left], keys[right]) <= 0;
            int from = takeLeft ? left++ : right++;
            arrBuffer[out] = arr[from];
            keyBuffer[out] = keys[from];
            out++;
        }
        while (left < mid)
        {
            arrBuffer[out] = arr[left];
            keyBuffer[out] = keys[left];
            out++;
            left++;
        }
        while (right < hi)
        {
            arrBuffer[out] = arr[right];
            keyBuffer[out] = keys[right];
            out++;
            right++;
        }
        System.arraycopy(arrBuffer, lo, arr, lo, hi - lo);
        System.arraycopy(keyBuffer, lo, keys, lo, hi - lo);
    }

    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    private Object[] extractKeys(Object[] arr, Object keyFn)
    {
        Object[] keys = new Object[arr.length];
        for (int i = 0; i < arr.length; i++)
        {
            keys[i] = keyCallNode.call(keyFn, arr[i]);
        }
        return keys;
    }

    @SuppressWarnings("unchecked")
    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
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
        int typeCmp = a.getClass().getName().compareTo(b.getClass().getName());
        if (typeCmp != 0)
        {
            return typeCmp;
        }
        return String.valueOf(a).compareTo(String.valueOf(b));
    }
}
