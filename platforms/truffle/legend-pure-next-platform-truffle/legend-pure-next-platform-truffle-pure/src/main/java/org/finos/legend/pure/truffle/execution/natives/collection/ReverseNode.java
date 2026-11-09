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

/**
 * {@code reverse(T[m]) : T[m]}.
 */
@NodeInfo(shortName = "reverse")
public final class ReverseNode extends PureNode
{
    @Child
    private PureNode arg;

    public ReverseNode(PureNode arg)
    {
        this(arg, COPY);
    }

    public ReverseNode(PureNode arg, int updatableInPlace)
    {
        this.arg = arg;
        this.updatableInPlace = updatableInPlace;
    }

    /**
     * Whether this call may change its receiver in place (pure/specification/IN_PLACE_UPDATE_ANALYSIS.md): {@link #COPY} never,
     * {@link #IN_PLACE} always — the compiler marked the receiver updatable in place — or, a frame slot, when the flag of the
     * parameter it is updatable in place on (updatableInPlaceIfParameter) holds true. Only a sequence is changed, and only once it is a
     * {@link GrowableSequence}: anything else is copied into one first.
     */
    public static final int COPY = -2;
    public static final int IN_PLACE = -1;

    @com.oracle.truffle.api.CompilerDirectives.CompilationFinal
    private final int updatableInPlace;

    private boolean owns(VirtualFrame frame, Object receiver)
    {
        // The mode first: a copying node folds this away entirely.
        return updatableInPlace != COPY
                && (updatableInPlace == IN_PLACE || frame.getValue(updatableInPlace) == Boolean.TRUE)
                && receiver instanceof org.finos.legend.pure.truffle.execution.types.PureSequence;
    }


    @Override
    public Object executeGeneric(VirtualFrame frame)
    {
        Object v = arg.executeGeneric(frame);
        if (owns(frame, v))
        {
            return org.finos.legend.pure.truffle.execution.types.GrowableSequence.reversed(v);
        }
        Object[] arr = CollectionHelper.toArray(v);
        if (arr.length == 0)
        {
            return PureSequence.EMPTY;
        }
        // reverse in-place
        for (int lo = 0, hi = arr.length - 1; lo < hi; lo++, hi--)
        {
            Object tmp = arr[lo];
            arr[lo] = arr[hi];
            arr[hi] = tmp;
        }
        return new ObjectSequence(arr);
    }
}
