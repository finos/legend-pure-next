// Copyright 2026 Goldman Sachs
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0

package org.finos.legend.pure.truffle.runtime.dynobj;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-(Pure-class, property-name) declared Java type registry. Populated by
 * each generated XPDBHelper's {@code static} initialiser when its class first
 * loads. Used by {@link PureDynamicObject#writeProperty(String, Object)} to
 * pick the right {@code PropertyCoercion} target — the same per-property
 * type info the XPDBHelper's typed setter parameter carries.
 *
 * <p>Lazy-load on miss: if a {@code getType} call doesn't find an entry,
 * the matching XPDBHelper class is loaded via {@link Class#forName}, which runs
 * its {@code static} block and populates the registry. Subsequent calls hit
 * the cached entry.</p>
 */
public final class PropertyMetadataRegistry
{
    private static final ConcurrentHashMap<String, ConcurrentHashMap<String, Class<?>>> META = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, String[]> EQUALITY_KEYS = new ConcurrentHashMap<>();
    private static final java.util.Set<String> POPULATED_FROM_CLASS = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private PropertyMetadataRegistry() {}

    /**
     * Populate the registry by walking the Pure {@code Class} element's
     * properties + propertiesFromAssociations + stereotypes. This is the
     * "no-codegen" path for user-defined classes: no per-class XPDBHelper exists,
     * so the per-property type and equality-key metadata is derived directly
     * from the Pure class definition at runtime. Idempotent and lazy
     * (one-shot per Pure path).
     *
     * <p>Called from {@link
     * org.finos.legend.pure.truffle.runtime.TruffleInstanceFactory#createInstance}
     * and from {@link PureShapeRegistry#shapeFor} before snapshotting, so the
     * Shape's sharedData ends up populated for user classes the same way
     * metamodel classes inherit theirs from XPDBHelper static{} blocks.</p>
     */
    public static void ensurePopulated(String purePath,
            org.finos.legend.pure.truffle.runtime.TruffleMetadataAccess resolver)
    {
        if (purePath == null || resolver == null) return;
        if (POPULATED_FROM_CLASS.contains(purePath)) return;
        if (META.containsKey(purePath)) { POPULATED_FROM_CLASS.add(purePath); return; }
        Object classElem = resolver.getElement(purePath);
        if (classElem == null) { POPULATED_FROM_CLASS.add(purePath); return; }
        populateFromClass(purePath, classElem);
        POPULATED_FROM_CLASS.add(purePath);
    }

    private static void populateFromClass(String purePath, Object classElem)
    {
        java.util.List<String> equalityKeyNames = new java.util.ArrayList<>();
        registerPropertyList(purePath, readField(classElem, "properties"), equalityKeyNames);
        registerPropertyList(purePath, readField(classElem, "propertiesFromAssociations"), equalityKeyNames);
        registerPropertyList(purePath, readField(classElem, "qualifiedProperties"), null);
        if (!equalityKeyNames.isEmpty())
        {
            EQUALITY_KEYS.put(purePath, equalityKeyNames.toArray(new String[0]));
        }
    }

    /**
     * PropertyAccessor-driven read for either a PDO or a typed XPDBHelper —
     * mirrors {@code PureObj.read} but lives in the codegen module so this
     * class can stay alongside the registry. Returns null for ABSENT.
     */
    private static Object readField(Object obj, String name)
    {
        if (obj == null) return null;
        if (obj instanceof org.finos.legend.pure.truffle.runtime.PropertyAccessor pa)
        {
            Object v = pa.readProperty(name);
            return v == org.finos.legend.pure.truffle.runtime.PropertyAccessor.ABSENT ? null : v;
        }
        return null;
    }

    private static void registerPropertyList(String purePath, Object propsObj,
            java.util.List<String> equalityKeyNamesSink)
    {
        if (!(propsObj instanceof org.finos.legend.pure.truffle.types.PureSequence props)) return;
        for (int i = 0; i < props.size(); i++)
        {
            Object prop = props.getBoxed(i);
            if (prop == null) continue;
            Object nameObj = readField(prop, "name");
            if (!(nameObj instanceof String propName)) continue;
            Class<?> paramType = inferJavaType(prop);
            META.computeIfAbsent(purePath, k -> new ConcurrentHashMap<>()).put(propName, paramType);
            if (equalityKeyNamesSink != null && hasEqualityKeyStereotype(prop))
            {
                equalityKeyNamesSink.add(propName);
            }
        }
    }

    private static Class<?> inferJavaType(Object property)
    {
        Object mult = readField(property, "multiplicity");
        if (isMultiplicityMany(mult)) return org.finos.legend.pure.truffle.types.PureSequence.class;
        Object gt = readField(property, "genericType");
        Object type = gt != null ? readField(gt, "type") : null;
        Object typeName = type != null ? readField(type, "name") : null;
        if (typeName instanceof String tn)
        {
            switch (tn)
            {
                case "String": return String.class;
                case "Boolean": return Boolean.class;
                case "Integer": return Long.class;
                case "Float": return Double.class;
                case "Number": return Number.class;
                case "Decimal": return java.math.BigDecimal.class;
                default: return Object.class;
            }
        }
        return Object.class;
    }

    private static boolean isMultiplicityMany(Object mult)
    {
        if (mult == null) return false;
        Object upperObj = readField(mult, "upperBound");
        if (upperObj == null) return true;
        Object valueObj = readField(upperObj, "value");
        if (valueObj == null) return true;
        if (valueObj instanceof Number n && n.longValue() > 1) return true;
        return false;
    }

    private static boolean hasEqualityKeyStereotype(Object property)
    {
        Object stsObj = readField(property, "stereotypes");
        if (!(stsObj instanceof org.finos.legend.pure.truffle.types.PureSequence sts)) return false;
        for (int i = 0; i < sts.size(); i++)
        {
            Object st = sts.getBoxed(i);
            if (st == null) continue;
            Object val = readField(st, "value");
            if ("Key".equals(val))
            {
                Object profile = readField(st, "profile");
                Object profName = profile != null ? readField(profile, "name") : null;
                if ("equality".equals(profName)) return true;
            }
        }
        return false;
    }

    /**
     * Register a property's declared Java type for a Pure class. Called
     * from each XPDBHelper's {@code static} initialiser at class-load time.
     */
    public static void register(String purePath, String propName, Class<?> paramType)
    {
        META.computeIfAbsent(purePath, k -> new ConcurrentHashMap<>()).put(propName, paramType);
    }

    /**
     * Register the {@code <<equality.Key>>}-annotated property names for a
     * Pure class. Used by {@link PureDynamicObject#equals(Object)} and
     * {@code hashCode()} to compare by key-property values, matching the
     * generated XPDBHelper's equality semantics.
     */
    public static void registerEqualityKeys(String purePath, String... propNames)
    {
        EQUALITY_KEYS.put(purePath, propNames);
    }

    /** Returns the equality-key property names for a Pure class, or null if none. */
    public static String[] getEqualityKeys(String purePath)
    {
        String[] keys = EQUALITY_KEYS.get(purePath);
        if (keys == null)
        {
            // Lazy-load the XPDBHelper class so its static{} block registers.
            try { Class.forName(toJavaClassName(purePath)); } catch (ClassNotFoundException ignored) {}
            keys = EQUALITY_KEYS.get(purePath);
        }
        return keys;
    }

    /**
     * Look up the declared Java type for a property on a Pure class.
     * Triggers XPDBHelper class load on first miss so the registry self-populates.
     * Returns {@code null} when the property/class is unknown (caller skips
     * coercion).
     */
    public static Class<?> getType(String purePath, String propName)
    {
        ConcurrentHashMap<String, Class<?>> inner = META.get(purePath);
        if (inner == null)
        {
            inner = tryLoadInner(purePath);
            if (inner == null) return null;
        }
        return inner.get(propName);
    }

    /**
     * Snapshot the per-property type map for {@code purePath}, loading the
     * XPDBHelper if needed. Returns an empty map when no XPDBHelper exists. Used by
     * {@link PureShapeRegistry} to bake the lookup into the Shape's
     * sharedData so per-write coercion avoids re-traversing the two-level
     * CHM here.
     */
    public static java.util.Map<String, Class<?>> snapshotTypes(String purePath)
    {
        ConcurrentHashMap<String, Class<?>> inner = META.get(purePath);
        if (inner == null)
        {
            inner = tryLoadInner(purePath);
        }
        return inner != null ? inner : java.util.Map.of();
    }

    /** Pure paths that have no XPDBHelper on the classpath. Avoids retrying
     *  {@code Class.forName} (which itself walks the classpath) + the
     *  regex {@code split("::")} on every miss for the same path. */
    private static final java.util.concurrent.ConcurrentHashMap<String, Boolean> NO_XPDBHELPER =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** Resolve the XPDBHelper class for {@code purePath} and return the populated
     *  inner map (or {@code null} when no XPDBHelper exists). Memoises the
     *  "no XPDBHelper" verdict in {@link #NO_XPDBHELPER} so subsequent calls for the
     *  same path skip the {@code Class.forName} and the path-to-FQN build. */
    private static ConcurrentHashMap<String, Class<?>> tryLoadInner(String purePath)
    {
        if (NO_XPDBHELPER.containsKey(purePath)) return null;
        try
        {
            Class.forName(toJavaClassName(purePath));
        }
        catch (ClassNotFoundException ignored)
        {
            NO_XPDBHELPER.put(purePath, Boolean.TRUE);
            return null;
        }
        return META.get(purePath);
    }

    /** {@code purePath.split("::")} is regex-backed (Pattern.compile per call
     *  for 2-char delimiters); manual {@code indexOf} loop is ~10x faster on
     *  the warm path and was 18 leaf JFR samples before this rewrite. */
    private static String toJavaClassName(String purePath)
    {
        StringBuilder sb = new StringBuilder(purePath.length() + 16);
        sb.append("org.finos.legend.pure.truffle.pdb.");
        int start = 0;
        boolean firstSegment = true;
        while (true)
        {
            int idx = purePath.indexOf("::", start);
            int end = idx < 0 ? purePath.length() : idx;
            if (!firstSegment) sb.append('.');
            firstSegment = false;
            String segment = purePath.substring(start, end);
            sb.append(JAVA_KEYWORDS.contains(segment) ? segment + "_" : segment);
            if (idx < 0) break;
            start = idx + 2;
        }
        sb.append("PDBHelper");
        return sb.toString();
    }

    private static final java.util.Set<String> JAVA_KEYWORDS = java.util.Set.of(
            "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char",
            "class", "const", "continue", "default", "do", "double", "else", "enum",
            "extends", "final", "finally", "float", "for", "goto", "if", "implements",
            "import", "instanceof", "int", "interface", "long", "native", "new",
            "package", "private", "protected", "public", "return", "short", "static",
            "strictfp", "super", "switch", "synchronized", "this", "throw", "throws",
            "transient", "try", "void", "volatile", "while");
}
