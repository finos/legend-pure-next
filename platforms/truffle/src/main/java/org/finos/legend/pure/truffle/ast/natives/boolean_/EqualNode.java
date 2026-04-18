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

package org.finos.legend.pure.truffle.ast.natives.boolean_;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.NodeInfo;
import org.finos.legend.pure.execution.NativeRepository;
import org.finos.legend.pure.truffle.ast.PureNode;
import org.finos.legend.pure.truffle.types.ValueAdapter;

/**
 * {@code equal(Any[*], Any[*]) : Boolean[1]}.
 *
 * <p>Evaluates both children to raw values, then delegates to
 * {@link NativeRepository#pureEquals} via a {@link TruffleBoundary}
 * method for deep equality comparison.</p>
 */
@NodeInfo(shortName = "equal")
public final class EqualNode extends PureNode
{
    @Child
    private PureNode left;

    @Child
    private PureNode right;

    public EqualNode(PureNode left, PureNode right)
    {
        this.left = left;
        this.right = right;
    }

    @Override
    public Object executeGeneric(VirtualFrame frame)
    {
        Object a = left.executeGeneric(frame);
        Object b = right.executeGeneric(frame);
        return doEquals(a, b);
    }

    @TruffleBoundary
    private static boolean doEquals(Object a, Object b)
    {
        Object rawA = normalizeForEquals(ValueAdapter.toRaw(a));
        Object rawB = normalizeForEquals(ValueAdapter.toRaw(b));
        return NativeRepository.pureEquals(rawA, rawB);
    }

    private static Object normalizeForEquals(Object v)
    {
        if (v instanceof org.finos.legend.pure.truffle.types.PureSequence seq)
        {
            return java.util.Arrays.asList(seq.toBoxedArray());
        }
        if (v instanceof org.finos.legend.pure.truffle.types.PureNull)
        {
            return null;
        }
        return v;
    }
}
