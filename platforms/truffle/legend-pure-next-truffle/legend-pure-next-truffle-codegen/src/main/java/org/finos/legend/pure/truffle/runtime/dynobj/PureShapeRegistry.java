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

    /**
     * Per-Shape metadata bundle attached via Truffle's {@code sharedData} slot.
     * Pre-resolved at Shape build time so per-write coercion and equality
     * paths avoid the two-level {@link PropertyMetadataRegistry} CHM lookup
     * and the {@code Class.forName} fallback. Lookups are stable for the life
     * of the Shape; Shape transitions inherit {@code sharedData}.
     */
    public static final class ShapeMeta
    {
        public final String purePath;
        public final java.util.Map<String, Class<?>> propTypes;
        public final String[] equalityKeys;

        ShapeMeta(String purePath, java.util.Map<String, Class<?>> propTypes, String[] equalityKeys)
        {
            this.purePath = purePath;
            this.propTypes = propTypes;
            this.equalityKeys = equalityKeys;
        }
    }

    public static Shape shapeFor(String purePath)
    {
        return SHAPES.computeIfAbsent(purePath, PureShapeRegistry::buildShape);
    }

    private static Shape buildShape(String purePath)
    {
        // Trigger XImpl's static{} block so PropertyMetadataRegistry is
        // populated for this Pure class before we snapshot it into the
        // Shape's sharedData. Misses (no XImpl) leave the entries empty,
        // which is fine — writeProperty falls through with no coercion and
        // equals() falls back to identity.
        java.util.Map<String, Class<?>> propTypes = PropertyMetadataRegistry.snapshotTypes(purePath);
        String[] equalityKeys = PropertyMetadataRegistry.getEqualityKeys(purePath);
        ShapeMeta meta = new ShapeMeta(purePath, propTypes, equalityKeys);
        return Shape.newBuilder()
                .layout(PureDynamicObject.class)
                .dynamicType(purePath)
                .sharedData(meta)
                .build();
    }
}
