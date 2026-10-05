// Copyright 2026 Goldman Sachs
// ©2026 JP Morgan Chase & Co. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package org.finos.legend.pure.truffle.compiler.module;

import org.finos.legend.pure.truffle.compiler.helper._GenericType;
import org.finos.legend.pure.truffle.compiler.helper._PackageableElement;
import org.finos.legend.pure.truffle.compiler.helper._Any;
import org.finos.legend.pure.truffle.execution.types.PureSequence;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Per-resolver memoization of two computations the JFR profile flagged as the
 * dominant runtime hot spots:
 *
 * <ul>
 *   <li><b>linearization</b>: C3 list of {@code self → supertypes → … → Any}.
 *       Recomputed on every {@code subtypeOf} / {@code findCommonType} call
 *       in the uncached implementation; here it's computed once per Type.</li>
 *   <li><b>equalityKeyProperties</b>: names of properties stereotyped with
 *       {@code <<meta::pure::profiles::equality.Key>>} from the Type and all
 *       its supertypes. Walks the same generalization chain; without caching
 *       it re-walks on every {@code equal()} call.</li>
 * </ul>
 *
 * <p>Lives on the {@link MetadataAccess
 * resolver} so its lifetime is bounded by the resolver — no shared static
 * state, and entries are collected when the resolver is dropped. Entries are
 * computed lazily on first lookup. Top-level PDB types are singletons per
 * resolver, so identity-based caching via the underlying {@link
 * ConcurrentHashMap} is safe. Both computations are pure functions of the
 * Type's structure, so a stale cache never matters as long as the Type
 * instance itself isn't replaced.</p>
 */
public final class TypeCache implements TruffleTypeCache
{

    private static final int SLOT_PROFILE = org.finos.legend.pure.truffle.compiler.module.PureClassRegistry.globalSlot("profile");
    private static final int SLOT_STEREOTYPES = org.finos.legend.pure.truffle.compiler.module.PureClassRegistry.globalSlot("stereotypes");
    private static final int SLOT_VALUE = org.finos.legend.pure.truffle.compiler.module.PureClassRegistry.globalSlot("value");
    private static final int SLOT_GENERAL =
            org.finos.legend.pure.truffle.compiler.module.PureClassRegistry.globalSlot("general");
    private static final int SLOT_GENERALIZATIONS =
            org.finos.legend.pure.truffle.compiler.module.PureClassRegistry.globalSlot("generalizations");
    private static final int SLOT_NAME =
            org.finos.legend.pure.truffle.compiler.module.PureClassRegistry.globalSlot("name");
    private static final int SLOT_PROPERTIES =
            org.finos.legend.pure.truffle.compiler.module.PureClassRegistry.globalSlot("properties");

    private static final String EQUALITY_PROFILE_PATH = "meta::pure::profiles::equality";
    private static final String EQUALITY_KEY_VALUE = "Key";
    private static final int MAX_GENERALIZATION_DEPTH = 64;

    private static final Entry EMPTY = new Entry(Collections.emptyList(), Collections.emptySet(), Collections.emptySet());

    // IdentityHashMap with copy-on-write semantics — the generated PDB Type
    // classes have structural equals/hashCode that recurse through
    // generalizations and can blow the stack on any cyclic shape, so
    // ConcurrentHashMap is unsafe; identity is also semantically correct
    // (top-level PDB types are singletons per resolver).
    //
    // Reads (cache hits, the hot path) are unsynchronized — they see the
    // current snapshot via the volatile reference. Writes take a monitor,
    // copy the entire map, add the new entry, and atomically install the
    // new snapshot. This is the right shape for a JFR-measured workload
    // where ~all subtypeOf / equalityKey lookups hit a cache populated
    // during warmup; under the previous synchronizedMap wrapper, every
    // read paid a monitor enter/exit (46 JFR samples = ~2.7% of warm CPU
    // on the metamodel_factories.pure self-host).
    private volatile IdentityHashMap<Object, Entry> entries = new IdentityHashMap<>();

    @Override
    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    public List<Object> linearization(Object type)
    {
        return entryFor(type, null).linearization;
    }

    @Override
    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    public Set<String> equalityKeyProperties(Object type)
    {
        return entryFor(type, null).equalityKeys;
    }

    @Override
    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    public Set<Object> ancestors(Object type)
    {
        return entryFor(type, null).ancestors;
    }

    @Override
    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    public Set<Object> ancestors(Object type, MetadataAccess resolver)
    {
        return entryFor(type, resolver).ancestors;
    }

    private Entry entryFor(Object type, MetadataAccess resolver)
    {
        if (type == null)
        {
            return EMPTY;
        }
        // Fast path — unsynchronized read of the volatile snapshot. Once
        // entries are populated during warmup, this is the only branch
        // hit and avoids the monitor enter/exit.
        Entry hit = entries.get(type);
        if (hit != null)
        {
            return hit;
        }
        return computeAndCache(type, resolver);
    }

    private synchronized Entry computeAndCache(Object t, MetadataAccess resolver)
    {
        // Re-check inside the lock — another thread may have populated.
        Entry hit = entries.get(t);
        if (hit != null)
        {
            return hit;
        }
        Entry computed = compute(t, resolver);
        IdentityHashMap<Object, Entry> next = new IdentityHashMap<>(entries);
        next.put(t, computed);
        entries = next;
        return computed;
    }

    private static Entry compute(Object type, MetadataAccess resolverOrNull)
    {
        // Prefer the caller's resolver; only fall back to PureLanguage.get(null)
        // when none was threaded in. The fallback is null on threads outside an
        // active Pure execution, so the
        // resolver-aware ancestors(type, resolver) path must be used there.
        // Linearization needs the resolver to dereference {@link
        // meta.pure.metamodel.pointer.TempCompilerPointer} subtypes that
        // compile-pure emits in {@code generalizations.general.type}.
        var resolver = resolverOrNull != null
                ? resolverOrNull
                : org.finos.legend.pure.truffle.execution.PureLanguage.get(null).resolver();
        List<Object> lin = new ArrayList<>();
        linearizeInto(type, lin, resolver);
        Set<String> keys = new LinkedHashSet<>();
        // Type IS-A SimplePropertyOwner in Pure (Class, Association, etc.) —
        // _Any.read returns null for the absent slot when this isn't true,
        // and collectEqualityKeysInto gracefully no-ops on a null _properties.
        //
        // Pointer types are path-only stand-ins — `==` semantics for them is
        // path equality, not user-property equality. There are no user
        // equality keys to walk. Skip — otherwise `collectEqualityKeysInto`
        // reads `.properties` on the pointer instance and trips the strict
        // pointer guard (which correctly rejects user-data reads on
        // pointers).
        boolean isPointer = org.finos.legend.pure.truffle.compiler.helper._PackageableElement.pointerPath(type) != null;
        if (!isPointer)
        {
            collectEqualityKeysInto(type, keys, new LinkedHashSet<>(), 0, resolver);
        }
        // Identity-keyed set of ancestors for O(1) subtypeOf check. We can't
        // use HashSet (Type's structural equals/hashCode recurse through
        // generalisations and blow the stack on cyclic shapes); identity is
        // semantically correct here since top-level PDB types are singletons
        // per resolver.
        Set<Object> ancestors = Collections.newSetFromMap(new IdentityHashMap<>(lin.size() * 2));
        ancestors.addAll(lin);
        return new Entry(
                List.copyOf(lin),
                keys.isEmpty() ? Collections.emptySet() : Set.copyOf(keys),
                ancestors);
    }

    // --- linearization ------------------------------------------------------

    private static void linearizeInto(Object type, List<Object> out,
            MetadataAccess resolver)
    {
        if (type == null)
        {
            return;
        }
        // Identity check — generated Type.equals walks the structure and
        // can recurse through cyclic generalisations (see entries field).
        for (Object seen : out)
        {
            if (seen == type)
            {
                return;
            }
        }
        out.add(type);
        Object gens = _Any.readBySlot(type, SLOT_GENERALIZATIONS);
        if (gens instanceof PureSequence seq)
        {
            for (Object gen : seq.toBoxedArray())
            {
                if (gen != null)
                {
                    Object superType = _GenericType.type(_Any.readBySlot(gen, SLOT_GENERAL));
                    linearizeInto(superType, out, resolver);
                }
            }
        }
    }

    // --- equality keys ------------------------------------------------------

    private static void collectEqualityKeysInto(Object owner, Set<String> keys,
            Set<String> seenPropNames, int depth,
            MetadataAccess resolver)
    {
        // Cycle/runaway guard — generalization chains are usually shallow but
        // FlatBuffer wrappers can produce duplicate-but-non-identical property
        // owners along the way; the depth limit keeps us bounded.
        if (depth > MAX_GENERALIZATION_DEPTH)
        {
            return;
        }
        Object propsObj = _Any.readBySlot(owner, SLOT_PROPERTIES);
        if (propsObj instanceof PureSequence properties)
        {
            for (Object prop : properties.toBoxedArray())
            {
                if (prop == null)
                {
                    continue;
                }
                Object nameObj = _Any.readBySlot(prop, SLOT_NAME);
                if (!(nameObj instanceof String propName) || !seenPropNames.add(propName))
                {
                    continue;
                }
                if (hasEqualityKeyStereotype(prop, resolver))
                {
                    keys.add(propName);
                }
            }
        }
        Object gensObj = _Any.readBySlot(owner, SLOT_GENERALIZATIONS);
        if (gensObj instanceof PureSequence gens)
        {
            for (Object gen : gens.toBoxedArray())
            {
                if (gen == null)
                {
                    continue;
                }
                Object general = _Any.readBySlot(gen, SLOT_GENERAL);
                if (general == null)
                {
                    continue;
                }
                Object superType = _GenericType.type(general);
                if (superType != null)
                {
                    collectEqualityKeysInto(superType, keys, seenPropNames, depth + 1, resolver);
                }
            }
        }
    }

    private static boolean hasEqualityKeyStereotype(Object prop, MetadataAccess resolver)
    {
        Object stereotypesObj = _Any.readBySlot(prop, SLOT_STEREOTYPES);
        if (!(stereotypesObj instanceof PureSequence stereotypes))
        {
            return false;
        }
        for (Object ster : stereotypes.toBoxedArray())
        {
            if (ster == null)
            {
                continue;
            }
            if (!EQUALITY_KEY_VALUE.equals(_Any.readBySlot(ster, SLOT_VALUE)))
            {
                continue;
            }
            Object profile = _Any.readBySlot(ster, SLOT_PROFILE);
            if (profile != null && EQUALITY_PROFILE_PATH.equals(_PackageableElement.path(profile, resolver)))
            {
                return true;
            }
        }
        return false;
    }

    private record Entry(List<Object> linearization, Set<String> equalityKeys, Set<Object> ancestors) {}
}
