// Copyright 2026 Goldman Sachs
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0

package org.finos.legend.pure.truffle.runtime.dynobj;

import org.finos.legend.pure.truffle.runtime.PropertyAccessor;
import org.finos.legend.pure.truffle.runtime.TruffleMetadataAccess;
import org.finos.legend.pure.truffle.types.PureSequence;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Global registry of {@link PureClassInfo} keyed by Pure-class path, with a
 * <b>global property-name → slot-index</b> table that guarantees inheritance
 * preservation. Each property name gets exactly one slot index program-wide,
 * so a value stored at {@code "name"} lives at the same slot index in every
 * class that has a {@code "name"} property. Polymorphic call sites that
 * read {@code "name"} on a static parent type work for every subtype.
 */
public final class PureClassRegistry
{
    private static final ConcurrentHashMap<String, PureClassInfo> CLASSES = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Integer> GLOBAL_SLOTS = new ConcurrentHashMap<>();
    private static final AtomicInteger NEXT_GLOBAL_SLOT = new AtomicInteger(0);
    private static volatile String[] REVERSE_SLOT = new String[64];
    private static final ThreadLocal<java.util.Set<String>> BUILDING =
            ThreadLocal.withInitial(java.util.HashSet::new);

    /** Pure path of the {@code Class} metaclass — special-cased because it's
     *  self-classified (every Class element is itself a {@code Class}
     *  instance, including the {@code Class} class element itself). */
    private static final String CLASS_PATH = "meta::pure::metamodel::type::Class";

    /** Hardcoded property list for the {@code Class} metaclass. Mirrors the
     *  walk PureClassRegistry would perform if it could (which it can't —
     *  reading {@code Class}'s metadata requires {@code Class} to be already
     *  populated). Order matches inheritance: Any's properties first
     *  (root-most), then each level down to Class's own properties. */
    private static final String[] CLASS_BOOTSTRAP_PROPS = new String[]{
            // Any
            "elementOverride", "classifierGenericType", "sourceInformation",
            // ElementWithTaggedValues
            "taggedValues",
            // ElementWithStereotypes
            "stereotypes",
            // PackageableElement
            "name", "package",
            // Type
            "generalizations",
            // ElementWithConstraints
            "constraints",
            // PropertyOwner
            "qualifiedProperties",
            // SimplePropertyOwner
            "properties",
            // TypeAndMultiplicityParametersOwner
            "typeParameters", "multiplicityParameters",
            // Class (own)
            "typeVariables", "propertiesFromAssociations", "qualifiedPropertiesFromAssociations",
    };

    /** Pure path of the {@code Enum} metaclass — bootstrapped so enum-value
     *  PDO singletons (emitted by codegen instead of Java {@code enum}
     *  constants) can be constructed at class-load time without a populated
     *  resolver. */
    private static final String ENUM_PATH = "meta::pure::metamodel::type::Enum";

    private static final String[] ENUM_BOOTSTRAP_PROPS = new String[]{
            // Any
            "elementOverride", "classifierGenericType", "sourceInformation",
            // ElementWithTaggedValues
            "taggedValues",
            // ElementWithStereotypes
            "stereotypes",
            // Enum (own) — just `name`.
            "name",
    };

    static
    {
        // Pre-assign global slots for the Class metaclass so cycle-free
        // bootstrap of every other class can proceed via the resolver walk.
        for (String name : CLASS_BOOTSTRAP_PROPS) globalSlot(name);
        for (String name : ENUM_BOOTSTRAP_PROPS) globalSlot(name);

        PureClassInfo classInfo = new PureClassInfo(CLASS_PATH);
        java.util.Map<String, Integer> globalSnapshot = java.util.Collections.unmodifiableMap(new java.util.HashMap<>(GLOBAL_SLOTS));
        classInfo.populate(CLASS_BOOTSTRAP_PROPS, globalSnapshot, java.util.Map.of(), new String[0]);
        CLASSES.put(CLASS_PATH, classInfo);

        PureClassInfo enumInfo = new PureClassInfo(ENUM_PATH);
        enumInfo.populate(ENUM_BOOTSTRAP_PROPS, globalSnapshot, java.util.Map.of(), new String[0]);
        CLASSES.put(ENUM_PATH, enumInfo);
    }

    /** ClassInfo for {@code meta::pure::metamodel::type::Enum} — used by
     *  generated {@code XEnum} classes to construct value PDO singletons. */
    public static PureClassInfo enumClassInfo()
    {
        return CLASSES.get(ENUM_PATH);
    }

    private PureClassRegistry() {}

    /** Resolve (and lazy-build) {@link PureClassInfo} for {@code purePath}. */
    public static PureClassInfo classInfoFor(String purePath, TruffleMetadataAccess resolver)
    {
        PureClassInfo cached = CLASSES.get(purePath);
        if (cached != null && cached.isPopulated()) return cached;
        if (resolver == null)
        {
            return CLASSES.computeIfAbsent(purePath, PureClassInfo::new);
        }

        // Insert (or reuse) the placeholder before populating, so recursive
        // lookups during the build see the same shared instance.
        PureClassInfo info = CLASSES.computeIfAbsent(purePath, PureClassInfo::new);
        if (info.isPopulated()) return info;

        java.util.Set<String> building = BUILDING.get();
        if (building.contains(purePath)) return info;
        building.add(purePath);
        try
        {
            populateClassInfo(info, resolver);
            return info;
        }
        finally
        {
            building.remove(purePath);
        }
    }

    /** Global slot index for {@code name}, assigning a fresh one on first
     *  encounter. Used by populate() and by AST-build slot resolution. */
    public static int globalSlot(String name)
    {
        Integer i = GLOBAL_SLOTS.get(name);
        if (i != null) return i;
        synchronized (GLOBAL_SLOTS)
        {
            i = GLOBAL_SLOTS.get(name);
            if (i != null) return i;
            int next = NEXT_GLOBAL_SLOT.getAndIncrement();
            GLOBAL_SLOTS.put(name, next);
            String[] arr = REVERSE_SLOT;
            if (next >= arr.length)
            {
                String[] grown = new String[Math.max(arr.length * 2, next + 16)];
                System.arraycopy(arr, 0, grown, 0, arr.length);
                arr = grown;
                REVERSE_SLOT = arr;
            }
            arr[next] = name;
            return next;
        }
    }

    /** Inverse of {@link #globalSlot}: name registered at this slot, or null. */
    public static String nameOfGlobalSlot(int slot)
    {
        String[] arr = REVERSE_SLOT;
        return slot >= 0 && slot < arr.length ? arr[slot] : null;
    }

    /** Total number of distinct property names assigned so far — upper bound
     *  for the max slot index of any class. */
    public static int totalSlots() { return NEXT_GLOBAL_SLOT.get(); }

    /** Test-only convenience: register a class with explicit property names. */
    public static PureClassInfo registerForTest(String purePath, String... propertyNames)
    {
        PureClassInfo info = CLASSES.computeIfAbsent(purePath, PureClassInfo::new);
        java.util.Map<String, Integer> slots = new java.util.HashMap<>();
        for (String n : propertyNames) slots.put(n, globalSlot(n));
        info.populate(propertyNames.clone(), slots, java.util.Map.of(), new String[0]);
        return info;
    }

    private static void populateClassInfo(PureClassInfo info, TruffleMetadataAccess resolver)
    {
        Object classElem = resolver.getElement(info.purePath);
        if (classElem == null) return;

        PropertyMetadataRegistry.ensurePopulated(info.purePath, resolver);

        ArrayList<Object> hierarchy = new ArrayList<>();
        collectHierarchy(classElem, hierarchy, new java.util.IdentityHashMap<>());

        // Collect property names walking least-specific first.
        LinkedHashMap<String, Integer> nameToGlobalSlot = new LinkedHashMap<>();
        for (int i = hierarchy.size() - 1; i >= 0; i--)
        {
            addNamesFrom(hierarchy.get(i), "properties", nameToGlobalSlot);
            addNamesFrom(hierarchy.get(i), "propertiesFromAssociations", nameToGlobalSlot);
        }

        String[] nameBySlot = nameToGlobalSlot.keySet().toArray(new String[0]);
        java.util.Map<String, Class<?>> propTypes = PropertyMetadataRegistry.snapshotTypes(info.purePath);
        String[] equalityKeys = PropertyMetadataRegistry.getEqualityKeys(info.purePath);
        info.populate(nameBySlot, nameToGlobalSlot, propTypes, equalityKeys);
    }

    private static void collectHierarchy(Object classElem, ArrayList<Object> sink,
            java.util.IdentityHashMap<Object, Boolean> seen)
    {
        if (classElem == null || seen.containsKey(classElem)) return;
        seen.put(classElem, Boolean.TRUE);
        sink.add(classElem);
        Object generalizations = readField(classElem, "generalizations");
        if (!(generalizations instanceof PureSequence gens)) return;
        for (int i = 0; i < gens.size(); i++)
        {
            Object g = gens.getBoxed(i);
            if (g == null) continue;
            Object general = readField(g, "general");
            Object parent = general != null ? readField(general, "type") : null;
            if (parent != null) collectHierarchy(parent, sink, seen);
        }
    }

    private static void addNamesFrom(Object element, String collectionName,
            LinkedHashMap<String, Integer> sink)
    {
        Object obj = readField(element, collectionName);
        if (!(obj instanceof PureSequence seq)) return;
        for (int i = 0; i < seq.size(); i++)
        {
            Object prop = seq.getBoxed(i);
            if (prop == null) continue;
            Object name = readField(prop, "name");
            if (!(name instanceof String s) || s.isEmpty()) continue;
            sink.putIfAbsent(s, globalSlot(s));
        }
    }

    private static Object readField(Object obj, String name)
    {
        if (obj instanceof PropertyAccessor pa)
        {
            Object v = pa.readProperty(name);
            return v == PropertyAccessor.ABSENT ? null : v;
        }
        return null;
    }
}
