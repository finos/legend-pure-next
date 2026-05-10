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
import org.finos.legend.pure.truffle.runtime.PropertyAccessor;

/**
 * Universal Pure-property reader for hot AST sites — works against
 * <em>both</em> the legacy generated metamodel classes ({@code XImpl},
 * {@code XFlatBufferWrapper} — both implement {@link PropertyAccessor})
 * <em>and</em> the new {@link PureDynamicObject} that the loader flip will
 * eventually emit. Single call site, polymorphic via Truffle DSL
 * {@code @Specialization}.
 *
 * <p>Pre-flip the legacy specialization wins — calls go through the FBW's
 * generated {@code switch}-on-name body in {@link PropertyAccessor#readProperty},
 * roughly the same cost as the typed-getter path it replaced. Post-flip the
 * {@link PureDynamicObject} specialization takes over — uses the cached
 * {@link DynamicObjectLibrary} for the Shape-folded direct slot read (5.5 ns
 * in the spike microbench, beats the typed-getter's 6.3 ns). The migration
 * is therefore safe to apply at hot AST sites <em>before</em> flipping the
 * loader, and the perf wins materialise automatically afterwards without
 * further code changes.</p>
 *
 * <p>Property name is cached with {@code @Cached("propertyName")} so each
 * specialization slot bakes in a constant name; the inner switch / DOL
 * lookup constant-folds against it. Three slots covers polymorphism of name
 * at any single call site (most are monomorphic).</p>
 */
@GenerateUncached
public abstract class PurePropertyAccessNode extends Node
{
    public abstract Object execute(Object receiver, String propertyName);

    /**
     * New path: {@link PureDynamicObject} backed by {@link DynamicObjectLibrary}.
     * Lazy materialisation: first read decodes from the Shape's {@code fb}
     * slot via {@link PureFbDecoder} and stores it back; subsequent reads
     * are direct slot loads.
     */
    @Specialization(guards = "propertyName == cachedName", limit = "3")
    static Object readDynamic(PureDynamicObject receiver,
            @SuppressWarnings("unused") String propertyName,
            @Cached("propertyName") String cachedName,
            @CachedLibrary(limit = "3") DynamicObjectLibrary dol)
    {
        Object value = dol.getOrDefault(receiver, cachedName, PureFbDecoder.LAZY);
        if (value != PureFbDecoder.LAZY)
        {
            return value;
        }
        Object decoded = PureFbDecoder.decode(receiver, cachedName);
        dol.put(receiver, cachedName, decoded);
        return decoded;
    }

    /**
     * Legacy path: {@code XImpl} / {@code XFlatBufferWrapper} implementing
     * {@link PropertyAccessor#readProperty}. Generated as a {@code switch}
     * on the property name; PE constant-folds against the {@code cachedName}
     * to a direct typed accessor (e.g. {@code _name()}) — equivalent to the
     * pre-migration call site after warm-up.
     */
    @Specialization(guards = "propertyName == cachedName", limit = "3")
    static Object readLegacy(PropertyAccessor receiver,
            @SuppressWarnings("unused") String propertyName,
            @Cached("propertyName") String cachedName)
    {
        Object value = receiver.readProperty(cachedName);
        return value == PropertyAccessor.ABSENT ? null : value;
    }

    /**
     * Megamorphic fallback — covers receivers that are neither (e.g. raw
     * primitives accidentally routed here). Mirrors {@link PureObj#read}.
     */
    @Specialization(replaces = {"readDynamic", "readLegacy"})
    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    static Object readSlow(Object receiver, String propertyName)
    {
        return PureObj.read(receiver, propertyName);
    }
}
