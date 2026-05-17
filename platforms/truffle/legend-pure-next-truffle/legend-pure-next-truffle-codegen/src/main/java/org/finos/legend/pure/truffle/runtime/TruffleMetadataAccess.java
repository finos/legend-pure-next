// Copyright 2024 Goldman Sachs
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

import java.util.Set;

/**
 * Truffle-specific metadata access — no dependency on bootstrap types.
 * Returns raw {@code Object} that callers check via {@code instanceof}
 * against truffle-namespaced types.
 */
public interface TruffleMetadataAccess
{
    Object getElement(String path);

    boolean hasElement(String path);

    Set<String> elementPaths();

    /**
     * Reverse lookup: return the PDB path for an element previously loaded
     * via {@link #getElement(String)}.  Returns {@code null} if the element
     * was not loaded through this resolver (e.g. runtime-created PDBHelper).
     */
    default String pathOf(Object element)
    {
        return null;
    }

    /**
     * Per-resolver memoisation of type-shape derivations (linearization,
     * equality keys). Lives on the resolver so its lifetime tracks the
     * loaded modules — no shared static state.
     */
    TruffleTypeCache typeCache();
}
