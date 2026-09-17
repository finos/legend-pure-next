// Copyright 2026 Goldman Sachs
// ©2026 JP Morgan Chase & Co. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0

package org.finos.legend.pure.truffle.compiler.helper;

import org.finos.legend.pure.truffle.execution.PropertyAccessor;
import org.finos.legend.pure.truffle.execution.PureDynamicObject;
import org.finos.legend.pure.truffle.compiler.module.MetadataAccess;

/**
 * Static helper API for Pure object property access. After the custom-storage
 * pivot, reads route through {@link PureDynamicObject#readSlot} / {@link
 * PureDynamicObject#readProperty} — direct array indexing rather than a
 * {@code TriePropertyMap} walk.
 *
 * <p>Hot AST paths should use slot-indexed AST nodes (slot index resolved at
 * AST-build via {@link PureClassInfo#slotIndex}, baked as
 * {@code @CompilationFinal}). These static helpers are kept for cold/dynamic
 * paths where the property name isn't known at compile time.</p>
 */
public final class _Any
{
    private _Any() {}

    /**
     * Read property {@code name} on {@code obj}. Lazy-materialises from the
     * backing FB on first access. Returns {@code null} when the property is
     * absent or the receiver is not a Pure metamodel object.
     */
    public static Object read(Object obj, String name)
    {
        if (obj instanceof PropertyAccessor accessor)
        {
            Object value = accessor.readProperty(name);
            return value == PropertyAccessor.ABSENT ? null : value;
        }
        return null;
    }

    /**
     * Slot-indexed read for Pure metamodel objects. Callers with a
     * {@code static final int SLOT_X = PureClassRegistry.globalSlot("x")}
     * use this to skip the per-class {@code slotByName} HashMap.get — slot
     * is a JIT constant so the array offset folds. Every Pure value is a
     * {@link PureDynamicObject} post enum-to-PDO migration, so the receiver
     * is typed; the {@code Object}-overload casts at the boundary for
     * call sites that retrieve from collections or lambda args.
     */
    public static Object readBySlot(PureDynamicObject pdo, int slot)
    {
        return pdo.readSlot(slot);
    }

    public static Object readBySlot(Object obj, int slot)
    {
        return ((PureDynamicObject) obj).readSlot(slot);
    }

    /**
     * Write property {@code name} on {@code obj}. Throws when the receiver
     * isn't a Pure metamodel object or doesn't declare {@code name}.
     */
    public static void write(Object obj, String name, Object value)
    {
        if (obj instanceof PureDynamicObject pdo)
        {
            pdo.writeProperty(name, value);
            return;
        }
        if (obj instanceof PropertyAccessor accessor)
        {
            accessor.writeProperty(name, value);
            return;
        }
        throw new IllegalArgumentException(
                "_Any.write: receiver is not a Pure metamodel object: "
                        + (obj == null ? "null" : obj.getClass().getName()));
    }

    /**
     * Pure-class path of {@code obj}, or {@code null} when unknown.
     */
    public static String pureTypeOf(Object obj)
    {
        // Post enum-to-PDO migration every Pure metamodel value IS a
        // PureDynamicObject; non-PDO receivers (raw primitives, sequences,
        // host objects) have no Pure-class path here.
        return obj instanceof PureDynamicObject pdo ? pdo.classInfo.purePath : null;
    }

    /** Exact Pure-type match (no linearization). */
    public static boolean pureTypeIs(Object obj, String purePath)
    {
        String t = pureTypeOf(obj);
        return t != null && t.equals(purePath);
    }

    /** Convenience: cast to {@link PureDynamicObject} or null. */
    public static PureDynamicObject asDynamic(Object obj)
    {
        return obj instanceof PureDynamicObject pdo ? pdo : null;
    }

    public static boolean isType(Object obj, String targetPurePath,
            MetadataAccess resolver)
    {
        if (obj == null || targetPurePath == null || resolver == null) return false;
        String objTypePath = pureTypeOf(obj);
        if (objTypePath == null) return false;
        if (objTypePath.equals(targetPurePath)) return true;
        Object objType = resolver.getElement(objTypePath);
        Object targetType = resolver.getElement(targetPurePath);
        if (objType == null || targetType == null) return false;
        return org.finos.legend.pure.truffle.compiler.helper._Type.subtypeOf(objType, targetType, resolver);
    }
}
