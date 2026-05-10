// Copyright 2026 Goldman Sachs
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0

package org.finos.legend.pure.truffle.runtime.dynobj;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.object.DynamicObjectLibrary;
import org.finos.legend.pure.truffle.runtime.PropertyAccessor;

/**
 * Static helper API for the migration from typed-getter Pure metamodel
 * classes to {@link PureDynamicObject} + {@code DynamicObjectLibrary}.
 *
 * <p>Replaces {@code pe._name()} with {@code PureObj.read(pe, "name")}. Each
 * helper handles BOTH the legacy {@code PropertyAccessor}-implementing
 * generated classes (XImpl + XFlatBufferWrapper) AND the new {@link
 * PureDynamicObject}, so call sites can be migrated incrementally without
 * coordinating with the loader flip.</p>
 *
 * <p>Performance: these are convenience helpers using
 * {@code DynamicObjectLibrary.getUncached()} — fine for cold and rarely-hit
 * paths. Hot AST sites should use the cached {@code PurePropertyReadNode}
 * (or a future {@code @CachedLibrary}-decorated equivalent) instead.</p>
 */
public final class PureObj
{
    private PureObj() {}

    /**
     * Read property {@code name} on {@code obj}. Lazy-materialises from the
     * backing FB on first access. Returns {@code null} when the property is
     * absent or the receiver is not a Pure metamodel object.
     */
    @TruffleBoundary
    public static Object read(Object obj, String name)
    {
        if (obj instanceof PureDynamicObject pdo)
        {
            DynamicObjectLibrary dol = DynamicObjectLibrary.getUncached();
            Object cached = dol.getOrDefault(pdo, name, PureFbDecoder.LAZY);
            if (cached != PureFbDecoder.LAZY)
            {
                return cached;
            }
            Object decoded = PureFbDecoder.decode(pdo, name);
            dol.put(pdo, name, decoded);
            return decoded;
        }
        // Legacy path: existing XImpl / XFlatBufferWrapper. Migration safe —
        // both already implement PropertyAccessor.
        if (obj instanceof PropertyAccessor accessor)
        {
            Object value = accessor.readProperty(name);
            return value == PropertyAccessor.ABSENT ? null : value;
        }
        return null;
    }

    /**
     * Write property {@code name} on {@code obj}. The Pure-built path: used by
     * {@code ^X(name=val)} and the bootstrap construction sites. Read-only
     * legacy FBWs throw — that mirrors today's behaviour.
     */
    @TruffleBoundary
    public static void write(Object obj, String name, Object value)
    {
        if (obj instanceof PureDynamicObject pdo)
        {
            DynamicObjectLibrary.getUncached().put(pdo, name, value);
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
     *
     * <p>For {@link PureDynamicObject} the path lives in the Shape's dynamic
     * type. For legacy generated classes we derive the path from the Java
     * class name + package via {@link #derivePureTypePathFrom}, then cache
     * the result identity-keyed on {@code Class<?>} so subsequent calls are
     * a single {@code ConcurrentHashMap.get(Class)} — fast enough to use
     * on hot AST paths without busting Graal's inlining budget the way the
     * uncached version did (confirmed: blew up {@code IsNode} with
     * {@code PermanentBailoutException} before the cache).</p>
     *
     * <p>{@code @TruffleBoundary} is retained for safety: the cache miss
     * still walks the class name (StringBuilder, substring), which Graal
     * would happily inline. The boundary keeps the PE graph bounded; the
     * cache hit makes the runtime cost negligible.</p>
     */
    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    public static String pureTypeOf(Object obj)
    {
        if (obj == null)
        {
            return null;
        }
        if (obj instanceof PureDynamicObject pdo)
        {
            Object dt = pdo.getShape().getDynamicType();
            if (dt instanceof String s)
            {
                return s;
            }
            return null;
        }
        // Class-keyed cache: legacy generated class → derived Pure path.
        // Once warm, every call is a single CHM.get(Class) — bounded.
        String cached = LEGACY_CLASS_PATH_CACHE.get(obj.getClass());
        if (cached != null)
        {
            return cached == ABSENT_PATH ? null : cached;
        }
        String derived = derivePureTypePathFrom(obj);
        LEGACY_CLASS_PATH_CACHE.put(obj.getClass(), derived != null ? derived : ABSENT_PATH);
        return derived;
    }

    /** Class-keyed memo of {@link #derivePureTypePathFrom}. Lifetime is JVM-wide; entries
     *  are stable because the Java class → Pure path mapping is invariant. */
    private static final java.util.concurrent.ConcurrentHashMap<Class<?>, String> LEGACY_CLASS_PATH_CACHE =
            new java.util.concurrent.ConcurrentHashMap<>();
    /** Sentinel for "this Class doesn't map to a Pure path" — caches the negative case
     *  so non-metamodel classes (PureSequence, Long, RawClosure, …) don't re-derive. */
    private static final String ABSENT_PATH = new String("<absent>");

    /**
     * Exact Pure-type match (no linearization). Cheap — no resolver needed.
     * Use for {@code instanceof X} where {@code X} is a leaf concrete type
     * that has no Pure subtypes (e.g. {@code Package}, {@code Stereotype}).
     * For interfaces with subtypes (e.g. {@code Type}, {@code GenericType}),
     * use {@link #isType} which walks linearization.
     */
    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
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

    /**
     * Replacement for {@code obj instanceof X} where {@code X} is a generated
     * Pure metamodel interface. Resolves the Pure type of {@code obj} (Shape
     * dynamic type for a {@link PureDynamicObject}, derived from the Java
     * class name for a legacy generated instance) and tests subtype against
     * {@code targetPurePath} via the existing
     * {@link org.finos.legend.pure.truffle.runtime.helper._Type#subtypeOf
     * _Type.subtypeOf} which uses the resolver's TypeCache linearization.
     *
     * <p>Returns {@code false} when {@code obj} has no resolvable Pure type or
     * the target path doesn't load to a Type. Mirrors Java {@code instanceof}
     * semantics: a Property is also a subtype of PackageableElement, so
     * {@code isType(propertyObj, "...PackageableElement", resolver)} → true.</p>
     */
    public static boolean isType(Object obj, String targetPurePath,
            org.finos.legend.pure.truffle.runtime.TruffleMetadataAccess resolver)
    {
        if (obj == null || targetPurePath == null || resolver == null)
        {
            return false;
        }
        String objTypePath = pureTypeOf(obj);
        if (objTypePath == null)
        {
            objTypePath = derivePureTypePathFrom(obj);
        }
        if (objTypePath == null)
        {
            return false;
        }
        if (objTypePath.equals(targetPurePath))
        {
            return true;
        }
        Object objType = resolver.getElement(objTypePath);
        Object targetType = resolver.getElement(targetPurePath);
        if (!(objType instanceof org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.type.Type ot)
                || !(targetType instanceof org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.type.Type tt))
        {
            return false;
        }
        return org.finos.legend.pure.truffle.runtime.helper._Type.subtypeOf(ot, tt, resolver);
    }

    /**
     * Derive the Pure-type path (e.g. {@code meta::pure::metamodel::type::Class})
     * for a legacy-generated metamodel object by introspecting its Java class
     * name + package. The codegen places {@code XImpl} / {@code XFlatBufferWrapper}
     * under {@code org.finos.legend.pure.truffle.pdb.<pure-package>.<X>...}, so:
     *
     * <pre>
     *   .pdb.meta.pure.metamodel.type.ClassFlatBufferWrapper
     *     -&gt; meta::pure::metamodel::type::Class
     * </pre>
     *
     * <p>Used by the loader bridge to set the {@code Shape}'s dynamic type when
     * wrapping a legacy FBW in a {@link PureDynamicObject}. Returns {@code null}
     * when the object is not a recognised generated metamodel class.</p>
     */
    public static String derivePureTypePathFrom(Object element)
    {
        if (element == null)
        {
            return null;
        }
        String className = element.getClass().getName();
        final String marker = ".pdb.";
        int markerIdx = className.indexOf(marker);
        if (markerIdx < 0)
        {
            return null;
        }
        int lastDot = className.lastIndexOf('.');
        String pkgRest = className.substring(markerIdx + marker.length(), lastDot);
        String simpleName = className.substring(lastDot + 1);
        if (simpleName.endsWith("FlatBufferWrapper"))
        {
            simpleName = simpleName.substring(0, simpleName.length() - "FlatBufferWrapper".length());
        }
        else if (simpleName.endsWith("Impl"))
        {
            simpleName = simpleName.substring(0, simpleName.length() - "Impl".length());
        }
        return pkgRest.replace(".", "::") + "::" + simpleName;
    }
}
