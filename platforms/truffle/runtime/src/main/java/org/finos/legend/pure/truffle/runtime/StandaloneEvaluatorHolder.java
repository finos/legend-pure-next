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

import org.finos.legend.pure.truffle.StandaloneEvaluator;

/**
 * Thread-local access to the active {@link StandaloneEvaluator}.
 *
 * <p>Set at the top of {@code PureTruffleRuntime.execute()}, cleared on
 * exit. Truffle nodes that need the evaluator (e.g., construction stack
 * for {@code new}, property dispatch) read it from here.</p>
 */
public final class StandaloneEvaluatorHolder
{
    private static final ThreadLocal<StandaloneEvaluator> CURRENT = new ThreadLocal<>();

    private StandaloneEvaluatorHolder()
    {
    }

    public static StandaloneEvaluator current()
    {
        return CURRENT.get();
    }

    public static void set(StandaloneEvaluator evaluator)
    {
        CURRENT.set(evaluator);
    }

    public static void clear()
    {
        CURRENT.remove();
    }
}
