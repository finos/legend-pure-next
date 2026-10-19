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

package org.finos.legend.pure.platform.java.runtime;

import org.finos.legend.pure.m3.platform.pure.functions.collection.ListImpl;
import org.finos.legend.pure.m3.platform.pure.metamodel.valuespecification.AtomicValue;
import org.finos.legend.pure.platform.java.compiler.module.pdbModule.PureLambda;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * How translated code reads Pure values: one value or several (a {@code [*]} is a List, a {@code [1]} the value
 * itself, {@code []} null or an empty List), and Pure equality over them.
 */
public final class PureValues
{
    private PureValues()
    {
    }

    /** A one-element list as its element, absence as the empty list. */
    public static Object norm(Object v)
    {
        if (v == null)
        {
            return Collections.emptyList();
        }
        return v instanceof List && ((List<?>) v).size() == 1 ? ((List<?>) v).get(0) : v;
    }

    public static boolean equal(Object a, Object b)
    {
        Object x = atomicValue(norm(a));
        Object y = atomicValue(norm(b));
        // Pure has one zero: Double.equals tells -0.0 from 0.0, and `%-000.000` parses to a negative zero that
        // must still equal the literal 0.0.
        if (x instanceof Double && y instanceof Double && ((Double) x) == 0.0 && ((Double) y) == 0.0)
        {
            return true;
        }
        // A function reference and the element it names are one Pure element.
        if (same(x, y))
        {
            return true;
        }
        // A Binary is a host byte[], whose natural equals() is identity. Pure equality on a primitive is by
        // VALUE, so compare contents.
        if (x instanceof byte[] && y instanceof byte[])
        {
            return Arrays.equals((byte[]) x, (byte[]) y);
        }
        return Objects.equals(x, y);
    }

    /**
     * An AtomicValue compares as the value it WRAPS: a ValueSpecification lifted out of an expressionSequence
     * equals the literal it holds. Both other hosts with this object model compare that way (Truffle's
     * EqualNode.normalizeForEquals, the JS runtime's __unwrapAtomicValue), so equality is the ONE place that
     * looks through the wrapper — a property read hands back the AtomicValue itself on every host, and nothing
     * else may unwrap it. A loop, not one step: an AtomicValue can wrap another. A wrapper holding [] is not
     * its absent value, so it stays itself.
     */
    public static Object atomicValue(Object v)
    {
        Object x = v;
        while (x instanceof AtomicValue)
        {
            Object inner = one(((AtomicValue) x).value());
            if (inner == null)
            {
                return x;
            }
            x = inner;
        }
        return x;
    }

    /** A lambda's captured variables, from alternating names and values: each value as a Pure List. */
    public static Map<Object, Object> captures(List<?> namesAndValues)
    {
        Map<Object, Object> captures = new LinkedHashMap<>();
        for (int i = 0; i + 1 < namesAndValues.size(); i += 2)
        {
            captures.put(namesAndValues.get(i), new ListImpl()._values(list(namesAndValues.get(i + 1))));
        }
        return captures;
    }

    public static List list(Object v)
    {
        if (v == null)
        {
            return Collections.emptyList();
        }
        return v instanceof List ? (List) v : Collections.singletonList(v);
    }

    public static Object one(Object v)
    {
        if (!(v instanceof List))
        {
            return v;
        }
        List<?> l = (List<?>) v;
        return l.isEmpty() ? null : l.get(0);
    }

    /**
     * {@code toOne}, which is {@link #one} with Pure's contract enforced: NOT exactly one value is an error, not
     * a silent first-element read. Same message and exception as bootstrap's CollectionNatives, Truffle's
     * ToOneNode and the JS runtime, so the text is identical on all four backends.
     */
    public static Object toOne(Object v)
    {
        if (!(v instanceof List))
        {
            return v;
        }
        List<?> l = (List<?>) v;
        if (l.size() != 1)
        {
            throw new RuntimeException("toOne expected exactly 1 element, got " + l.size());
        }
        return l.get(0);
    }

    /**
     * {@code cast(value, @T)}: every value must be a {@code type}, else {@code Cast exception: <its type> cannot be
     * cast to <name>}, its type named as Pure names it.
     */
    public static Object checkedCast(Object value, Class<?> type, String name)
    {
        for (Object v : list(value))
        {
            if (v != null && !type.isInstance(v))
            {
                throw new RuntimeException("Cast exception: " + pureTypeName(v) + " cannot be cast to " + name);
            }
        }
        return value;
    }

    private static String pureTypeName(Object v)
    {
        if (v instanceof Long)
        {
            return "Integer";
        }
        if (v instanceof Double)
        {
            return "Float";
        }
        if (v instanceof BigDecimal)
        {
            return "Decimal";
        }
        if (v instanceof PureDate)
        {
            return v.toString().contains("T") ? "DateTime" : v.toString().length() >= 10 ? "StrictDate" : "Date";
        }
        return v.getClass().getSimpleName();
    }

    /** Identity — with a function reference standing for the element it names. */
    public static boolean same(Object a, Object b)
    {
        Object x = norm(a);
        Object y = norm(b);
        Object ea = PureLambda.elementOf(x);
        Object eb = PureLambda.elementOf(y);
        return (ea == null ? x : ea) == (eb == null ? y : eb);
    }

    /**
     * Java has no unary minus for Number: {@code -n} where n is a function parameter typed Number (Pure's minus
     * accepts any). Each kind keeps its own, so negating an Integer is an Integer.
     */
    public static Object negate(Object v)
    {
        if (v instanceof Long)
        {
            return -((Long) v);
        }
        if (v instanceof Double)
        {
            return -((Double) v);
        }
        if (v instanceof BigDecimal)
        {
            return ((BigDecimal) v).negate();
        }
        if (v instanceof Number)
        {
            return -((Number) v).doubleValue();
        }
        throw new RuntimeException("Cannot negate " + (v == null ? "[]" : v.getClass().getName()));
    }
}
