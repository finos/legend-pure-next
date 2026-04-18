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

import com.oracle.truffle.api.dsl.TypeSystem;

/**
 * Declares the runtime types specialized Truffle nodes operate on.
 *
 * <p>Order is significant: the generated type-check cascade tries the most
 * specific type first. A specialized node like {@code PlusIntegerNode} that
 * declares {@code @Specialization(long + long)} will use this cascade at
 * every argument slot.</p>
 *
 * <p>The type order below mirrors Pure's primitive hierarchy:</p>
 * <ul>
 *   <li>{@code PureNull} — singleton sentinel for empty multiplicity
 *       ({@code [0..0]}) and missing {@code [0..1]} values. First so it's
 *       cheapest to detect (identity check).</li>
 *   <li>{@code long} — Pure {@code Integer[1]}. Covers the vast majority of
 *       hot-path arithmetic.</li>
 *   <li>{@code double} — Pure {@code Float[1]}.</li>
 *   <li>{@code boolean} — Pure {@code Boolean[1]}.</li>
 *   <li>{@code String} — Pure {@code String[1]}.</li>
 * </ul>
 *
 * <p>{@code Object} catches every other runtime shape: {@code BigDecimal}
 * for {@code Decimal}, {@code LocalDate} / {@code Instant} for {@code Date},
 * {@code PureSequence} subclasses for typed collections, generated class
 * instances (e.g. {@code CompilerContextImpl}), {@code DynamicInstance} for
 * runtime-only classes, {@code ValueSpecification} for bridged natives, and
 * {@code Closure} / {@code PureClosure} for lambdas.</p>
 *
 * <p>Specialized nodes should never accept {@code ValueSpecification}
 * directly — the {@link ValueAdapter} boundary unwraps before specialized
 * dispatch and rewraps only when crossing into a bridged native.</p>
 */
@TypeSystem({
        PureNull.class,
        long.class,
        double.class,
        boolean.class,
        String.class,
})
public abstract class PureTypes
{
}
