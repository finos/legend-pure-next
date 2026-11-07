// Copyright 2026 Goldman Sachs
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

// Hand-written — Pure's Map is a persistent ordered map, not generated.
package org.finos.legend.pure.truffle.execution.types;

import org.eclipse.collections.api.factory.Lists;
import org.eclipse.collections.api.list.MutableList;

/**
 * Runtime implementation of Pure's {@code Map<U,V>}: a {@link PersistentOrderedMap}, insertion-ordered with
 * {@link java.util.LinkedHashMap} semantics. The update natives ({@code put}, {@code putAll}, {@code remove})
 * return a new map sharing structure with this one in O(log n) instead of copying it: {@code put} and
 * {@code putAll} fill a fresh map, whose {@link #putAll} of the source takes its structure whole, and
 * {@link #without} removes. {@link #put} and {@link #putAll} only fill a map a native is building, before it is
 * handed to Pure: a Pure map never changes.
 */
public class MapImpl implements org.finos.legend.pure.truffle.execution.PropertyAccessor
{
    // Fields widened to Object — the typed metamodel interfaces are no
    // longer generated (zero codegen for non-metamodel; metamodel emits
    // XImpls only). All Pure values that flow into these slots are PDOs.
    private Object classifierGenericType;
    private Object sourceInformation;
    private Object elementOverride;

    private PersistentOrderedMap map = PersistentOrderedMap.EMPTY;

    public MapImpl() {}

    private MapImpl(Object classifierGenericType, PersistentOrderedMap map)
    {
        this.classifierGenericType = classifierGenericType;
        this.map = map;
    }

    public Object get(Object key)
    {
        return map.get(key);
    }

    public void put(Object key, Object value)
    {
        map = map.with(key, value);
    }

    public void putAll(MapImpl other)
    {
        map = map.withAll(other.map);
    }

    /** A new map, classified by {@code classifierGenericType}, without {@code key}. */
    public MapImpl without(Object classifierGenericType, Object key)
    {
        return new MapImpl(classifierGenericType, map.without(key));
    }

    public MutableList<Object> keys()
    {
        MutableList<Object> keys = Lists.mutable.withInitialCapacity(map.size());
        map.forEach((k, v) -> keys.add(k));
        return keys;
    }

    public MutableList<Object> values()
    {
        MutableList<Object> values = Lists.mutable.withInitialCapacity(map.size());
        map.forEach((k, v) -> values.add(v));
        return values;
    }

    public int size()
    {
        return map.size();
    }

    /** A read-only {@link java.util.Map} view of the entries, in insertion order. */
    public java.util.Map<Object, Object> getMap()
    {
        return new MapView(map);
    }

    private static final class MapView extends java.util.AbstractMap<Object, Object>
    {
        private final PersistentOrderedMap map;

        MapView(PersistentOrderedMap map)
        {
            this.map = map;
        }

        @Override
        public Object get(Object key)
        {
            return map.get(key);
        }

        @Override
        public boolean containsKey(Object key)
        {
            return map.containsKey(key);
        }

        @Override
        public int size()
        {
            return map.size();
        }

        @Override
        public java.util.Set<java.util.Map.Entry<Object, Object>> entrySet()
        {
            MutableList<java.util.Map.Entry<Object, Object>> entries = Lists.mutable.withInitialCapacity(map.size());
            map.forEach((k, v) -> entries.add(new java.util.AbstractMap.SimpleImmutableEntry<>(k, v)));
            return new java.util.AbstractSet<>()
            {
                @Override
                public java.util.Iterator<java.util.Map.Entry<Object, Object>> iterator()
                {
                    return entries.asUnmodifiable().iterator();
                }

                @Override
                public int size()
                {
                    return entries.size();
                }
            };
        }
    }

    // _classifierGenericType / _sourceInformation / _elementOverride getters
    // and setters now resolve via Any's default methods, which delegate to
    // readProperty/writeProperty on this PropertyAccessor.

    @Override
    public Object readProperty(String name)
    {
        switch (name)
        {
            case "classifierGenericType":
                return classifierGenericType == null ? org.finos.legend.pure.truffle.execution.PropertyAccessor.ABSENT : classifierGenericType;
            case "sourceInformation":
                return sourceInformation == null ? org.finos.legend.pure.truffle.execution.PropertyAccessor.ABSENT : sourceInformation;
            case "elementOverride":
                return elementOverride == null ? org.finos.legend.pure.truffle.execution.PropertyAccessor.ABSENT : elementOverride;
            default:
                return org.finos.legend.pure.truffle.execution.PropertyAccessor.ABSENT;
        }
    }

    @Override
    public void writeProperty(String name, Object value)
    {
        switch (name)
        {
            case "classifierGenericType":
                this.classifierGenericType = value;
                return;
            case "sourceInformation":
                this.sourceInformation = value;
                return;
            case "elementOverride":
                this.elementOverride = value;
                return;
            default:
                throw new IllegalArgumentException("MapImpl has no property named: " + name);
        }
    }

    public MapImpl _copy()
    {
        MapImpl copy = new MapImpl();
        copy.classifierGenericType = this.classifierGenericType;
        copy.map = this.map;
        return copy;
    }
}
