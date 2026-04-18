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

import org.finos.legend.pure.execution.ValueSpecificationEvaluator;

/**
 * Thread-local holder for the active {@link ValueSpecificationEvaluator}.
 *
 * <p>Phase B simplification: rather than carry the evaluator through every
 * Truffle node as a field, we stash it per-thread. AST nodes and the native
 * bridge read from here. This also lets us stay compatible with Java natives
 * that already expect a {@code ValueSpecificationEvaluator} parameter — we
 * simply pass the active one.</p>
 *
 * <p>Phase C+ may replace this with a {@code @ContextReference}-backed
 * lookup via {@code PureContext} once the Truffle language lifecycle is
 * stabilized.</p>
 */
public final class EvaluatorHolder
{
    private static final ThreadLocal<ValueSpecificationEvaluator> CURRENT = new ThreadLocal<>();

    private EvaluatorHolder()
    {
    }

    public static ValueSpecificationEvaluator current()
    {
        ValueSpecificationEvaluator e = CURRENT.get();
        if (e == null)
        {
            throw new IllegalStateException("No active ValueSpecificationEvaluator for current thread");
        }
        return e;
    }

    public static void set(ValueSpecificationEvaluator evaluator)
    {
        CURRENT.set(evaluator);
    }

    public static void clear()
    {
        CURRENT.remove();
    }
}
