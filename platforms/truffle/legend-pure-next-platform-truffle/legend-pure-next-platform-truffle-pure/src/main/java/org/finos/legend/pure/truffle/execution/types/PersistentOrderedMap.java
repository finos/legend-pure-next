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

package org.finos.legend.pure.truffle.execution.types;

import org.finos.legend.pure.truffle.execution.types.PersistentHashTrie.Entry;

import java.util.function.BiConsumer;

/**
 * An immutable map in insertion order, with the semantics of {@link java.util.LinkedHashMap}: keys by Java
 * {@code equals}/{@code hashCode}, and putting a key already present replaces its value but keeps its place and
 * its first key object. What Pure's {@code Map} is on this platform ({@link MapImpl}).
 *
 * <p>A {@link PersistentHashTrie} finds a key's entry, and a {@link PersistentVector} holds the entries by
 * insertion position (a removed one leaves {@code null}), so iterating needs no lookups. {@link #with} and
 * {@link #without} are O(log n) and share all but one path of each structure with the map they came from:
 * every earlier map stays valid and unchanged, where copying the whole map on every update made a map grown in
 * a loop quadratic.</p>
 */
public final class PersistentOrderedMap
{
    public static final PersistentOrderedMap EMPTY = new PersistentOrderedMap(PersistentHashTrie.EMPTY, PersistentVector.EMPTY, 0);

    // Positions left by removals before the vector is rebuilt without them.
    private static final int MIN_COMPACTION = 32;

    private final PersistentHashTrie index;
    private final PersistentVector order;
    private final int size;

    private PersistentOrderedMap(PersistentHashTrie index, PersistentVector order, int size)
    {
        this.index = index;
        this.order = order;
        this.size = size;
    }

    public int size()
    {
        return size;
    }

    public boolean isEmpty()
    {
        return size == 0;
    }

    /** The value of {@code key}, or null when absent. */
    public Object get(Object key)
    {
        Entry e = index.find(key, PersistentHashTrie.hash(key));
        return e == null ? null : e.value;
    }

    public boolean containsKey(Object key)
    {
        return index.find(key, PersistentHashTrie.hash(key)) != null;
    }

    public PersistentOrderedMap with(Object key, Object value)
    {
        int hash = PersistentHashTrie.hash(key);
        Entry existing = index.find(key, hash);
        if (existing == null)
        {
            Entry entry = new Entry(key, hash, value, order.size());
            return new PersistentOrderedMap(index.with(entry), order.append(entry), size + 1);
        }
        if (existing.value == value)
        {
            return this;
        }
        Entry entry = new Entry(existing.key, hash, value, existing.seq);
        return new PersistentOrderedMap(index.with(entry), order.set(existing.seq, entry), size);
    }

    /** This map with every entry of {@code other} put in its order. */
    public PersistentOrderedMap withAll(PersistentOrderedMap other)
    {
        if (isEmpty())
        {
            return other;
        }
        PersistentOrderedMap[] result = {this};
        other.forEach((k, v) -> result[0] = result[0].with(k, v));
        return result[0];
    }

    public PersistentOrderedMap without(Object key)
    {
        int hash = PersistentHashTrie.hash(key);
        Entry existing = index.find(key, hash);
        if (existing == null)
        {
            return this;
        }
        PersistentOrderedMap removed = new PersistentOrderedMap(index.without(key, hash), order.set(existing.seq, null), size - 1);
        return removed.order.size() > MIN_COMPACTION && removed.size * 2 < removed.order.size() ? removed.compacted() : removed;
    }

    // The same entries, renumbered without the positions removals left.
    private PersistentOrderedMap compacted()
    {
        PersistentOrderedMap[] result = {EMPTY};
        forEach((k, v) -> result[0] = result[0].with(k, v));
        return result[0];
    }

    /** Every entry, in insertion order. */
    public void forEach(BiConsumer<Object, Object> consumer)
    {
        order.forEach(o ->
        {
            if (o != null)
            {
                Entry e = (Entry) o;
                consumer.accept(e.key, e.value);
            }
        });
    }
}
