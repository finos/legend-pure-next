// Copyright 2026 Goldman Sachs
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0

package org.finos.legend.pure.truffle.ast;

import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.nodes.Node;
import org.finos.legend.pure.truffle.PureLanguage;
import org.finos.legend.pure.truffle.runtime.PropertyAccessor;
import org.finos.legend.pure.truffle.runtime.dynobj.PureClassRegistry;
import org.finos.legend.pure.truffle.runtime.dynobj.PureDynamicObject;
import org.finos.legend.pure.truffle.runtime.dynobj.PureFbDecoder;
import org.finos.legend.pure.truffle.runtime.helper._PackageableElement;
import org.finos.legend.pure.truffle.types.PureSequence;

/**
 * Helper for dynamic-name property reads ({@code execute(target, propName)} —
 * propName from a runtime expression) and bound-name reads (literal name
 * baked at AST-build time via {@link #PropertyReadNode(String)}).
 *
 * <p>Post custom-storage pivot, reads go through {@link PureDynamicObject#readProperty}
 * (slot lookup via {@code classInfo.slotIndex}) or the legacy
 * {@link PropertyAccessor#readProperty} for non-PDO targets like
 * {@link org.finos.legend.pure.truffle.ast.natives.collection.MapImpl}.</p>
 *
 * <p>{@link #ABSENT} is the sentinel returned by {@link #executeOrAbsent} for
 * "no such property on this class" — callers that need to distinguish that
 * from a present-but-null property use the {@code OrAbsent} variant.</p>
 */
public final class PropertyReadNode extends Node
{
    public static final Object ABSENT = PropertyAccessor.ABSENT;

    @CompilationFinal
    private final String boundName;

    /** Slot index when {@link #boundName} is set — global, baked once and
     *  valid for every PDO receiver. {@code -1} when not bound. */
    private final int boundSlot;

    public PropertyReadNode()
    {
        this.boundName = null;
        this.boundSlot = -1;
    }

    public PropertyReadNode(String propertyName)
    {
        this.boundName = propertyName;
        this.boundSlot = PureClassRegistry.globalSlot(propertyName);
    }

    /**
     * Read returning {@link org.finos.legend.pure.truffle.types.PureSequence#EMPTY}
     * for "not present" or "unset". Uses the blind-slot fast path: even when
     * this receiver class doesn't declare the property, {@code slots[]} is
     * either out-of-range (returns null → normalized to EMPTY) or holds
     * null (unset → EMPTY) — both correct under "execute" semantics.
     */
    public Object execute(Object target, String propName)
    {
        // Lazy TempCompilerPointer dereference on the way out: compile-pure
        // embeds Class / PackageableFunction references in element slots as
        // path-only pointers to avoid freezing pass-1 skeletons; consumers
        // (properties(), pathToElement(), instanceOf, …) expect live elements.
        // Per-read deref keeps it lazy — pay only when a pointer surfaces.
        var resolver = PureLanguage.get(null).resolver();
        if (boundName != null && target instanceof PureDynamicObject pdo)
        {
            Object v = pdo.readSlot(boundSlot);
            if (v == null) return PureSequence.EMPTY;
            return _PackageableElement.derefPointer(v, resolver);
        }
        Object result = executeOrAbsent(target, propName);
        if (result == ABSENT || result == null)
        {
            return PureSequence.EMPTY;
        }
        return _PackageableElement.derefPointer(result, resolver);
    }

    /**
     * Read returning {@link #ABSENT} for "property not declared by this
     * receiver type". Callers (notably {@code RawPropertyAccessNode}'s
     * Enumeration fallback) rely on ABSENT to distinguish "missing
     * property" from "present but empty". A null slot is indistinguishable
     * from a real EMPTY value here — must verify the receiver class
     * actually declares the property via {@link PureDynamicObject#readProperty}.
     */
    public Object executeOrAbsent(Object target, String propName)
    {
        if (target == null)
        {
            return org.finos.legend.pure.truffle.types.PureSequence.EMPTY;
        }
        if (target instanceof RawClosure rc)
        {
            target = rc.lambda();
            if (target == null) return ABSENT;
        }
        String name = (boundName != null) ? boundName : propName;
        if (target instanceof PureDynamicObject pdo)
        {
            return pdo.readProperty(name);
        }
        if (target instanceof PropertyAccessor pa)
        {
            return pa.readProperty(name);
        }
        return ABSENT;
    }
}
