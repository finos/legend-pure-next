// Copyright 2026 Goldman Sachs
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0

package org.finos.legend.pure.truffle.runtime.dynobj;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import org.finos.legend.pure.truffle.runtime.PropertyAccessor;

/**
 * Lazy materializer for {@link PureDynamicObject} property reads.
 *
 * <p>The {@code fb} slot on a {@code PureDynamicObject} carries a backing
 * decoder (during the migration this is the existing per-class {@code
 * XFlatBufferWrapper}; post-migration we may inline a thinner reflection-based
 * decoder, but the seam is the same). All FBWs already implement
 * {@link PropertyAccessor} with a generated {@code switch}-on-name body, so a
 * single delegation here covers all ~750 Pure classes — no per-class decoder
 * needed in the runtime/dynobj layer.</p>
 *
 * <p>Pure-built objects ({@code fb == null}) materialise via {@code DOL.put}
 * on construction; {@code decode} is never reached for them.</p>
 */
public final class PureFbDecoder
{
    /** Distinguishes "not yet materialized in Shape" from a stored {@code null}. */
    public static final Object LAZY = new Object();

    private PureFbDecoder() {}

    @TruffleBoundary
    public static Object decode(PureDynamicObject obj, String propertyName)
    {
        if (obj.fb == null)
        {
            // Pure-built; nothing to lazy-decode. Caller stored a null.
            // (We surface ABSENT for names the Shape doesn't have a slot
            // for; here we don't know the property list without a backing
            // FB, so treat as null.)
            return null;
        }
        if (obj.fb instanceof PropertyAccessor accessor)
        {
            // Legacy backing: XImpl / XFBW. Per-class readProperty is the
            // generated decode switch — propagate ABSENT so callers can
            // distinguish "no such property" from "property holds null".
            return accessor.readProperty(propertyName);
        }
        // Post-flip backing: raw XDef. Per-Pure-class decoder lives in
        // PureFbDecoderRegistry, looked up by the PDO's classInfo path.
        return PureFbDecoderRegistry.decode(obj.classInfo.purePath, propertyName, obj.fb,
                (org.finos.legend.pure.truffle.runtime.TruffleMetadataAccess) obj.resolver, obj);
    }
}
