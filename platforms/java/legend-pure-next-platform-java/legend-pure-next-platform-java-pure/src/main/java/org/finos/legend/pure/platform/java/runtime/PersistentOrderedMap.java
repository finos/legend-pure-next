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

import org.eclipse.collections.api.factory.Lists;
import org.eclipse.collections.api.list.MutableList;

import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;

/**
 * An immutable map in insertion order, with the semantics of {@link java.util.LinkedHashMap}: keys by Java
 * {@code equals}/{@code hashCode}, and putting a key already present replaces its value but keeps its place and
 * its first key object. What Pure's {@code Map} is on this platform ({@link PureMap}); as a read-only
 * {@link Map}, it equals any map with the same entries.
 *
 * <p>A {@link PersistentHashTrie} finds a key's entry, and a {@link PersistentVector} holds the entries by
 * insertion position (a removed one leaves {@code null}), so iterating needs no lookups. {@link #with} and
 * {@link #without} are O(log n) and share all but one path of each structure with the map they came from:
 * every earlier map stays valid and unchanged, where copying the whole map on every update made a map grown in
 * a loop quadratic.</p>
 */
public final class PersistentOrderedMap extends AbstractMap<Object, Object>
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

    /** {@code map} itself when it is one, else its entries in its iteration order. */
    public static PersistentOrderedMap of(Map<?, ?> map)
    {
        if (map instanceof PersistentOrderedMap p)
        {
            return p;
        }
        PersistentOrderedMap result = EMPTY;
        for (Map.Entry<?, ?> e : map.entrySet())
        {
            result = result.with(e.getKey(), e.getValue());
        }
        return result;
    }

    @Override
    public int size()
    {
        return size;
    }

    @Override
    public boolean isEmpty()
    {
        return size == 0;
    }

    @Override
    public Object get(Object key)
    {
        PersistentHashTrie.Entry e = index.find(key, PersistentHashTrie.hash(key));
        return e == null ? null : e.value;
    }

    @Override
    public boolean containsKey(Object key)
    {
        return index.find(key, PersistentHashTrie.hash(key)) != null;
    }

    public PersistentOrderedMap with(Object key, Object value)
    {
        int hash = PersistentHashTrie.hash(key);
        PersistentHashTrie.Entry existing = index.find(key, hash);
        if (existing == null)
        {
            PersistentHashTrie.Entry entry = new PersistentHashTrie.Entry(key, hash, value, order.size());
            return new PersistentOrderedMap(index.with(entry), order.append(entry), size + 1);
        }
        if (existing.value == value)
        {
            return this;
        }
        PersistentHashTrie.Entry entry = new PersistentHashTrie.Entry(existing.key, hash, value, existing.seq);
        return new PersistentOrderedMap(index.with(entry), order.set(existing.seq, entry), size);
    }

    /** This map with every entry of {@code other} put in its order. */
    public PersistentOrderedMap withAll(Map<?, ?> other)
    {
        if (isEmpty())
        {
            return of(other);
        }
        PersistentOrderedMap result = this;
        for (Map.Entry<?, ?> e : other.entrySet())
        {
            result = result.with(e.getKey(), e.getValue());
        }
        return result;
    }

    public PersistentOrderedMap without(Object key)
    {
        int hash = PersistentHashTrie.hash(key);
        PersistentHashTrie.Entry existing = index.find(key, hash);
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
        forEachEntry((k, v) -> result[0] = result[0].with(k, v));
        return result[0];
    }

    /** Every entry, in insertion order. */
    public void forEachEntry(BiConsumer<Object, Object> consumer)
    {
        order.forEach(o ->
        {
            if (o != null)
            {
                PersistentHashTrie.Entry e = (PersistentHashTrie.Entry) o;
                consumer.accept(e.key, e.value);
            }
        });
    }

    @Override
    public Set<Map.Entry<Object, Object>> entrySet()
    {
        MutableList<Map.Entry<Object, Object>> entries = Lists.mutable.withInitialCapacity(size);
        forEachEntry((k, v) -> entries.add(new SimpleImmutableEntry<>(k, v)));
        MutableList<Map.Entry<Object, Object>> readOnly = entries.asUnmodifiable();
        return new AbstractSet<>()
        {
            @Override
            public Iterator<Map.Entry<Object, Object>> iterator()
            {
                return readOnly.iterator();
            }

            @Override
            public int size()
            {
                return readOnly.size();
            }
        };
    }
}
