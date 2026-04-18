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

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import meta.pure.metamodel.valuespecification.AtomicValue;
import meta.pure.metamodel.valuespecification.Collection;
import meta.pure.metamodel.valuespecification.ValueSpecification;
import org.eclipse.collections.api.list.MutableList;
import org.finos.legend.pure.truffle.types.PureNull;
import org.finos.legend.pure.truffle.types.PureSequence;

/**
 * Shared helpers for specialized collection-native nodes. All of them need
 * to coerce their input to a list of {@link ValueSpecification}s matching
 * Pure's flattened collection semantics: a {@link Collection} exposes its
 * values directly; a non-null {@link AtomicValue} is a one-element list;
 * a null-valued AtomicValue is empty.
 *
 * <p>Hot path is inlined — no virtual dispatch, no TruffleBoundary — so
 * Graal can see through nested calls like {@code size($xs)->plus(1)}.</p>
 */
final class CollectionHelper
{
    private CollectionHelper()
    {
    }

    /**
     * Number of values in {@code v} under Pure's flatten rules.
     */
    static int size(Object v)
    {
        if (v == PureNull.INSTANCE)
        {
            return 0;
        }
        if (v instanceof PureSequence seq)
        {
            return seq.size();
        }
        if (v instanceof Collection col)
        {
            return col._values().size();
        }
        if (v instanceof java.util.List<?> list)
        {
            return list.size();
        }
        if (v instanceof AtomicValue av)
        {
            return av._value() == null ? 0 : 1;
        }
        return slowSize(v);
    }

    /**
     * True iff the value has zero elements.
     */
    static boolean isEmpty(Object v)
    {
        if (v == PureNull.INSTANCE)
        {
            return true;
        }
        if (v instanceof PureSequence seq)
        {
            return seq.isEmpty();
        }
        if (v instanceof Collection col)
        {
            return col._values().isEmpty();
        }
        if (v instanceof java.util.List<?> list)
        {
            return list.isEmpty();
        }
        if (v instanceof AtomicValue av)
        {
            return av._value() == null;
        }
        return slowSize(v) == 0;
    }

    /**
     * Return the element at index {@code i} as a {@link ValueSpecification}.
     * Assumes {@code 0 &le; i &lt; size(v)}; callers bound-check first.
     */
    static Object at(Object v, int i)
    {
        if (v instanceof PureSequence seq)
        {
            return seq.getBoxed(i);
        }
        if (v instanceof Collection col)
        {
            return col._values().get(i);
        }
        if (v instanceof AtomicValue av)
        {
            return av;
        }
        return slowAt(v, i);
    }

    /**
     * Expose {@code v} as a {@code MutableList<ValueSpecification>} for
     * natives that need to walk elements (concatenate, head, tail, last).
     * A single {@link AtomicValue} is returned as a one-element list
     * backed by Eclipse Collections.
     */
    @TruffleBoundary
    static MutableList<ValueSpecification> values(Object v)
    {
        if (v instanceof PureSequence seq)
        {
            Object[] boxed = seq.toBoxedArray();
            MutableList<ValueSpecification> result = org.eclipse.collections.api.factory.Lists.mutable.withInitialCapacity(boxed.length);
            for (Object item : boxed)
            {
                result.add(org.finos.legend.pure.truffle.types.ValueAdapter.ensureVS(item));
            }
            return result;
        }
        if (v instanceof Collection col)
        {
            return col._values();
        }
        if (v instanceof AtomicValue av)
        {
            return av._value() == null
                    ? org.eclipse.collections.api.factory.Lists.mutable.empty()
                    : org.eclipse.collections.api.factory.Lists.mutable.with(av);
        }
        return slowValues(v);
    }

    @TruffleBoundary
    private static int slowSize(Object v)
    {
        return values(v).size();
    }

    @TruffleBoundary
    private static ValueSpecification slowAt(Object v, int i)
    {
        return values(v).get(i);
    }

    @TruffleBoundary
    @SuppressWarnings("unchecked")
    private static MutableList<ValueSpecification> slowValues(Object v)
    {
        if (v == null)
        {
            return org.eclipse.collections.api.factory.Lists.mutable.empty();
        }
        if (v instanceof java.util.List<?> list)
        {
            // Raw unwrapped collection (from _E_ValueSpecification.unwrap
            // on a Collection VS). Re-wrap each element for consumers that
            // expect ValueSpecification items.
            MutableList<ValueSpecification> result = org.eclipse.collections.api.factory.Lists.mutable.withInitialCapacity(list.size());
            for (Object item : list)
            {
                result.add(org.finos.legend.pure.truffle.types.ValueAdapter.ensureVS(item));
            }
            return result;
        }
        if (v instanceof ValueSpecification vs)
        {
            return org.eclipse.collections.api.factory.Lists.mutable.with(vs);
        }
        // Raw scalar (Long, String, etc.) stored directly in a frame slot —
        // treat as a 1-element collection.
        return org.eclipse.collections.api.factory.Lists.mutable.with(
                org.finos.legend.pure.truffle.types.ValueAdapter.ensureVS(v));
    }

    /**
     * Wrap a pre-built list of {@link ValueSpecification}s into a fresh Pure
     * {@code Collection}. Delegates to
     * {@link org.finos.legend.pure.execution.natives.collection.CollectionNatives#makeCollection}
     * so the result's {@code genericType}/{@code multiplicity} are set the
     * same way the bridged natives do.
     */
    @TruffleBoundary
    static ValueSpecification makeCollection(java.util.List<ValueSpecification> items)
    {
        return org.finos.legend.pure.execution.natives.collection.CollectionNatives.makeCollection(
                items,
                org.finos.legend.pure.truffle.runtime.EvaluatorHolder.current().natives().resolver());
    }
}
