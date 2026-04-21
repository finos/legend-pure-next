// Hand-written — Pure's Map is backed by java.util.LinkedHashMap, not generated.
package org.finos.legend.pure.truffle.pdb.meta.pure.functions.collection;

import org.eclipse.collections.api.factory.Lists;
import org.eclipse.collections.api.list.MutableList;

import java.util.LinkedHashMap;

/**
 * Runtime implementation of Pure's {@code Map<U,V>}.
 * Backed by a {@link LinkedHashMap} for O(1) lookup with insertion-order iteration.
 */
public class MapImpl implements org.finos.legend.pure.truffle.pdb.meta.pure.functions.collection.Map
{
    private org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.type.generics.GenericTypeValue classifierGenericType;
    private org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.SourceInformation sourceInformation;
    private org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.type.ElementOverride elementOverride;

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

    @Override
    public org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.type.generics.GenericTypeValue _classifierGenericType()
    {
        return this.classifierGenericType;
    }

    @Override
    public MapImpl _classifierGenericType(org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.type.generics.GenericTypeValue value)
    {
        this.classifierGenericType = value;
        return this;
    }

    @Override
    public org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.SourceInformation _sourceInformation()
    {
        return this.sourceInformation;
    }

    @Override
    public MapImpl _sourceInformation(org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.SourceInformation value)
    {
        this.sourceInformation = value;
        return this;
    }

    @Override
    public org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.type.ElementOverride _elementOverride()
    {
        return this.elementOverride;
    }

    @Override
    public MapImpl _elementOverride(org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.type.ElementOverride value)
    {
        this.elementOverride = value;
        return this;
    }

    @Override
    public MapImpl _copy()
    {
        MapImpl copy = new MapImpl();
        copy.classifierGenericType = this.classifierGenericType;
        copy.map.putAll(this.map);
        return copy;
    }
}
