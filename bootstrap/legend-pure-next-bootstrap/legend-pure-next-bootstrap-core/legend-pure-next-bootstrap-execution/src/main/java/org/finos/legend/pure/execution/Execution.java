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

package org.finos.legend.pure.execution;

import meta.pure.metamodel.function.FunctionWithParameters;

/**
 * An execution strategy: runs compiled Pure functions over a registry.
 *
 * <p>{@code PureRuntime} holds one. Natives belong to the strategy (e.g.
 * {@link InterpretedExecution} walks the expression tree with a {@code NativeRegistry});
 * language and grammar extensions belong to the runtime, which passes them in through
 * {@link ExecutionContext}.</p>
 */
public interface Execution
{
    Object execute(FunctionWithParameters function, Object... args);

    default void close()
    {
    }

    /**
     * Builds the strategy from what the runtime provides — the registry and the language
     * and grammar extensions. They arrive at build time because natives capture them
     * when they register.
     */
    @FunctionalInterface
    interface Builder
    {
        Execution build(ExecutionContext context);
    }
}
