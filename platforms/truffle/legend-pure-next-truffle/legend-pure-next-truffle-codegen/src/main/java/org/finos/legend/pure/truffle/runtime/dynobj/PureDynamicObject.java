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
import org.finos.legend.pure.truffle.runtime.TruffleMetadataAccess;

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
     * Lazy DOL-backed read. Cache hit (post-warmup) is a single Shape-folded
     * slot load. Cache miss decodes from {@link #fb} via the per-Pure-class
     * decoder registered in {@link PureFbDecoderRegistry}, then stores the
     * materialised value back so subsequent reads hit the slot.
     *
     * <p>Backwards-compatible fallback: if {@link #fb} implements {@link
     * PropertyAccessor} (the legacy {@code XImpl} construction path), call
     * its {@code readProperty} directly. Lets the loader flip happen
     * incrementally — once the loader switches to constructing {@link
     * PureDynamicObject} with raw {@code XDef} as {@link #fb}, the registry
     * path takes over.</p>
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
        Object decoded = decodeFromFb(name);
        dol.put(this, name, decoded);
        return decoded;
    }

    private Object decodeFromFb(String name)
    {
        if (fb == null)
        {
            return null;
        }
        // Legacy path: fb is an XImpl/XFBW that implements PropertyAccessor.
        // PropertyAccessor.readProperty does the per-class decode switch.
        if (fb instanceof PropertyAccessor accessor)
        {
            Object value = accessor.readProperty(name);
            return value == PropertyAccessor.ABSENT ? null : value;
        }
        // New path: fb is a raw XDef; per-Pure-class decoder is registered in
        // PureFbDecoderRegistry, looked up by Shape's dynamic type.
        Object dt = getShape().getDynamicType();
        if (dt instanceof String purePath)
        {
            return PureFbDecoderRegistry.decode(purePath, name, fb,
                    (TruffleMetadataAccess) resolver, this);
        }
        return null;
    }

    @Override
    @TruffleBoundary
    public boolean equals(Object o)
    {
        if (this == o) return true;
        if (!(o instanceof PureDynamicObject other)) return false;
        // Same Pure type required for equality — compare Shapes directly
        // (Shapes are per-purePath singletons so identity ≡ purePath equality
        // for the root Shape, and Shape transitions still point at the same
        // dynamicType so this stays correct across instance specialization).
        Object meta = getShape().getSharedData();
        Object oMeta = other.getShape().getSharedData();
        if (!(meta instanceof PureShapeRegistry.ShapeMeta m) || meta != oMeta) return false;
        String[] keys = m.equalityKeys;
        if (keys == null || keys.length == 0)
        {
            // No declared equality keys → identity. Matches XImpl's
            // hashCode behaviour (System.identityHashCode when no keys).
            return false;
        }
        for (String k : keys)
        {
            if (!java.util.Objects.equals(readProperty(k), other.readProperty(k))) return false;
        }
        return true;
    }

    @Override
    @TruffleBoundary
    public int hashCode()
    {
        Object meta = getShape().getSharedData();
        if (!(meta instanceof PureShapeRegistry.ShapeMeta m)) return System.identityHashCode(this);
        String[] keys = m.equalityKeys;
        if (keys == null || keys.length == 0) return System.identityHashCode(this);
        int h = 1;
        for (String k : keys)
        {
            Object v = readProperty(k);
            // Match XImpl's hashCode: only String/Number/Boolean contribute
            // (XImpl filters hashProps to primitive types to avoid recursive
            // self-references). For PDO we approximate by hashing primitive
            // values, treating PDO-valued properties as identity-only.
            if (v instanceof String || v instanceof Number || v instanceof Boolean)
            {
                h = 31 * h + v.hashCode();
            }
        }
        return h;
    }

    @Override
    @TruffleBoundary
    public void writeProperty(String name, Object value)
    {
        Object meta = getShape().getSharedData();
        if (meta instanceof PureShapeRegistry.ShapeMeta m)
        {
            Class<?> paramType = m.propTypes.get(name);
            if (paramType != null)
            {
                value = PropertyCoercion.coerce(value, paramType);
            }
        }
        DynamicObjectLibrary.getUncached().put(this, name, value);
    }

}
