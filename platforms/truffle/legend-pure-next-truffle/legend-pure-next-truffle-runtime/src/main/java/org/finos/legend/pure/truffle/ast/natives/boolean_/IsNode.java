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
import org.finos.legend.pure.truffle.ast.PureNode;
import org.finos.legend.pure.truffle.runtime.dynobj.PureClassRegistry;
import org.finos.legend.pure.truffle.runtime.dynobj.PureObj;
import org.finos.legend.pure.truffle.runtime.helper._GenericType;
import org.finos.legend.pure.truffle.runtime.helper._PackageableElement;
import org.finos.legend.pure.truffle.types.PureSequence;

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

    private static final int SLOT_CLASSIFIER_GENERIC_TYPE = PureClassRegistry.globalSlot("classifierGenericType");
    private static final int SLOT_NAME = PureClassRegistry.globalSlot("name");
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
        var resolver = getResolver();
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
        boolean aIsEnum = PureObj.pureTypeIs(rawA,
                "meta::pure::metamodel::type::Enum");
        boolean bIsEnum = PureObj.pureTypeIs(rawB,
                "meta::pure::metamodel::type::Enum");
        if (aIsEnum && bIsEnum)
        {
            Object nameA = PureObj.readBySlot(rawA, SLOT_NAME);
            Object nameB = PureObj.readBySlot(rawB, SLOT_NAME);
            if (!Objects.equals(nameA, nameB))
            {
                return false;
            }
            // Post enum-to-PDO migration: all enum values are PDO singletons
            // sharing the {@code Enum} classInfo. Two values are the same
            // enum type iff their CGTs resolve to the same Pure type.
            Object cgtA = PureObj.readBySlot(rawA, SLOT_CLASSIFIER_GENERIC_TYPE);
            Object cgtB = PureObj.readBySlot(rawB, SLOT_CLASSIFIER_GENERIC_TYPE);
            if (cgtA == null || cgtB == null)
            {
                return false;
            }
            var typeA = _GenericType.type(cgtA);
            var typeB = _GenericType.type(cgtB);
            if (typeA == typeB)
            {
                return true;
            }
            if (typeA != null && typeB != null)
            {
                String pathA = _PackageableElement.path(typeA, resolver);
                String pathB = _PackageableElement.path(typeB, resolver);
                return pathA != null && pathA.equals(pathB);
            }
            return false;
        }
        if (aIsEnum && rawB instanceof String s)
        {
            return Objects.equals(PureObj.readBySlot(rawA, SLOT_NAME),
                    extractEnumValueName(s));
        }
        if (bIsEnum && rawA instanceof String s)
        {
            return Objects.equals(extractEnumValueName(s),
                    PureObj.readBySlot(rawB, SLOT_NAME));
        }
        return false;
    }

    @Override
    public Object executeGeneric(VirtualFrame frame)
    {
        return executeBoolean(frame);
    }

    @TruffleBoundary
    private static boolean callPureEquals(Object a, Object b)
    {
        return Objects.equals(a, b);
    }

    @TruffleBoundary
    private static String extractEnumValueName(String s)
    {
        int dotIdx = s.lastIndexOf('.');
        return (dotIdx > 0 && s.contains("::")) ? s.substring(dotIdx + 1) : s;
    }

    @TruffleBoundary
    private static Object normalize(Object v)
    {
        if (v instanceof PureSequence ps && ps.isEmpty())
        {
            return null;
        }
        return v;
    }

}
