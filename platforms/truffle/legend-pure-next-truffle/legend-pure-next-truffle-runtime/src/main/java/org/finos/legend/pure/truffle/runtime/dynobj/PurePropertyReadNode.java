// Copyright 2026 Goldman Sachs
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0

package org.finos.legend.pure.truffle.runtime.dynobj;

import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.GenerateUncached;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.library.CachedLibrary;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.object.DynamicObjectLibrary;

/**
 * Spike: read a Pure property from a {@link PureDynamicObject} via DOL.
 *
 * <p>On a hit (property already materialized in the Shape) this is a single
 * {@code DOL.getOrDefault} call — Truffle inlines it as a direct field read
 * after the receiver's Shape is constant-folded.</p>
 *
 * <p>On a miss (first read of a lazy property), the {@link PureFbDecoder#LAZY}
 * sentinel is returned, we decode from {@code receiver.fb}, write back via
 * {@code DOL.put} (which triggers a Shape transition the first time, then
 * cheap value updates after that), and return the materialized value.</p>
 *
 * <p>Property name is the cache key — {@code @Cached("propertyName")} keeps
 * one specialization per name. Three name slots is enough for any single
 * call site (most are one).</p>
 */
@GenerateUncached
public abstract class PurePropertyReadNode extends Node
{
    public abstract Object execute(PureDynamicObject receiver, String propertyName);

    @Specialization(guards = "propertyName == cachedName", limit = "3")
    static Object readCachedName(PureDynamicObject receiver, @SuppressWarnings("unused") String propertyName,
            @Cached("propertyName") String cachedName,
            @CachedLibrary(limit = "3") DynamicObjectLibrary dol)
    {
        Object value = dol.getOrDefault(receiver, cachedName, PureFbDecoder.LAZY);
        if (value != PureFbDecoder.LAZY)
        {
            return value;
        }
        return materialize(receiver, cachedName, dol);
    }

    @Specialization(replaces = "readCachedName")
    static Object readSlow(PureDynamicObject receiver, String propertyName,
            @CachedLibrary(limit = "3") DynamicObjectLibrary dol)
    {
        Object value = dol.getOrDefault(receiver, propertyName, PureFbDecoder.LAZY);
        if (value != PureFbDecoder.LAZY)
        {
            return value;
        }
        return materialize(receiver, propertyName, dol);
    }

    private static Object materialize(PureDynamicObject receiver, String propertyName, DynamicObjectLibrary dol)
    {
        Object decoded = PureFbDecoder.decode(receiver, propertyName);
        dol.put(receiver, propertyName, decoded);
        return decoded;
    }
}
