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

package org.finos.legend.pure.truffle.runtime;

import java.util.List;
import java.util.Set;

/**
 * Per-resolver memoisation contract for type-shape derivations the JFR profile
 * flagged as the dominant runtime hot spots. Concrete implementation lives in
 * the runtime module; this interface lets the {@link TruffleMetadataAccess
 * resolver} expose it without dragging the generated PDB metamodel into
 * codegen's first compile pass (codegen generates {@code Type} as part of its
 * build, so codegen's hand-written code can't reference it directly).
 *
 * <p>Callers in the runtime module pass the typed {@code Type} instance as
 * {@code Object}; the implementation casts internally.</p>
 */
public interface TruffleTypeCache
{
    /** C3-style linearization: {@code self → supertypes → … → Any}. */
    List<?> linearization(Object type);

    /**
     * Identity-keyed set of {@code type}'s ancestors (same Types as {@link
     * #linearization} but addressable in O(1)). Use for subtype checks
     * where we need to test membership but not traversal order.
     */
    Set<?> ancestors(Object type);

    /** Names of properties stereotyped {@code <<meta::pure::profiles::equality.Key>>}. */
    Set<String> equalityKeyProperties(Object type);

    /**
     * Resolve a Pure class path (e.g. {@code "meta::pure::metamodel::type::Class"})
     * to the runtime Java {@code Impl} class. Cached because {@code Class.forName}
     * is the dominant {@code String.replace} hot caller in JFR and the result
     * is invariant for a given path.
     */
    Class<?> classForPath(String classPath);

    /**
     * Reflection-free instance creation: returns a fresh {@code Impl} instance
     * for {@code classPath}. The first call builds a typed {@code Supplier}
     * via {@code LambdaMetafactory}; subsequent calls are direct virtual
     * dispatches into the no-arg constructor with no per-call reflection.
     * Replaces {@code Class#getDeclaredConstructor().newInstance()}.
     */
    Object newInstance(String classPath);
}
