// Copyright 2026 Goldman Sachs
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

package org.finos.legend.pure.truffle.runtime.helper;

import org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.PackageableElement;
import org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.SimplePropertyOwner;
import org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.extension.Stereotype;
import org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.function.property.Property;
import org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.relationship.Generalization;
import org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.type.Type;
import org.finos.legend.pure.truffle.runtime.TruffleTypeCache;
import org.finos.legend.pure.truffle.types.PureSequence;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
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
 * <p>Lives on the {@link org.finos.legend.pure.truffle.runtime.TruffleMetadataAccess
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
    private static final String EQUALITY_PROFILE_PATH = "meta::pure::profiles::equality";
    private static final String EQUALITY_KEY_VALUE = "Key";
    private static final int MAX_GENERALIZATION_DEPTH = 64;

    private static final Entry EMPTY = new Entry(Collections.emptyList(), Collections.emptySet());

    // IdentityHashMap (synchronized) — the generated PDB Type classes have
    // structural equals/hashCode that recurse through generalizations and
    // can blow the stack on any cyclic shape. Identity is also semantically
    // correct: top-level PDB types are singletons per resolver.
    private final Map<Type, Entry> entries = Collections.synchronizedMap(new IdentityHashMap<>());
    private final Map<String, Class<?>> classCache = new java.util.concurrent.ConcurrentHashMap<>();
    /**
     * Pre-built {@link java.util.function.Supplier} per Pure class path.
     * Each entry wraps a {@link java.lang.invoke.MethodHandle} produced via
     * {@link java.lang.invoke.LambdaMetafactory} from the Impl class's
     * no-arg constructor — invocation is a direct virtual call into the
     * constructor body, no reflection per use. JFR identified
     * {@code Constructor.newInstance} + JDK access-check overhead at ~7%
     * of self-compile CPU before this cache.
     */
    private final Map<String, java.util.function.Supplier<Object>> instanceFactoryCache =
            new java.util.concurrent.ConcurrentHashMap<>();

    @Override
    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    public List<Type> linearization(Object type)
    {
        return entryFor(type).linearization;
    }

    @Override
    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    public Set<String> equalityKeyProperties(Object type)
    {
        return entryFor(type).equalityKeys;
    }

    @Override
    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    public Class<?> classForPath(String classPath)
    {
        return classCache.computeIfAbsent(classPath, org.finos.legend.pure.truffle.runtime.TruffleInstanceFactory::resolveClass);
    }

    /**
     * Reflection-free factory for the Impl class at {@code classPath}. The
     * first lookup builds a typed {@link java.util.function.Supplier} via
     * {@link java.lang.invoke.LambdaMetafactory}; subsequent calls are
     * direct virtual dispatches with no per-call reflection.
     */
    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    public Object newInstance(String classPath)
    {
        return instanceFactoryCache.computeIfAbsent(classPath, this::buildInstanceFactory).get();
    }

    private java.util.function.Supplier<Object> buildInstanceFactory(String classPath)
    {
        Class<?> implClass = classForPath(classPath);
        try
        {
            java.lang.invoke.MethodHandles.Lookup lookup = java.lang.invoke.MethodHandles.lookup();
            java.lang.invoke.MethodHandle ctor = lookup.findConstructor(
                    implClass, java.lang.invoke.MethodType.methodType(void.class));
            // LambdaMetafactory turns the constructor MethodHandle into a
            // typed Supplier<Object> implementation. Subsequent invocations
            // of the Supplier are direct invocations of the no-arg
            // constructor — no reflection, no access check, no permission
            // walk. This is the standard "fast reflection" pattern.
            java.lang.invoke.CallSite site = java.lang.invoke.LambdaMetafactory.metafactory(
                    lookup,
                    "get",
                    java.lang.invoke.MethodType.methodType(java.util.function.Supplier.class),
                    java.lang.invoke.MethodType.methodType(Object.class),
                    ctor,
                    java.lang.invoke.MethodType.methodType(Object.class));
            @SuppressWarnings("unchecked")
            java.util.function.Supplier<Object> supplier =
                    (java.util.function.Supplier<Object>) site.getTarget().invokeExact();
            return supplier;
        }
        catch (Throwable t)
        {
            throw new RuntimeException("Failed to build factory for " + classPath, t);
        }
    }

    private Entry entryFor(Object type)
    {
        if (!(type instanceof Type t))
        {
            return EMPTY;
        }
        return entries.computeIfAbsent(t, TypeCache::compute);
    }

    private static Entry compute(Type type)
    {
        List<Type> lin = new ArrayList<>();
        linearizeInto(type, lin);
        Set<String> keys = new LinkedHashSet<>();
        if (type instanceof SimplePropertyOwner spo)
        {
            collectEqualityKeysInto(spo, keys, new LinkedHashSet<>(), 0);
        }
        return new Entry(
                List.copyOf(lin),
                keys.isEmpty() ? Collections.emptySet() : Set.copyOf(keys));
    }

    // --- linearization ------------------------------------------------------

    private static void linearizeInto(Type type, List<Type> out)
    {
        if (type == null)
        {
            return;
        }
        // Identity check — generated Type.equals walks the structure and
        // can recurse through cyclic generalisations (see entries field).
        for (Type seen : out)
        {
            if (seen == type)
            {
                return;
            }
        }
        out.add(type);
        Object gens = type._generalizations();
        if (gens instanceof PureSequence seq)
        {
            for (Object gen : seq.toBoxedArray())
            {
                if (gen instanceof Generalization g)
                {
                    Type superType = _GenericType.type(g._general());
                    linearizeInto(superType, out);
                }
            }
        }
    }

    // --- equality keys ------------------------------------------------------

    private static void collectEqualityKeysInto(SimplePropertyOwner owner, Set<String> keys,
            Set<String> seenPropNames, int depth)
    {
        // Cycle/runaway guard — generalization chains are usually shallow but
        // FlatBuffer wrappers can produce duplicate-but-non-identical property
        // owners along the way; the depth limit keeps us bounded.
        if (depth > MAX_GENERALIZATION_DEPTH)
        {
            return;
        }
        PureSequence properties = owner._properties();
        if (properties != null)
        {
            for (Object p : properties.toBoxedArray())
            {
                if (!(p instanceof Property prop))
                {
                    continue;
                }
                String propName = prop._name();
                if (propName == null || !seenPropNames.add(propName))
                {
                    continue;
                }
                if (hasEqualityKeyStereotype(prop))
                {
                    keys.add(propName);
                }
            }
        }
        if (owner instanceof Type type && type._generalizations() != null)
        {
            for (Object gen : type._generalizations().toBoxedArray())
            {
                if (gen instanceof Generalization g && g._general() != null)
                {
                    Type superType = _GenericType.type(g._general());
                    if (superType instanceof SimplePropertyOwner superOwner)
                    {
                        collectEqualityKeysInto(superOwner, keys, seenPropNames, depth + 1);
                    }
                }
            }
        }
    }

    private static boolean hasEqualityKeyStereotype(Property prop)
    {
        PureSequence stereotypes = prop._stereotypes();
        if (stereotypes == null)
        {
            return false;
        }
        for (Object st : stereotypes.toBoxedArray())
        {
            if (st instanceof Stereotype ster
                    && EQUALITY_KEY_VALUE.equals(ster._value())
                    && ster._profile() instanceof PackageableElement profile
                    && EQUALITY_PROFILE_PATH.equals(_PackageableElement.path(profile)))
            {
                return true;
            }
        }
        return false;
    }

    private record Entry(List<Type> linearization, Set<String> equalityKeys) {}
}
