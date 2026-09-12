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

package org.finos.legend.pure.truffle.interpreter.ast.natives.collection;

import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.NodeInfo;
import org.finos.legend.pure.truffle.interpreter.ast.PureNode;
import org.finos.legend.pure.truffle.interpreter.ast.natives.math.IntegerHelper;
import org.finos.legend.pure.truffle.types.LongSequence;
import org.finos.legend.pure.truffle.types.ObjectSequence;
import org.finos.legend.pure.truffle.types.PrependLongSequence;
import org.finos.legend.pure.truffle.types.PureSequence;

import java.util.Arrays;

/**
 * {@code drop(T[*], Integer[1]) : T[*]} -- skip first {@code count} elements.
 */
@NodeInfo(shortName = "drop")
public final class DropNode extends PureNode
{
    private static final String SIG = "drop_T_MANY__Integer_1__T_MANY_";

    @Child
    private PureNode collection;

    @Child
    private PureNode count;

    public DropNode(PureNode collection, PureNode count)
    {
        this.collection = collection;
        this.count = count;
    }

    @Override
    public Object executeGeneric(VirtualFrame frame)
    {
        Object col = collection.executeGeneric(frame);
        long n = IntegerHelper.asLong(count.executeGeneric(frame), SIG);
        int sz = CollectionHelper.size(col);
        int from = (int) Math.min(Math.max(n, 0), sz);
        if (from >= sz) return PureSequence.EMPTY;
        // Unboxed fast paths: dropping from the front of long content is an
        // O(1) window view — no copy at all. The view is born "extended" so a
        // later prepend onto it cannot clobber the region that still belongs
        // to the original value's window (see PrependLongSequence).
        if (col instanceof PrependLongSequence pls)
        {
            return pls.dropView(from);
        }
        if (col instanceof LongSequence ls)
        {
            return PrependLongSequence.viewOf(ls, from);
        }
        // Single allocation — skip the full-collection {@code toArray} +
        // {@code Arrays.copyOfRange} double-copy.
        Object[] out = new Object[sz - from];
        for (int i = 0; i < out.length; i++) out[i] = CollectionHelper.at(col, from + i);
        return new ObjectSequence(out);
    }
}
