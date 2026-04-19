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

package org.finos.legend.pure.truffle.types;

/**
 * Base type for unboxed Pure collection storage.
 *
 * <p>A {@code PureSequence} is the runtime representation of a Pure
 * multi-valued expression ({@code [*]}, {@code [1..*]}, etc.) inside the
 * Truffle interpreter. Unlike {@link
 * meta.pure.metamodel.valuespecification.Collection} (which stores
 * individually-boxed {@code ValueSpecification} elements), a
 * {@code PureSequence} can keep primitives unboxed in typed backing
 * arrays — e.g., {@link LongSequence} stores a {@code long[]} directly,
 * avoiding {@code Long} boxing per element.</p>
 *
 * <p>Specialized collection nodes ({@code SizeNode}, {@code MapNode}, …)
 * dispatch on the concrete subclass. If a mixed-type operation forces a
 * transition (e.g., appending a String to a {@code LongSequence}), the
 * subclass materializes into an {@link ObjectSequence} via
 * {@code transferToInterpreterAndInvalidate} so Graal can re-specialize.</p>
 *
 * <p>Phase 4 scaffolding — subclasses exist, but the main runtime still
 * flows through {@code Collection} VSes. Wiring {@code CollectionNode}
 * to build typed sequences is future work.</p>
 */
public abstract class PureSequence
{
    public abstract int size();

    public abstract boolean isEmpty();

    public abstract Object getBoxed(int index);

    public abstract Object[] toBoxedArray();

    /**
     * Convert to a {@code java.util.List} for interop with code that
     * doesn't know about PureSequence (e.g. PureValuePrinter).
     */
    public java.util.List<Object> toList()
    {
        Object[] arr = toBoxedArray();
        return java.util.Arrays.asList(arr);
    }
}
