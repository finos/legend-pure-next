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
import org.finos.legend.pure.truffle.execution.types.LongSequence;
import org.finos.legend.pure.truffle.execution.types.ObjectSequence;
import org.finos.legend.pure.truffle.execution.types.PrependLongSequence;
import org.finos.legend.pure.truffle.execution.types.PureSequence;

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
        // Unboxed fast paths: Integer[*] byte assemblies (e.g. the self-hosted
        // PDB writer's builder) concatenate long runs millions of times —
        // boxing every element into Object[] dominates the whole write.
        long[] la = longsOf(a);
        if (la != null)
        {
            // The builder's prepend shape: small chunk ++ large buffer.
            // PrependLongSequence turns the linear case into O(chunk) instead
            // of O(buffer) — see that class for the immutability argument.
            if (b instanceof PrependLongSequence pls)
            {
                return la.length == 0 ? pls : pls.prepend(la);
            }
            long[] lb = longsOf(b);
            if (lb != null)
            {
                int total = la.length + lb.length;
                if (total == 0)
                {
                    return PureSequence.EMPTY;
                }
                if (total >= 256)
                {
                    // Big enough that repeated prepends would hurt: relocate
                    // once into a front-reserve buffer so the NEXT prepend is
                    // in-place.
                    return PrependLongSequence.seed(la, lb);
                }
                long[] mergedLongs = new long[total];
                System.arraycopy(la, 0, mergedLongs, 0, la.length);
                System.arraycopy(lb, 0, mergedLongs, la.length, lb.length);
                return new LongSequence(mergedLongs);
            }
        }
        int aSize = CollectionHelper.size(a);
        int bSize = CollectionHelper.size(b);
        int total = aSize + bSize;
        if (total == 0)
        {
            return PureSequence.EMPTY;
        }
        // Direct fill into the final array — skips the per-input toArray
        // allocation+copy. Each {@code at(v, i)} is a constant-time read
        // for the common {@link ObjectSequence} case.
        Object[] merged = new Object[total];
        for (int i = 0; i < aSize; i++) merged[i] = CollectionHelper.at(a, i);
        for (int i = 0; i < bSize; i++) merged[aSize + i] = CollectionHelper.at(b, i);
        return new ObjectSequence(merged);
    }

    private static final long[] NO_LONGS = new long[0];

    /**
     * The value as an unboxed {@code long[]}, or {@code null} when it isn't
     * pure-long content: a {@link LongSequence}'s backing array, a boxed
     * {@link Long} as a singleton, an empty sequence as a zero-length array.
     * SMALL all-Long {@link ObjectSequence}s (collection literals like
     * {@code [80, 75, 3, 4]} parse boxed) are unboxed by copy so a literal
     * prefix doesn't force a large long[] buffer onto the boxed slow path;
     * large boxed sequences are left alone — converting those would re-copy
     * per call.
     */
    private static long[] longsOf(Object v)
    {
        if (v instanceof LongSequence ls)
        {
            return ls.backingValues();
        }
        if (v instanceof PrependLongSequence pls)
        {
            return pls.toLongArray();
        }
        if (v instanceof Long l)
        {
            return new long[]{l};
        }
        if (v instanceof PureSequence ps)
        {
            if (ps.isEmpty())
            {
                return NO_LONGS;
            }
            int sz = ps.size();
            if (sz <= 64)
            {
                long[] out = new long[sz];
                for (int i = 0; i < sz; i++)
                {
                    Object e = ps.getBoxed(i);
                    if (!(e instanceof Long l))
                    {
                        return null;
                    }
                    out[i] = l;
                }
                return out;
            }
        }
        return null;
    }
}
