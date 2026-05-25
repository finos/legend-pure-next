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

import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.NodeInfo;
import org.finos.legend.pure.truffle.ast.PureNode;
import org.finos.legend.pure.truffle.ast.natives.math.IntegerHelper;

/**
 * {@code range(Integer[1], Integer[1], Integer[1]) : Integer[*]} —
 * arithmetic progression from {@code start} to {@code stop} (exclusive) by
 * {@code step}. Negative step counts down.
 *
 * <p>Returns a {@code LongSequence} containing the raw {@code long} values.</p>
 */
@NodeInfo(shortName = "range")
public final class RangeNode extends PureNode
{
    private static final String SIG = "range_Integer_1__Integer_1__Integer_1__Integer_MANY_";

    @Child
    private PureNode startNode;

    @Child
    private PureNode stopNode;

    @Child
    private PureNode stepNode;

    @CompilationFinal
    private final Object elementGenericType;

    public RangeNode(PureNode startNode, PureNode stopNode, PureNode stepNode, Object elementGenericType)
    {
        this.startNode = startNode;
        this.stopNode = stopNode;
        this.stepNode = stepNode;
        this.elementGenericType = elementGenericType;
    }

    @Override
    public Object executeGeneric(VirtualFrame frame)
    {
        long start = IntegerHelper.asLong(startNode.executeGeneric(frame), SIG);
        long stop = IntegerHelper.asLong(stopNode.executeGeneric(frame), SIG);
        long step = IntegerHelper.asLong(stepNode.executeGeneric(frame), SIG);
        return build(start, stop, step);
    }

    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    private Object build(long start, long stop, long step)
    {
        int capacity = (int) Math.max(0, step > 0 ? (stop - start + step - 1) / step : (start - stop - step - 1) / (-step));
        long[] values = new long[capacity];
        int idx = 0;
        for (long i = start; step > 0 ? i < stop : i > stop; i += step)
        {
            if (idx < values.length)
            {
                values[idx++] = i;
            }
        }
        if (idx < values.length)
        {
            values = java.util.Arrays.copyOf(values, idx);
        }
        return new org.finos.legend.pure.truffle.types.LongSequence(values);
    }
}
