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
 * Per–Pure-class slot layout.
 *
 * <p>Slot indices are <b>globally consistent</b>: each property name has
 * exactly one slot index across the entire program, assigned by
 * {@link PureClassRegistry#globalSlot} on first encounter. So if {@code "name"}
 * is at slot 5, every class that has a {@code "name"} property stores it at
 * slot 5. Subclasses thus share their parents' slot indices for inherited
 * properties — a polymorphic call site reading {@code "name"} on a static type
 * {@code A} works for every subtype of {@code A} that has {@code "name"},
 * because all of them use slot 5.</p>
 *
 * <p>Each PDO's {@code slots[]} array is sized to {@link #slotCount} — one
 * more than the highest global index used by any of this class's
 * (own + inherited) properties. Slots for properties this class doesn't have
 * stay null (unused).</p>
 */
public final class PureClassInfo
{
    public final String purePath;

    /** Slot count for this class = max global slot index + 1.
     *  Each instance's {@code slots[]} array is sized to this. */
    private volatile int slotCount = 0;

    /** Name → slot index for properties this class declares (own + inherited).
     *  Indices are global — shared across all classes that have the same
     *  property name. */
    private final ConcurrentHashMap<String, Integer> slotByName = new ConcurrentHashMap<>();

    /** Reverse: slot index → property name. Sized to {@link #slotCount}
     *  with null entries for slots this class doesn't have. Used by
     *  {@link PureDynamicObject#materialize} to look up the name from a
     *  global slot index for FB decode. */
    private volatile String[] nameBySlotIndex = EMPTY_NAMES;

    private volatile String[] propertyNames = EMPTY_NAMES;
    private volatile java.util.Map<String, Class<?>> propTypes = java.util.Map.of();
    private volatile String[] equalityKeys = EMPTY_NAMES;
    private volatile boolean populated = false;

    /** Lazily-cached FB decoder for this class. Lookup is keyed by
     *  {@link #purePath}; once resolved we stamp it here so subsequent
     *  reads skip the {@link PureFbDecoderRegistry} HashMap lookup. */
    private volatile PureFbDecoderRegistry.Decoder cachedDecoder;

    private static final String[] EMPTY_NAMES = new String[0];

    PureClassInfo(String purePath)
    {
        this.purePath = purePath;
    }

    void populate(String[] propertyNames, java.util.Map<String, Integer> globalSlots,
                  java.util.Map<String, Class<?>> propTypes, String[] equalityKeys)
    {
        int max = -1;
        for (String n : propertyNames)
        {
            Integer idx = globalSlots.get(n);
            if (idx == null) continue;
            slotByName.put(n, idx);
            if (idx > max) max = idx;
        }
        int newCount = max + 1;
        // Build the slot → name reverse map for materialize().
        String[] reverse = new String[newCount];
        for (var e : slotByName.entrySet()) reverse[e.getValue()] = e.getKey();
        this.nameBySlotIndex = reverse;
        this.slotCount = newCount;
        this.propertyNames = propertyNames;
        this.propTypes = propTypes;
        this.equalityKeys = equalityKeys != null ? equalityKeys : EMPTY_NAMES;
        this.populated = true;
    }

    boolean isPopulated() { return populated; }

    /** Slot-array size for this class's PDO instances. */
    public int slotCount() { return slotCount; }

    /** Flat list of property names this class declares (own + inherited).
     *  Used for serialisation / debugging. */
    public String[] propertyNames() { return propertyNames; }

    /** Reverse map slot index → property name (or null if this class has no
     *  property at that slot). Used by {@link PureDynamicObject#materialize}
     *  to recover the property name when decoding from FB. */
    public String[] nameBySlot() { return nameBySlotIndex; }

    public java.util.Map<String, Class<?>> propTypes() { return propTypes; }

    public String[] equalityKeys() { return equalityKeys; }

    /** Resolved FB decoder for this class, lazily loaded and cached.
     *  Skip the per-call {@link PureFbDecoderRegistry} HashMap lookup. */
    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    public PureFbDecoderRegistry.Decoder decoder()
    {
        PureFbDecoderRegistry.Decoder d = cachedDecoder;
        if (d == null)
        {
            d = PureFbDecoderRegistry.lookup(purePath);
            if (d == null)
            {
                d = PureFbDecoderRegistry.lazyLoadPublic(purePath);
            }
            if (d != null) cachedDecoder = d;
        }
        return d;
    }

    /** Resolve a property name to its slot index, or {@code -1} when this
     *  class doesn't declare it. Slot indices are global — the same name
     *  gets the same index across every class that has it. */
    public int slotIndex(String name)
    {
        Integer i = slotByName.get(name);
        return i == null ? -1 : i;
    }
}
