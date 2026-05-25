// Hand-written — Pure's Map is backed by java.util.LinkedHashMap, not generated.
package org.finos.legend.pure.truffle.ast.natives.collection;

import org.eclipse.collections.api.factory.Lists;
import org.eclipse.collections.api.list.MutableList;

import java.util.LinkedHashMap;

/**
 * Runtime implementation of Pure's {@code Map<U,V>}.
 * Backed by a {@link LinkedHashMap} for O(1) lookup with insertion-order iteration.
 */
public class MapImpl implements org.finos.legend.pure.truffle.runtime.PropertyAccessor
{
    // Fields widened to Object — the typed metamodel interfaces are no
    // longer generated (zero codegen for non-metamodel; metamodel emits
    // XImpls only). All Pure values that flow into these slots are PDOs.
    private Object classifierGenericType;
    private Object sourceInformation;
    private Object elementOverride;

    private final LinkedHashMap<Object, Object> map = new LinkedHashMap<>();

    public MapImpl() {}

    public Object get(Object key)
    {
        return map.get(key);
    }

    public void put(Object key, Object value)
    {
        map.put(key, value);
    }

    public void putAll(MapImpl other)
    {
        map.putAll(other.map);
    }

    public MutableList<Object> keys()
    {
        return Lists.mutable.withAll(map.keySet());
    }

    public MutableList<Object> values()
    {
        return Lists.mutable.withAll(map.values());
    }

    public int size()
    {
        return map.size();
    }

    public LinkedHashMap<Object, Object> getMap()
    {
        return map;
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
                return classifierGenericType == null ? org.finos.legend.pure.truffle.runtime.PropertyAccessor.ABSENT : classifierGenericType;
            case "sourceInformation":
                return sourceInformation == null ? org.finos.legend.pure.truffle.runtime.PropertyAccessor.ABSENT : sourceInformation;
            case "elementOverride":
                return elementOverride == null ? org.finos.legend.pure.truffle.runtime.PropertyAccessor.ABSENT : elementOverride;
            default:
                return org.finos.legend.pure.truffle.runtime.PropertyAccessor.ABSENT;
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
        copy.map.putAll(this.map);
        return copy;
    }
}
