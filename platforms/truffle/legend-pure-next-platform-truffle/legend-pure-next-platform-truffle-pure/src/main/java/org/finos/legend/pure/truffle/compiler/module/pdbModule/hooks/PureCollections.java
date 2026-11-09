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

package org.finos.legend.pure.truffle.compiler.module.pdbModule.hooks;

import org.eclipse.collections.api.list.MutableList;
import org.eclipse.collections.impl.list.mutable.FastList;

import java.util.List;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * The collection functions the generated PDB reader calls — Truffle's own copies of the Java platform's runtime
 * library methods (platforms/java/…/runtime/PureCollections), kept here so Truffle depends on no other platform.
 */
@SuppressWarnings({"rawtypes", "unchecked"})
public final class PureCollections
{
    private PureCollections()
    {
    }

    /** {@code map(coll, f)}: the results concatenated — a to-many result flattened, an empty one (null) dropped. */
    public static List map(List<?> values, Function f)
    {
        MutableList<Object> out = FastList.newList();
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

    /** {@code at(coll, idx)}: the element at a zero-based offset, with Pure's message when there is none. */
    public static Object at(List<?> values, long index)
    {
        if (index < 0 || index >= values.size())
        {
            throw new RuntimeException("The system is trying to get an element at offset " + index + " where the collection is of size " + values.size());
        }
        return values.get((int) index);
    }

    public static List range(long end)
    {
        return range(0L, end, 1L);
    }

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
}
