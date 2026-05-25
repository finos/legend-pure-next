// Copyright 2026 Goldman Sachs
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0

package org.finos.legend.pure.truffle.runtime.dynobj;

import org.finos.legend.pure.truffle.runtime.PropertyAccessor;

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
public final class PureObj
{
    private PureObj() {}

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
                "PureObj.write: receiver is not a Pure metamodel object: "
                        + (obj == null ? "null" : obj.getClass().getName()));
    }

    /**
     * Pure-class path of {@code obj}, or {@code null} when unknown.
     */
    public static String pureTypeOf(Object obj)
    {
        if (obj == null) return null;
        if (obj instanceof PureDynamicObject pdo) return pdo.classInfo.purePath;
        return pureTypeOfNonPdo(obj);
    }

    /** Slow-path resolver for non-PDO receivers (legacy XPDBHelper / FB wrappers).
     *  Behind {@code @TruffleBoundary} so the {@code ConcurrentHashMap} path
     *  (which triggers the JDK Locale/SignatureParser recursive inline
     *  chain) doesn't get pulled into Graal's PE of hot pure-PDO code. */
    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    private static String pureTypeOfNonPdo(Object obj)
    {
        String cached = LEGACY_CLASS_PATH_CACHE.get(obj.getClass());
        if (cached != null) return cached == ABSENT_PATH ? null : cached;
        String derived = derivePureTypePathFrom(obj);
        LEGACY_CLASS_PATH_CACHE.put(obj.getClass(), derived != null ? derived : ABSENT_PATH);
        return derived;
    }

    private static final java.util.concurrent.ConcurrentHashMap<Class<?>, String> LEGACY_CLASS_PATH_CACHE =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static final String ABSENT_PATH = new String("<absent>");

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
            org.finos.legend.pure.truffle.runtime.TruffleMetadataAccess resolver)
    {
        if (obj == null || targetPurePath == null || resolver == null) return false;
        String objTypePath = pureTypeOf(obj);
        if (objTypePath == null) objTypePath = derivePureTypePathFrom(obj);
        if (objTypePath == null) return false;
        if (objTypePath.equals(targetPurePath)) return true;
        Object objType = resolver.getElement(objTypePath);
        Object targetType = resolver.getElement(targetPurePath);
        if (objType == null || targetType == null) return false;
        return org.finos.legend.pure.truffle.runtime.helper._Type.subtypeOf(objType, targetType, resolver);
    }

    public static String derivePureTypePathFrom(Object element)
    {
        if (element == null) return null;
        String className = element.getClass().getName();
        final String marker = ".pdb.";
        int markerIdx = className.indexOf(marker);
        if (markerIdx < 0) return null;
        int lastDot = className.lastIndexOf('.');
        String pkgRest = className.substring(markerIdx + marker.length(), lastDot);
        String simpleName = className.substring(lastDot + 1);
        if (simpleName.endsWith("FlatBufferWrapper"))
        {
            simpleName = simpleName.substring(0, simpleName.length() - "FlatBufferWrapper".length());
        }
        else if (simpleName.endsWith("PDBHelper"))
        {
            simpleName = simpleName.substring(0, simpleName.length() - "PDBHelper".length());
        }
        return pkgRest.replace(".", "::") + "::" + simpleName;
    }
}
