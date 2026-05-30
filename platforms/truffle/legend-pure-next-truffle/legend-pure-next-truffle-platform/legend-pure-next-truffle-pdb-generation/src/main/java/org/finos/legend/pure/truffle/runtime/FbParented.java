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

/**
 * Common parent-link contract implemented by every generated FlatBuffer
 * wrapper. Replaces reflection-based AncestorRef walks
 * ({@code t.getClass().getMethod("_fbParent").invoke(t)}) with a single
 * virtual call — Graal can devirtualize the {@code _fbParent} dispatch and
 * partial-evaluate through it. The reflection-based variant brought in
 * {@code SignatureParser} / {@code Method.invoke} machinery that exhausted
 * the inliner's depth budget and caused 800+ "Too deep inlining" Graal
 * compilation failures during self-host.
 */
public interface FbParented
{
    /**
     * The parent FBW that owns this wrapper, set at construction time.
     * Used to resolve PDB AncestorRef pointers (relative parent walks)
     * without a path lookup.
     */
    Object _fbParent();
}
