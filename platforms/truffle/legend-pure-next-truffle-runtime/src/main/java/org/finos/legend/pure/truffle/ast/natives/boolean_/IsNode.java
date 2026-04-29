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

import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.NodeInfo;
import org.finos.legend.pure.truffle.ast.PureNode;

import java.util.Objects;

/**
 * {@code is(Any[1], Any[1]) : Boolean[1]} -- identity comparison.
 *
 * <p>Compares by reference ({@code ==}), with special cases for primitives
 * which fall back to structural equality since the JVM may not intern them.</p>
 */
@NodeInfo(shortName = "is")
public final class IsNode extends PureNode
{
    @Child
    private PureNode left;

    @Child
    private PureNode right;

    public IsNode(PureNode left, PureNode right)
    {
        this.left = left;
        this.right = right;
    }

    @Override
    public Object executeGeneric(VirtualFrame frame)
    {
        Object a = left.executeGeneric(frame);
        Object b = right.executeGeneric(frame);
        Object rawA = normalize(a);
        Object rawB = normalize(b);
        if (rawA == rawB)
        {
            return true;
        }
        if (rawA instanceof Number && rawB instanceof Number
                || rawA instanceof String && rawB instanceof String
                || rawA instanceof Boolean && rawB instanceof Boolean
                || rawA instanceof org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.type.Enum && rawB instanceof org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.type.Enum)
        {
            return callPureEquals(rawA, rawB);
        }
        // Enum-String cross-comparison: extract enum value name from qualified path
        if (rawA instanceof org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.type.Enum ea && rawB instanceof String s)
        {
            return Objects.equals(ea._name(), extractEnumValueName(s));
        }
        if (rawB instanceof org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.type.Enum eb && rawA instanceof String s)
        {
            return Objects.equals(extractEnumValueName(s), eb._name());
        }
        return false;
    }

    private static boolean callPureEquals(Object a, Object b)
    {
        return Objects.equals(a, b);
    }

    private static String extractEnumValueName(String s)
    {
        int dotIdx = s.lastIndexOf('.');
        return (dotIdx > 0 && s.contains("::")) ? s.substring(dotIdx + 1) : s;
    }

    private static Object normalize(Object v)
    {
        if (v instanceof org.finos.legend.pure.truffle.types.PureSequence ps && ps.isEmpty())
        {
            return null;
        }
        return v;
    }
}
