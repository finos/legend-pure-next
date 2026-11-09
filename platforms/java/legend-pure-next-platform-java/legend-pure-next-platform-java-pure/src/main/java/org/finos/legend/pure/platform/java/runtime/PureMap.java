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
import org.finos.legend.pure.m3.platform.pure.functions.collection.PairImpl;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Pure's Map functions over a Java Map. Pure Maps are immutable, so every change returns a new map (insertion
 * ordered, as Pure's are): a {@link PersistentOrderedMap}, which shares structure with the map it came from in
 * O(log n) where copying the whole map made a map grown in a loop quadratic. A map from elsewhere is converted
 * on its first change. Methods use raw types so they accept whatever the call site passes — Pure's generics
 * erase at the Java boundary anyway.
 */
@SuppressWarnings({"rawtypes", "unchecked"})
public final class PureMap
{
    private PureMap()
    {
    }

    public static Map newMap(Object entries)
    {
        return withEntries(PersistentOrderedMap.EMPTY, entries);
    }

    public static Object get(Map m, Object key)
    {
        return m == null ? null : m.get(key);
    }

    public static Map put(Map m, Object key, Object value)
    {
        return PersistentOrderedMap.of(m).with(key, value);
    }

    public static Map putAll(Map m, Object additions)
    {
        PersistentOrderedMap map = PersistentOrderedMap.of(m);
        return additions instanceof Map ? map.withAll((Map) additions) : withEntries(map, additions);
    }

    public static Map remove(Map m, Object key)
    {
        return PersistentOrderedMap.of(m).without(key);
    }

    public static List keys(Map m)
    {
        return FastList.newList(m.keySet());
    }

    public static List values(Map m)
    {
        return FastList.newList(m.values());
    }

    /** Pure pairs, not Java entries: {@code $map->keyValues()} is a Pair[*]. */
    public static List keyValues(Map m)
    {
        MutableList<Object> pairs = FastList.newList(m.size());
        for (Map.Entry<?, ?> e : ((Map<?, ?>) m).entrySet())
        {
            pairs.add(new PairImpl()._first(e.getKey())._second(e.getValue()));
        }
        return pairs;
    }

    public static Map replaceAll(Map m, Object pairs)
    {
        return newMap(pairs);
    }

    /**
     * Get-or-compute. The name suggests the value is also stored, but Pure Maps are immutable — the caller
     * would not see the change anyway — so the value is only computed on a miss.
     */
    public static Object getIfAbsentPutWithKey(Map m, Object key, Function fn)
    {
        return m.containsKey(key) ? m.get(key) : fn.apply(key);
    }

    /** {@code map} with a List of Map.Entry, or a single one, put in order. */
    private static PersistentOrderedMap withEntries(PersistentOrderedMap map, Object entries)
    {
        if (entries == null)
        {
            return map;
        }
        List list = entries instanceof List ? (List) entries : FastList.newListWith(entries);
        PersistentOrderedMap result = map;
        for (Object e : list)
        {
            Map.Entry pair = (Map.Entry) e;
            result = result.with(pair.getKey(), pair.getValue());
        }
        return result;
    }
}
