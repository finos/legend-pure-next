// Copyright 2026 Goldman Sachs
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0

package org.finos.legend.pure.truffle.runtime.dynobj;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.object.DynamicObject;
import com.oracle.truffle.api.object.DynamicObjectLibrary;
import com.oracle.truffle.api.object.Shape;
import org.finos.legend.pure.truffle.runtime.PropertyAccessor;

/**
 * Single object kind for all Pure-on-Truffle metamodel instances.
 *
 * <p>Replaces the per-class XImpl + XFlatBufferWrapper bi-morphic pair with a
 * single class whose Pure type is encoded by its {@link Shape}. Properties live
 * in the Shape's slots; on first access they're materialized from {@link #fb}
 * (when present) via {@link PureFbDecoder} and stored back into the slot —
 * subsequent accesses are normal {@code DynamicObjectLibrary} hits.</p>
 *
 * <p>Implements {@link PropertyAccessor} so the default-method bodies on the
 * generated metamodel interfaces (which delegate {@code _name()} →
 * {@code readProperty("name")}) work without per-class {@code @Override}s.
 * Post-flip, every loaded element is a {@code PureDynamicObject} subclass
 * that implements all generated typed interfaces; the typed call sites keep
 * compiling and running.</p>
 *
 * <p>The {@link #fb} / {@link #resolver} / {@link #parent} fields are plain
 * Java finals (not Shape slots) — PE inlines them as constants. {@link #fb}
 * is null for Pure-built instances (e.g. {@code ^Class(name="Foo")}); FB-loaded
 * instances carry the FlatBuffer struct pointer.</p>
 */
public class PureDynamicObject extends DynamicObject implements PropertyAccessor
{
    /** FB-generated {@code XDef} struct (e.g. {@code PropertyDef}); null for Pure-built. */
    public final Object fb;

    /** Resolver for cross-element references in lazy decode (PointerRef, etc.). */
    public final Object resolver;

    /** Parent for {@code AncestorRef} resolution; null for top-level / Pure-built. */
    public final Object parent;

    public PureDynamicObject(Shape shape, Object fb, Object resolver, Object parent)
    {
        super(shape);
        this.fb = fb;
        this.resolver = resolver;
        this.parent = parent;
    }

    /**
     * Lazy DOL-backed read — same shape as {@link PureObj#read} but on the
     * receiver itself. Cache hit (post-warmup) is a single Shape-folded slot
     * load. Cache miss decodes from {@link #fb} via {@link PureFbDecoder}
     * and stores the materialised value, so subsequent reads hit the slot.
     */
    @Override
    @TruffleBoundary
    public Object readProperty(String name)
    {
        DynamicObjectLibrary dol = DynamicObjectLibrary.getUncached();
        Object cached = dol.getOrDefault(this, name, PureFbDecoder.LAZY);
        if (cached != PureFbDecoder.LAZY)
        {
            return cached;
        }
        Object decoded = PureFbDecoder.decode(this, name);
        dol.put(this, name, decoded);
        return decoded;
    }

    @Override
    @TruffleBoundary
    public void writeProperty(String name, Object value)
    {
        DynamicObjectLibrary.getUncached().put(this, name, value);
    }
}
