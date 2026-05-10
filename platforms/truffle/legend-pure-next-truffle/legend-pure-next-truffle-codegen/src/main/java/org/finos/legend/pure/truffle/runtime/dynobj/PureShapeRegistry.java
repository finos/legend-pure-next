// Copyright 2026 Goldman Sachs
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0

package org.finos.legend.pure.truffle.runtime.dynobj;

import com.oracle.truffle.api.object.Shape;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Per–Pure-class {@link Shape} registry.
 *
 * <p>Each Pure Class gets exactly one {@code Shape} via {@link #shapeFor(String)};
 * all {@link PureDynamicObject} instances of that Pure type are constructed
 * against the same Shape. The Shape carries the Pure class path as a {@code
 * dynamicType} (constant accessible to PE), plus per-property slots populated
 * lazily as properties are first read.</p>
 *
 * <p>Spike note: properties are added to the Shape on first {@code DOL.put} —
 * Shape transitions converge as access patterns warm up. We don't pre-declare
 * all properties upfront; if Shape explosion shows up in PE compiles we'll
 * pre-warm at construction.</p>
 */
public final class PureShapeRegistry
{
    private static final ConcurrentHashMap<String, Shape> SHAPES = new ConcurrentHashMap<>();

    private PureShapeRegistry() {}

    public static Shape shapeFor(String purePath)
    {
        return SHAPES.computeIfAbsent(purePath, PureShapeRegistry::buildShape);
    }

    private static Shape buildShape(String purePath)
    {
        return Shape.newBuilder()
                .layout(PureDynamicObject.class)
                .dynamicType(purePath)
                .build();
    }
}
