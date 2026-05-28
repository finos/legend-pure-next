// Copyright 2026 Goldman Sachs
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0

package org.finos.legend.pure.truffle.runtime.dynobj;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.interop.TruffleObject;
import org.finos.legend.pure.truffle.runtime.PropertyAccessor;
import org.finos.legend.pure.truffle.runtime.TruffleMetadataAccess;
import org.finos.legend.pure.truffle.types.PureSequence;

import java.util.Arrays;

/**
 * Single object kind for all Pure-on-Truffle metamodel instances.
 *
 * <p>Storage is a hand-rolled {@code Object[] slots} array sized for the
 * Pure class's property count, indexed by the {@link PureClassInfo}'s
 * inheritance-preserved slot table. Replaces the
 * {@code DynamicObject}/{@code Shape}/{@code TriePropertyMap} path that
 * JFR pinned at ~35% of warm-time CPU.</p>
 *
 * <p>Read path under Graal PE (after AST-build slot resolution):
 * <pre>
 *   mov  rax, [pdo + slots_offset]   ; load slots field
 *   mov  rax, [rax + 16 + idx*8]      ; load constant-indexed element
 * </pre>
 * Two loads, ~2 ns. Polymorphism-tolerant because the slot index is constant
 * per (Pure class, property name) across every subclass — inheritance
 * preserved by {@link PureClassInfo}.</p>
 *
 * <p>{@link #fb} carries an FB-backed source for lazy decode (PDB load
 * path). On first read of a property whose slot is {@link PureFbDecoder#LAZY},
 * the value is materialised via {@link PureFbDecoderRegistry#decode} and
 * stored back in the slot.</p>
 */
public class PureDynamicObject implements PropertyAccessor, TruffleObject
{
    /** Per-class slot table — inheritance-preserved (subclass shares parent's
     *  slot indices for inherited properties). Resolved once per class at
     *  construction via {@link PureClassRegistry}. */
    public final PureClassInfo classInfo;

    /** Per-instance property storage, indexed by {@link PureClassInfo#slotIndex}.
     *  Sized to {@code classInfo.slotCount}. FB-backed instances start with
     *  every slot set to {@link PureFbDecoder#LAZY} (decode-on-first-read).
     *  Pure-built instances start with {@code null} in every slot. */
    public final Object[] slots;

    /** FB-generated {@code XDef} struct (e.g. {@code PropertyDef}); null for Pure-built. */
    public final Object fb;

    /** Resolver for cross-element references in lazy decode (PointerRef, etc.). */
    public final Object resolver;

    /** Parent for {@code AncestorRef} resolution; null for top-level / Pure-built. */
    public final Object parent;

    public PureDynamicObject(PureClassInfo classInfo, Object fb, Object resolver, Object parent)
    {
        this.classInfo = classInfo;
        this.slots = new Object[classInfo.slotCount()];
        if (fb != null && this.slots.length > 0)
        {
            // FB-backed: every property starts LAZY so the first read
            // triggers FB materialisation. Pure-built leaves slots null —
            // copy() relies on null to mean "unset", distinct from a set
            // value of {@link PureSequence#EMPTY}.
            Arrays.fill(this.slots, PureFbDecoder.LAZY);
        }
        this.fb = fb;
        this.resolver = resolver;
        this.parent = parent;
    }

    /** Convenience accessor — the Pure-class path encoded in this instance. */
    public String purePath()
    {
        return classInfo.purePath;
    }

    /**
     * Direct slot read by index. Called from AST nodes that have resolved
     * the slot index at build time via {@link PureClassRegistry#globalSlot}
     * and stored it as a {@code final} int. Under PE this folds to a single
     * array load (plus a LAZY materialisation branch on FB-cold path).
     *
     * <p>Returns {@code null} when this class doesn't declare a property at
     * {@code slotIdx} (the slot index is global but this PDO's
     * {@code slots[]} is sized to its own properties; foreign slots are
     * out-of-range). Callers in Pure-semantic contexts treat null as
     * {@link PureSequence#EMPTY}.</p>
     */
    public Object readSlot(int slotIdx)
    {
        // Pointer-access contract (see feature_pointerAccess.pure). Mirror
        // the readProperty check here because PropertyReadNode's bound-slot
        // fast path (compile-time-resolved slot index) calls readSlot
        // directly, bypassing readProperty. Matches the bootstrap
        // {@code PointerAccessGuard} rule: reading anything on a pointer
        // except its pointer-native slots ({@code path} on every pointer,
        // {@code element} on PropertyPointer/EnumPointer/TagPointer/…) means
        // a consumer skipped upstream resolution — throw.
        if (classInfo.purePath != null
                && classInfo.purePath.startsWith("meta::pure::metamodel::pointer::"))
        {
            String[] names = classInfo.nameBySlot();
            if (slotIdx < names.length)
            {
                String slotName = names[slotIdx];
                if (!isPointerNativeSlot(slotName))
                {
                    throw new IllegalStateException(
                            "Unresolved pointer access: " + classInfo.purePath + "." + slotName
                                    + " — dereference upstream via PointerGraphResolver or derefTypeIfPointer");
                }
            }
        }
        if (slotIdx >= slots.length)
        {
            // Two cases collapse here:
            //   1. This class declares no property at slotIdx (global slot
            //      exceeds classInfo.slotCount) — return null = "absent".
            //   2. Bootstrap-cycle: this PDO was constructed before classInfo
            //      grew. The slot IS in classInfo.nameBySlot but past this
            //      PDO's slots[]. Fall back to FB decode by name if we can.
            String[] names = classInfo.nameBySlot();
            if (fb != null && slotIdx < names.length && names[slotIdx] != null)
            {
                return decodeFromFb(names[slotIdx]);
            }
            return null;
        }
        Object v = slots[slotIdx];
        if (v == PureFbDecoder.LAZY)
        {
            return materialize(slotIdx);
        }
        return v;
    }

    /** Direct slot write by index. */
    public void writeSlot(int slotIdx, Object value)
    {
        slots[slotIdx] = value;
    }

    @TruffleBoundary
    private Object materialize(int slotIdx)
    {
        String[] names = classInfo.nameBySlot();
        // Global slot may exceed classInfo.nameBySlot() length OR map to a
        // slot this class doesn't declare (null entry). Both mean "property
        // not on this class" — store null and return; never pass null to a
        // typed FB PDBHelper's readProperty(switch) which would NPE.
        String name = slotIdx < names.length ? names[slotIdx] : null;
        if (name == null)
        {
            slots[slotIdx] = null;
            return null;
        }
        Object decoded = decodeFromFb(name);
        if (decoded == PropertyAccessor.ABSENT) decoded = null;
        slots[slotIdx] = decoded;
        return decoded;
    }

    @Override
    public Object readProperty(String name)
    {
        // Pointer-access contract (see feature_pointerAccess.pure) — see
        // readSlot for the rationale and pointer-native whitelist.
        if (classInfo.purePath != null
                && classInfo.purePath.startsWith("meta::pure::metamodel::pointer::")
                && !isPointerNativeSlot(name))
        {
            throw new IllegalStateException(
                    "Unresolved pointer access: " + classInfo.purePath + "." + name
                            + " — dereference upstream via PointerGraphResolver or derefTypeIfPointer");
        }
        int slot = classInfo.slotIndex(name);
        if (slot >= 0 && slot < slots.length)
        {
            return readSlot(slot);
        }
        return fb != null ? decodeFromFb(name) : PropertyAccessor.ABSENT;
    }

    // Slots readable on a pointer without tripping the strict guard.
    //   - `path` / `element`: pointer-native identity per m3.pure pointer
    //     hierarchy (`path` on TempCompilerPointer; `element` on
    //     PropertyPointer / QualifiedPropertyPointer / ColumnPointer /
    //     TagPointer / StereotypePointer / EnumPointer).
    //   - `classifierGenericType`: framework-required for every dispatch
    //     (match / genericType / subtypeOf). Reading it on a pointer
    //     returns whatever the producer set; a pointer with no CGT reads
    //     null and downstream getRawValueType correctly falls back to Any.
    //     Denying it would force every dispatch site to special-case
    //     pointers, which defeats the dispatch layer's purpose.
    //   - `generalizations`: framework-required for subtypeOf / linearization
    //     walks. A pointer's instance has no real ancestors at the Pure-data
    //     level (its hierarchy is metaclass-level, walked separately), so
    //     reading empty here gives the semantically correct answer (pointer
    //     IS its own type, no ancestor walk needed). Truffle's match
    //     dispatch goes through subtypeOf for every arm — bootstrap dodges
    //     it via Java instanceof on per-class generated impls — so the
    //     trip-on-pointer fires for Truffle uniquely. Silent-empty matches
    //     the pre-strict-guard working behavior here.
    private static boolean isPointerNativeSlot(String slotName)
    {
        return "path".equals(slotName)
                || "element".equals(slotName)
                || "classifierGenericType".equals(slotName)
                || "generalizations".equals(slotName);
    }

    @Override
    public void writeProperty(String name, Object value)
    {
        int slot = classInfo.slotIndex(name);
        if (slot < 0)
        {
            throw new IllegalArgumentException("writeProperty: " + classInfo.purePath
                    + " has no property '" + name + "'");
        }
        Class<?> paramType = classInfo.propTypes().get(name);
        if (paramType != null)
        {
            value = PropertyCoercion.coerce(value, paramType);
        }
        slots[slot] = value;
    }

    private Object decodeFromFb(String name)
    {
        if (fb == null) return null;
        if (fb instanceof PropertyAccessor accessor)
        {
            Object value = accessor.readProperty(name);
            return value == PropertyAccessor.ABSENT ? null : value;
        }
        // Cached on classInfo so we skip the per-call
        // {@link PureFbDecoderRegistry} HashMap lookup (which dominated
        // {@code StringLatin1.equals} in JFR for FB-backed reads).
        PureFbDecoderRegistry.Decoder d = classInfo.decoder();
        return d != null ? d.decode(name, fb, (TruffleMetadataAccess) resolver, this) : null;
    }

    @Override
    @TruffleBoundary
    public boolean equals(Object o)
    {
        if (this == o) return true;
        if (!(o instanceof PureDynamicObject other)) return false;
        if (classInfo != other.classInfo) return false;
        String[] keys = classInfo.equalityKeys();
        if (keys == null || keys.length == 0) return false;
        for (String k : keys)
        {
            if (!java.util.Objects.equals(readProperty(k), other.readProperty(k))) return false;
        }
        return true;
    }

    @Override
    @TruffleBoundary
    public int hashCode()
    {
        String[] keys = classInfo.equalityKeys();
        if (keys == null || keys.length == 0) return System.identityHashCode(this);
        int h = 1;
        for (String k : keys)
        {
            Object v = readProperty(k);
            // Only primitive-typed equality keys contribute, matching the
            // original XPDBHelper hashCode that filtered to String/Number/Boolean
            // to avoid recursive self-references.
            if (v instanceof String || v instanceof Number || v instanceof Boolean)
            {
                h = 31 * h + v.hashCode();
            }
        }
        return h;
    }
}
