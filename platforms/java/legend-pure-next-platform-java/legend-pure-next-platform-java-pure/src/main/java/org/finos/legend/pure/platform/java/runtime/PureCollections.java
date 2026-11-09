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

import org.eclipse.collections.api.list.MutableList;
import org.eclipse.collections.impl.list.mutable.FastList;
import org.finos.legend.pure.m3.platform.pure.functions.collection.ListImpl;
import org.finos.legend.pure.m3.platform.pure.functions.collection.PairImpl;

import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Pure's collection functions over the Java Lists translated code holds a {@code [*]} in. A result that is a new
 * collection is a fresh Eclipse Collections {@link FastList} that nothing else references — never a
 * {@code subList} view (Pure-side equality unwraps standard collections but does not recognise SubList views),
 * never a shared or immutable singleton. That is what lets a caller the compiler marks {@code updatable in place} grow the
 * result in place (see {@link #isGrowable}).
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
     * {@code toOneMany(values[, message])}: the values, or an error when there are none — with {@code message},
     * evaluated only then, else as the other platforms word it.
     */
    public static List toOneMany(List<?> values, Supplier<?> message)
    {
        if (values.isEmpty())
        {
            throw new RuntimeException(message == null ? "toOneMany expected at least 1 element, got 0" : String.valueOf(message.get()));
        }
        return values;
    }

    /**
     * {@code map(coll, f)}: Pure's map concatenates the results — a to-many result is flattened and an empty one
     * (null) dropped.
     */
    public static List map(List<?> values, Function f)
    {
        MutableList<Object> out = FastList.newList(values.size());
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
    /**
     * The native {@code sort(col, key, comp)}: {@code key} and {@code comp} are each {@code [0..1]} — a function, or
     * none (null, or the empty list a {@code [0..1]} value holds when empty). Without {@code comp}, natural order;
     * with {@code key}, the values compare by their keys. Stable, as the other platforms are.
     */
    public static List sort(List<?> values, Object key, Object comp)
    {
        Object k = one(key);
        Object c = one(comp);
        Comparator order = c == null
                ? naturalOrder()
                : (x, y) -> Long.signum(((Number) ((BiFunction) c).apply(x, y)).longValue());
        Comparator by = k == null ? order : Comparator.comparing((Function) k, order);
        // toSortedList is a stable sort, as the other platforms are
        return FastList.newList(values).sortThis(by);
    }

    /** A {@code [0..1]} value: itself, or null when it holds none. */
    private static Object one(Object value)
    {
        return value instanceof List<?> list ? (list.isEmpty() ? null : list.get(0)) : value;
    }

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
        return values.isEmpty() ? FastList.newList() : FastList.newList(values.subList(1, values.size()));
    }

    /** {@code init}: everything except the last element; empty stays empty. */
    public static List init(List<?> values)
    {
        return values.isEmpty() ? FastList.newList() : FastList.newList(values.subList(0, values.size() - 1));
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
                return r instanceof List ? (List) r : FastList.newListWith(r);
            }
        }
        return FastList.newList();
    }

    /** {@code slice(coll, start, end)}: [start, end), clamped to the collection as in Pure. */
    public static List slice(List<?> values, long start, long end)
    {
        int s = (int) Math.min(Math.max(0L, start), values.size());
        int e = (int) Math.min(Math.max(s, end), values.size());
        return FastList.newList(values.subList(s, e));
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
        MutableList<Object> out = FastList.newList();
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
        MutableList<Object> out = FastList.newList(n);
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
            groups.computeIfAbsent(key.apply(v), k -> FastList.newList()).add(v);
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
        MutableList<Object> out = FastList.newList();
        List<Object> keys = FastList.newList();
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
        MutableList<Object> out = FastList.newList();
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

    /** {@code add(set, val)}: a fresh list, {@code values} then {@code value}. */
    public static List add(List<?> values, Object value)
    {
        MutableList<Object> out = FastList.newList(values.size() + 1);
        out.addAll(values);
        out.add(value);
        return out;
    }

    /** {@code add(set, offset, val)}: a fresh list with {@code value} inserted at {@code offset}. */
    public static List add(List<?> values, long offset, Object value)
    {
        MutableList<Object> out = FastList.newList(values.size() + 1);
        out.addAll(values);
        out.add((int) offset, value);
        return out;
    }

    /** {@code concatenate(a, b)}: a fresh list, {@code a}'s values then {@code b}'s. */
    public static List concatenate(List<?> a, List<?> b)
    {
        MutableList<Object> out = FastList.newList(a.size() + b.size());
        out.addAll(a);
        out.addAll(b);
        return out;
    }

    /** {@code reverse(coll)}: a fresh list in reverse order. */
    public static List reverse(List<?> values)
    {
        return FastList.newList(values).reverseThis();
    }

    /**
     * {@code add(set, val)} where the compiler marked {@code set} updatable in place (IN_PLACE_UPDATE_ANALYSIS.md): appended in
     * place when it is a list this runtime allocated, else copied as {@link #add(List, Object)}.
     */
    public static List addInPlace(List<?> values, Object value)
    {
        if (values instanceof FastList)
        {
            ((List<Object>) values).add(value);
            return values;
        }
        return add(values, value);
    }

    /** {@code add(set, offset, val)} on an updatable in place {@code set}: in place when growable, else copied. */
    public static List addInPlace(List<?> values, long offset, Object value)
    {
        if (values instanceof FastList)
        {
            ((List<Object>) values).add((int) offset, value);
            return values;
        }
        return add(values, offset, value);
    }

    /** {@code concatenate(a, b)} on an updatable in place {@code a}: {@code b} appended in place when growable, else copied. */
    public static List concatenateInPlace(List<?> a, List<?> b)
    {
        if (a instanceof FastList)
        {
            ((List<Object>) a).addAll(b);
            return a;
        }
        return concatenate(a, b);
    }

    /** {@code reverse(coll)} on an updatable in place {@code coll}: reversed in place when growable, else copied. */
    public static List reverseInPlace(List<?> values)
    {
        if (values instanceof FastList<?> list)
        {
            return list.reverseThis();
        }
        return reverse(values);
    }

    /** {@code add(set, val)} on a {@code set} updatable in place when {@code updatable in place}: in place when also growable, else copied. */
    public static List addIfUpdatableInPlace(List<?> values, Object value, boolean updatableInPlace)
    {
        return updatableInPlace ? addInPlace(values, value) : add(values, value);
    }

    /** {@code add(set, offset, val)} on a {@code set} updatable in place when {@code updatable in place}. */
    public static List addIfUpdatableInPlace(List<?> values, long offset, Object value, boolean updatableInPlace)
    {
        return updatableInPlace ? addInPlace(values, offset, value) : add(values, offset, value);
    }

    /** {@code concatenate(a, b)} on an {@code a} updatable in place when {@code updatable in place}. */
    public static List concatenateIfUpdatableInPlace(List<?> a, List<?> b, boolean updatableInPlace)
    {
        return updatableInPlace ? concatenateInPlace(a, b) : concatenate(a, b);
    }

    /** {@code reverse(coll)} on a {@code coll} updatable in place when {@code updatable in place}. */
    public static List reverseIfUpdatableInPlace(List<?> values, boolean updatableInPlace)
    {
        return updatableInPlace ? reverseInPlace(values) : reverse(values);
    }

    /** A fold step told whether it holds the only reference to its accumulator. */
    @FunctionalInterface
    public interface FoldStep
    {
        Object apply(Object element, Object accumulator, boolean accumulatorUpdatableInPlace);
    }

    /**
     * {@code fold(values, step, init)}, telling each step whether it holds the only reference to its accumulator: the
     * first when the caller passed {@code init} updatable in place, each next one when the previous step's result is updatable in place
     * ({@code stepResultUpdatableInPlace}), or is updatable in place whenever its accumulator was ({@code stepResultUpdatableInPlaceIfAccumulator}). The step may
     * then mutate its accumulator in place. {@code many}: the accumulator is a list, a step's single value is one.
     */
    public static Object fold(List<?> values, Object init, boolean initUpdatableInPlace, boolean stepResultUpdatableInPlace, boolean stepResultUpdatableInPlaceIfAccumulator, boolean many, FoldStep step)
    {
        Object accumulator = init;
        boolean updatableInPlace = initUpdatableInPlace;
        for (Object value : values)
        {
            Object result = step.apply(value, accumulator, updatableInPlace);
            accumulator = many ? asList(result) : result;
            updatableInPlace = stepResultUpdatableInPlace || (stepResultUpdatableInPlaceIfAccumulator && updatableInPlace);
        }
        return accumulator;
    }

    private static List asList(Object value)
    {
        if (value == null)
        {
            return FastList.newList(0);
        }
        return value instanceof List ? (List) value : FastList.newListWith(value);
    }

    /** {@code filter(coll, predicate)}: a fresh list of the values the predicate holds for, in order. */
    public static List filter(List<?> values, Predicate predicate)
    {
        MutableList<Object> out = FastList.newList();
        for (Object v : values)
        {
            if (predicate.test(v))
            {
                out.add(v);
            }
        }
        return out;
    }

    /** {@code take(coll, n)}: the first {@code n} values; a negative count takes nothing. */
    public static List take(List<?> values, long n)
    {
        int end = (int) Math.min(Math.max(0L, n), values.size());
        return FastList.newList(values.subList(0, end));
    }

    /** {@code drop(coll, n)}: all but the first {@code n} values; a negative count drops nothing. */
    public static List drop(List<?> values, long n)
    {
        int start = (int) Math.min(Math.max(0L, n), values.size());
        return FastList.newList(values.subList(start, values.size()));
    }

    /**
     * A list a caller may grow in place: one this runtime allocated ({@link FastList}). Everything else — a PDB
     * value, a value read off an instance (an unmodifiable view), an EC fixed-size or immutable list, a JDK list
     * from outside — is never mutated; an in-place operation copies it instead.
     */
    /**
     * The read-only view a stored list is handed out as (a property's value): reads only, so a read of a property is
     * never a list a caller may grow in place (it is not {@link #isGrowable}), and the list it views is the collection
     * that was stored — what {@code is} compares ({@link #storedList}).
     */
    public static final class StoredList<T> extends java.util.AbstractList<T> implements java.util.RandomAccess
    {
        private final List<T> list;

        private StoredList(List<T> list)
        {
            this.list = list;
        }

        @Override
        public T get(int index)
        {
            return list.get(index);
        }

        @Override
        public int size()
        {
            return list.size();
        }
    }

    /** {@code list} held read-only as a property's value (see {@link StoredList}). */
    @SuppressWarnings("unchecked")
    public static <T> List<T> stored(List<? extends T> list)
    {
        return list instanceof StoredList ? (List<T>) list : new StoredList<>((List<T>) list);
    }

    /** The collection a list is: the one stored, for a property's read-only view of it. */
    public static List<?> storedList(List<?> list)
    {
        return list instanceof StoredList<?> stored ? stored.list : list;
    }

    public static boolean isGrowable(Object values)
    {
        return values instanceof FastList;
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
