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
import org.finos.legend.pure.m3.platform.pure.functions.collection.PairImpl;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Pure's collection functions over the Java Lists translated code holds a {@code [*]} in. A result is a fresh
 * ArrayList, never a {@code subList} view: Pure-side equality unwraps standard collections but does not
 * recognise SubList views.
 */
@SuppressWarnings({"rawtypes", "unchecked"})
public final class PureCollections
{
    private PureCollections()
    {
    }

    /** {@code toOne(values, message)}: the only value, or an error with {@code message}, evaluated only then. */
    public static Object toOne(List<?> values, Supplier<?> message)
    {
        if (values.size() != 1)
        {
            throw new RuntimeException(String.valueOf(message.get()));
        }
        return values.get(0);
    }

    /**
     * {@code map(coll, f)}: Pure's map concatenates the results — a to-many result is flattened and an empty one
     * (null) dropped.
     */
    public static List map(List<?> values, Function f)
    {
        List<Object> out = new ArrayList<>();
        for (Object v : values)
        {
            Object r = f.apply(v);
            if (r instanceof List)
            {
                out.addAll((List<?>) r);
            }
            else if (r != null)
            {
                out.add(r);
            }
        }
        return out;
    }

    /**
     * The order {@code sort} uses without a comparator. Mixed numbers compare by value: Pure sorts [342, 5.0, -2.0]
     * numerically, where Long.compareTo(Double) would throw ClassCastException. Other values of different classes
     * order by class name first, as on the Truffle runtime (['aaa', 2] sorts without failing).
     */
    public static Comparator naturalOrder()
    {
        return (a, b) ->
        {
            if (a instanceof Number && b instanceof Number && !(a instanceof Long && b instanceof Long))
            {
                return Double.compare(((Number) a).doubleValue(), ((Number) b).doubleValue());
            }
            if (a.getClass() != b.getClass() && !(a instanceof Number && b instanceof Number))
            {
                return a.getClass().getName().compareTo(b.getClass().getName());
            }
            return ((Comparable) a).compareTo(b);
        };
    }

    /** {@code compare(a, b)} of values of unrelated types: by their Java class name first, as on the Truffle runtime. */
    public static long compare(Object a, Object b)
    {
        int c = a.getClass().getName().compareTo(b.getClass().getName());
        if (c == 0)
        {
            c = ((Comparable) a).compareTo(b);
        }
        return Integer.signum(c);
    }

    /** {@code tail}: everything except the first element; empty stays empty. */
    public static List tail(List<?> values)
    {
        return values.isEmpty() ? new ArrayList() : new ArrayList(values.subList(1, values.size()));
    }

    /** {@code init}: everything except the last element; empty stays empty. */
    public static List init(List<?> values)
    {
        return values.isEmpty() ? new ArrayList() : new ArrayList(values.subList(0, values.size() - 1));
    }

    /**
     * {@code firstNonEmpty(thunks)}: the first non-empty result of the 0-argument suppliers, or empty if all
     * are. Null and an empty collection are empty; a scalar is always a value.
     */
    public static List firstNonEmpty(List<?> thunks)
    {
        for (Object thunk : thunks)
        {
            Object r = ((Supplier<?>) thunk).get();
            if (r != null && !(r instanceof Collection && ((Collection<?>) r).isEmpty()))
            {
                return r instanceof List ? (List) r : Collections.singletonList(r);
            }
        }
        return Collections.emptyList();
    }

    /** {@code slice(coll, start, end)}: [start, end), clamped to the collection as in Pure. */
    public static List slice(List<?> values, long start, long end)
    {
        int s = (int) Math.min(Math.max(0L, start), values.size());
        int e = (int) Math.min(Math.max(s, end), values.size());
        return new ArrayList<Object>(values.subList(s, e));
    }

    /** {@code range(end)}: 0 to {@code end}, exclusive. */
    public static List range(long end)
    {
        return range(0L, end, 1L);
    }

    /** {@code range(start, end)}. */
    public static List range(long start, long end)
    {
        return range(start, end, 1L);
    }

    /** {@code range(start, end, step)}: towards {@code end}, exclusive, by {@code step}; a negative step counts down. */
    public static List range(long start, long end, long step)
    {
        if (step == 0L)
        {
            throw new IllegalArgumentException("range step must not be 0");
        }
        List<Object> out = new ArrayList<>();
        for (long i = start; step > 0L ? i < end : i > end; i += step)
        {
            out.add(i);
        }
        return out;
    }

    /** {@code zip(a, b)}: element-wise Pure pairs, as long as the shorter list. A Pair prints `<1, a>`. */
    public static List zip(List<?> a, List<?> b)
    {
        int n = Math.min(a.size(), b.size());
        List<Object> out = new ArrayList<>();
        for (int i = 0; i < n; i++)
        {
            out.add(new PairImpl()._first(a.get(i))._second(b.get(i)));
        }
        return out;
    }

    /** {@code groupBy(collection, key)}: each key to the Pure List of the elements with that key, in encounter order. */
    public static Map groupBy(List<?> values, Function key)
    {
        Map<Object, List<Object>> groups = new LinkedHashMap<>();
        for (Object v : values)
        {
            groups.computeIfAbsent(key.apply(v), k -> new ArrayList<>()).add(v);
        }
        Map<Object, Object> out = new LinkedHashMap<>();
        for (Map.Entry<Object, List<Object>> e : groups.entrySet())
        {
            out.put(e.getKey(), new ListImpl()._values(e.getValue()));
        }
        return out;
    }

    /** {@code contains(col, value, comparator)}: some element for which comparator(value, element) holds. */
    public static boolean contains(List<?> values, Object value, BiFunction comparator)
    {
        for (Object v : values)
        {
            if ((Boolean) comparator.apply(value, v))
            {
                return true;
            }
        }
        return false;
    }

    /**
     * {@code removeDuplicates(col[, key][, eql])}: a value is dropped when its key (the value itself without
     * {@code key}) equals the key of one already kept — by {@code eql(keptKey, key)} when given, else
     * Objects.equals. Order preserved.
     */
    public static List removeDuplicates(List<?> values, Function key, BiFunction eql)
    {
        List<Object> out = new ArrayList<>();
        List<Object> keys = new ArrayList<>();
        for (Object v : values)
        {
            Object k = key == null ? v : key.apply(v);
            if (!seen(keys, k, eql))
            {
                out.add(v);
                keys.add(k);
            }
        }
        return out;
    }

    private static boolean seen(List<Object> keys, Object k, BiFunction eql)
    {
        for (Object kept : keys)
        {
            if (eql == null ? Objects.equals(kept, k) : (Boolean) eql.apply(kept, k))
            {
                return true;
            }
        }
        return false;
    }

    /**
     * {@code removeAll(set, other)}: order-preserving difference, compared with {@link PureValues#equal} — so an
     * {@code <<equality.Key>>} class matches a freshly built instance by its keys — as bootstrap's
     * CollectionNatives and Truffle's RemoveAllNode do.
     */
    public static List removeAll(List<?> values, List<?> drop)
    {
        List<Object> out = new ArrayList<>();
        for (Object v : values)
        {
            if (!containsEqual(drop, v))
            {
                out.add(v);
            }
        }
        return out;
    }

    private static boolean containsEqual(List<?> values, Object v)
    {
        for (Object d : values)
        {
            if (PureValues.equal(v, d))
            {
                return true;
            }
        }
        return false;
    }

    /** {@code at(coll, idx)}: the element at a zero-based offset, with Pure's message when there is none. */
    public static Object at(List<?> values, long index)
    {
        if (index < 0 || index >= values.size())
        {
            throw new RuntimeException("The system is trying to get an element at offset " + index + " where the collection is of size " + values.size());
        }
        return values.get((int) index);
    }
}
