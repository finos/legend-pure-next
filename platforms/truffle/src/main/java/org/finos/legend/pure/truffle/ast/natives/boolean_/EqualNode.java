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

import java.util.Objects;

/**
 * {@code equal(Any[*], Any[*]) : Boolean[1]}.
 *
 * <p>Evaluates both children to raw values, then performs deep equality.</p>
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
        Object rawA = normalizeForEquals(a);
        Object rawB = normalizeForEquals(b);
        return callPureEquals(rawA, rawB);
    }

    @TruffleBoundary
    private static boolean callPureEquals(Object a, Object b)
    {
        if (a == b)
        {
            return true;
        }
        if (a == null || b == null)
        {
            return false;
        }
        // Numeric coercion: compare numbers by value
        if (a instanceof Number na && b instanceof Number nb)
        {
            if (a instanceof Long && b instanceof Long)
            {
                return na.longValue() == nb.longValue();
            }
            double da = na.doubleValue();
            double db = nb.doubleValue();
            // Normalize -0.0 to 0.0
            if (da == 0.0) da = 0.0;
            if (db == 0.0) db = 0.0;
            return Double.compare(da, db) == 0;
        }
        // List comparison
        if (a instanceof java.util.List<?> la && b instanceof java.util.List<?> lb)
        {
            if (la.size() != lb.size())
            {
                return false;
            }
            for (int i = 0; i < la.size(); i++)
            {
                Object ea = normalizeForEquals(la.get(i));
                Object eb = normalizeForEquals(lb.get(i));
                if (!callPureEquals(ea, eb))
                {
                    return false;
                }
            }
            return true;
        }
        // Map comparison — deep equality by entries
        if (a instanceof meta.pure.functions.collection.MapImpl ma
                && b instanceof meta.pure.functions.collection.MapImpl mb)
        {
            if (ma.size() != mb.size())
            {
                return false;
            }
            for (var entry : ma.getMap().entrySet())
            {
                Object bVal = mb.get(entry.getKey());
                if (!callPureEquals(entry.getValue(), bVal))
                {
                    return false;
                }
            }
            return true;
        }
        // Enum equality — compare by name (including cross-type with String)
        if (a instanceof meta.pure.metamodel.type.Enum ea)
        {
            if (b instanceof meta.pure.metamodel.type.Enum eb)
            {
                return Objects.equals(ea._name(), eb._name());
            }
            if (b instanceof String s)
            {
                return Objects.equals(ea._name(), s);
            }
        }
        if (b instanceof meta.pure.metamodel.type.Enum eb && a instanceof String s)
        {
            return Objects.equals(s, eb._name());
        }
        // Generated Impl equality — compare by classifierGenericType + all property values
        if (a instanceof meta.pure.metamodel.type.Any anyA
                && b instanceof meta.pure.metamodel.type.Any anyB)
        {
            if (a.getClass() == b.getClass())
            {
                return equalByProperties(anyA, anyB);
            }
        }
        return Objects.equals(a, b);
    }

    private static boolean equalByProperties(meta.pure.metamodel.type.Any a, meta.pure.metamodel.type.Any b)
    {
        // Compare all getter methods (_xxx()) that return property values
        for (java.lang.reflect.Method m : a.getClass().getMethods())
        {
            String name = m.getName();
            if (name.startsWith("_") && m.getParameterCount() == 0
                    && !name.equals("_classifierGenericType") && !name.equals("_sourceInformation")
                    && !name.equals("_elementOverride") && !name.equals("_copy")
                    && !name.equals("_class") && !name.equals("_hashCode"))
            {
                try
                {
                    Object va = m.invoke(a);
                    Object vb = m.invoke(b);
                    if (!callPureEquals(normalizeForEquals(va), normalizeForEquals(vb)))
                    {
                        return false;
                    }
                }
                catch (Exception ignored)
                {
                }
            }
        }
        return true;
    }

    private static Object normalizeForEquals(Object v)
    {
        if (v instanceof org.finos.legend.pure.truffle.types.PureSequence seq)
        {
            if (seq.size() == 0)
            {
                return null;
            }
            if (seq.size() == 1)
            {
                return normalizeForEquals(seq.getBoxed(0));
            }
            return java.util.Arrays.asList(seq.toBoxedArray());
        }
        if (v instanceof org.finos.legend.pure.truffle.types.PureNull)
        {
            return null;
        }
        // MutableList normalization
        if (v instanceof org.eclipse.collections.api.list.MutableList<?> ml)
        {
            if (ml.isEmpty())
            {
                return null;
            }
            if (ml.size() == 1)
            {
                return normalizeForEquals(ml.getFirst());
            }
            return ml;
        }
        // Unwrap AtomicValue (dates kept as AV)
        if (v instanceof meta.pure.metamodel.valuespecification.AtomicValue av)
        {
            Object inner = av._value();
            return inner != null ? inner : null;
        }
        // Unwrap Collection (legacy VS — shouldn't appear but handle defensively)
        if (v instanceof meta.pure.metamodel.valuespecification.Collection col && col._values() != null)
        {
            if (col._values().size() == 1)
            {
                return normalizeForEquals(col._values().getFirst());
            }
        }
        return v;
    }
}
