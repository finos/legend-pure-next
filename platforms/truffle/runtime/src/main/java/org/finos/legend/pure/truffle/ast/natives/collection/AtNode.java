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

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.NodeInfo;
import org.finos.legend.pure.truffle.ast.PureNode;
import org.finos.legend.pure.truffle.ast.natives.math.IntegerHelper;

/**
 * {@code at(T[*], Integer[1]) : T[1]} — indexed access. Bounds-checked
 * against Pure's flatten semantics: a scalar argument acts
 * as a one-element collection.
 */
@NodeInfo(shortName = "at")
public final class AtNode extends PureNode
{
    private static final String SIG = "at_T_MANY__Integer_1__T_1_";

    @Child
    private PureNode collection;

    @Child
    private PureNode index;

    public AtNode(PureNode collection, PureNode index)
    {
        this.collection = collection;
        this.index = index;
    }

    @Override
    public Object executeGeneric(VirtualFrame frame)
    {
        Object col = collection.executeGeneric(frame);
        long i = IntegerHelper.asLong(index.executeGeneric(frame), SIG);
        int size = CollectionHelper.size(col);
        if (i < 0 || i >= size)
        {
            CompilerDirectives.transferToInterpreter();
            throw outOfBounds(i, size);
        }
        return CollectionHelper.at(col, (int) i);
    }

    @TruffleBoundary
    private static RuntimeException outOfBounds(long i, int size)
    {
        return new RuntimeException("The system is trying to get an element at offset " + i
                + " where the collection is of size " + size);
    }
}
