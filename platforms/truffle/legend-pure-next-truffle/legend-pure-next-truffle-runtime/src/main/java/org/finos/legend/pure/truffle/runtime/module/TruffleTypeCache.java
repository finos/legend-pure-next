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

package org.finos.legend.pure.truffle.runtime.module;

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

    /**
     * Resolver-aware variant of {@link #ancestors(Object)}. The no-arg form
     * pulls the resolver from {@code PureLanguage.get(null)}, which is null on
     * threads/contexts outside an active Pure execution. Pass the caller's
     * resolver so a first-time {@code compute} for an uncached type doesn't NPE
     * there; it is also what dereferences {@code TempCompilerPointer} subtypes
     * during linearization, so this variant is needed independently of who
     * calls it. Default delegates to the no-arg form.
     */
    default Set<?> ancestors(Object type, TruffleMetadataAccess resolver)
    {
        return ancestors(type);
    }

    /** Names of properties stereotyped {@code <<meta::pure::profiles::equality.Key>>}. */
    Set<String> equalityKeyProperties(Object type);
}
