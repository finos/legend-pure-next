// Copyright 2026 Goldman Sachs
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0

package org.finos.legend.pure.truffle.runtime.dynobj;

import org.finos.legend.pure.truffle.runtime.TruffleMetadataAccess;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-Pure-class registry of FB-decode logic. Replaces the per-class
 * {@code XPDBHelper} class that previously held both the property storage and
 * the decode switch — after the migration, storage lives in {@link
 * PureDynamicObject}'s {@code DynamicObjectLibrary} slots and decode lives
 * here.
 *
 * <p>Each generated {@code XPDBHelperDecoder.java} registers itself in a
 * {@code static{}} block at class-load time. {@link PureDynamicObject#readProperty}
 * uses {@link #lookup} on cache miss, keyed by the Shape's dynamic type
 * (the Pure-class path string).</p>
 *
 * <p>Decoders are pure functions of their inputs — no per-instance state.
 * The {@code parent} parameter is for {@code AncestorRef} resolution
 * (mirrors the previous FBW {@code _parent} field).</p>
 */
public final class PureFbDecoderRegistry
{
    @FunctionalInterface
    public interface Decoder
    {
        Object decode(String name, Object fb, TruffleMetadataAccess resolver, Object parent);
    }

    private static final ConcurrentHashMap<String, Decoder> DECODERS = new ConcurrentHashMap<>();

    private PureFbDecoderRegistry() {}

    /** Called from each generated {@code XPDBHelperDecoder}'s static initialiser. */
    public static void register(String purePath, Decoder decoder)
    {
        DECODERS.put(purePath, decoder);
    }

    /** Resolved decoder, or {@code null} if no class is registered for this path. */
    public static Decoder lookup(String purePath)
    {
        return DECODERS.get(purePath);
    }

    /**
     * One-shot decode call. Registry miss falls through to a Class.forName
     * load of the corresponding {@code XPDBHelperDecoder} (which triggers its
     * static init); a second registry get returns the now-installed decoder.
     */
    public static Object decode(String purePath, String name, Object fb,
            TruffleMetadataAccess resolver, Object parent)
    {
        Decoder d = DECODERS.get(purePath);
        if (d == null)
        {
            d = lazyLoad(purePath);
        }
        return d != null ? d.decode(name, fb, resolver, parent) : null;
    }

    /** Package-visible wrapper for {@link PureClassInfo#decoder} cache miss. */
    static Decoder lazyLoadPublic(String purePath) { return lazyLoad(purePath); }

    private static Decoder lazyLoad(String purePath)
    {
        // meta::pure::metamodel::type::Class → org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.type.ClassPDBHelper
        // The XPDBHelper class's static{} block registers a decoder lambda that
        // wraps its existing readProperty switch — see PdbJavaGenerator's
        // generateHelper (around the "static\n    {" emission).
        String fqn = "org.finos.legend.pure.truffle.pdb." + purePath.replace("::", ".") + "PDBHelper";
        try
        {
            Class.forName(fqn);
        }
        catch (ClassNotFoundException ignored)
        {
            return null;
        }
        return DECODERS.get(purePath);
    }
}
