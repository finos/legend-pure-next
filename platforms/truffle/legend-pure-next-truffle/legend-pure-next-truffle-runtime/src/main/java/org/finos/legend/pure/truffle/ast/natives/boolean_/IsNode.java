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
    public boolean executeBoolean(VirtualFrame frame)
    {
        Object rawA = normalize(left.executeGeneric(frame));
        Object rawB = normalize(right.executeGeneric(frame));
        if (rawA == rawB)
        {
            return true;
        }
        if (rawA instanceof Number && rawB instanceof Number
                || rawA instanceof String && rawB instanceof String
                || rawA instanceof Boolean && rawB instanceof Boolean)
        {
            return callPureEquals(rawA, rawB);
        }
        // pureTypeOf is class-keyed-cached (PureObj.LEGACY_CLASS_PATH_CACHE)
        // so post-warmup it's a single ConcurrentHashMap.get(Class) — bounded
        // PE cost. Earlier the uncached pureTypeIs blew Graal's inlining
        // budget here; the cache fixes that. Validated on TrufflePureTestRunner.
        boolean aIsEnum = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.pureTypeIs(rawA,
                "meta::pure::metamodel::type::Enum");
        boolean bIsEnum = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.pureTypeIs(rawB,
                "meta::pure::metamodel::type::Enum");
        if (aIsEnum && bIsEnum)
        {
            Object nameA = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.read(rawA, "name");
            Object nameB = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.read(rawB, "name");
            if (!Objects.equals(nameA, nameB))
            {
                return false;
            }
            if (rawA.getClass() == rawB.getClass() && rawA.getClass().isEnum())
            {
                return true;
            }
            Object cgtA = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.read(rawA, "classifierGenericType");
            Object cgtB = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.read(rawB, "classifierGenericType");
            if (cgtA == null || cgtB == null)
            {
                return rawA.getClass() == rawB.getClass();
            }
            var typeA = org.finos.legend.pure.truffle.runtime.helper._GenericType.type(cgtA);
            var typeB = org.finos.legend.pure.truffle.runtime.helper._GenericType.type(cgtB);
            if (typeA == typeB)
            {
                return true;
            }
            if (typeA != null && typeB != null)
            {
                String pathA = org.finos.legend.pure.truffle.runtime.helper._PackageableElement.path(typeA);
                String pathB = org.finos.legend.pure.truffle.runtime.helper._PackageableElement.path(typeB);
                return pathA != null && pathA.equals(pathB);
            }
            return false;
        }
        if (aIsEnum && rawB instanceof String s)
        {
            return Objects.equals(org.finos.legend.pure.truffle.runtime.dynobj.PureObj.read(rawA, "name"),
                    extractEnumValueName(s));
        }
        if (bIsEnum && rawA instanceof String s)
        {
            return Objects.equals(extractEnumValueName(s),
                    org.finos.legend.pure.truffle.runtime.dynobj.PureObj.read(rawB, "name"));
        }
        return false;
    }

    @Override
    public Object executeGeneric(VirtualFrame frame)
    {
        return executeBoolean(frame);
    }

    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    private static boolean callPureEquals(Object a, Object b)
    {
        return Objects.equals(a, b);
    }

    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    private static String extractEnumValueName(String s)
    {
        int dotIdx = s.lastIndexOf('.');
        return (dotIdx > 0 && s.contains("::")) ? s.substring(dotIdx + 1) : s;
    }

    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    private static Object normalize(Object v)
    {
        if (v instanceof org.finos.legend.pure.truffle.types.PureSequence ps && ps.isEmpty())
        {
            return null;
        }
        return v;
    }
}
