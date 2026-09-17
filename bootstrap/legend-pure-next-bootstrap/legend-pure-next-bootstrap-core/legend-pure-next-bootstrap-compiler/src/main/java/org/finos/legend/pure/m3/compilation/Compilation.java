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

package org.finos.legend.pure.m3.compilation;

import org.finos.legend.pure.m3.module.CompilationResult;
import org.finos.legend.pure.m3.module.sourceModule.SourceModule;

import java.util.List;

/**
 * A compilation strategy: turns Pure sources into compiled elements against the
 * runtime's registry, using the runtime's language extensions (parser + compiler
 * per section). {@code PureRuntime} holds one, next to its execution strategy.
 */
public interface Compilation
{
    /** Compile a source module (its source folders or contents) against the registry. */
    CompilationResult compile(SourceModule module);

    /**
     * Compile already-parsed {@code meta::pure::protocol::PureFile} values — what the
     * {@code compileSource} native receives.
     *
     * @throws CompilationUnavailableException when this strategy cannot compile them
     */
    default CompilationResult compileParsed(List<?> pureFiles)
    {
        throw new CompilationUnavailableException(getClass().getSimpleName() + " cannot compile parsed PureFile values");
    }

    /**
     * Compile the registry's own {@code SourceModule}s in place, in dependency order, in one pass
     * (they keep their compiled state, e.g. for an IDE's navigation).
     *
     * @throws CompilationUnavailableException when this strategy cannot compile modules in place
     */
    default CompilationResult compileSourceModules()
    {
        throw new CompilationUnavailableException(getClass().getSimpleName() + " cannot compile registered source modules in place");
    }

    /** Builds the strategy from what the runtime provides. */
    @FunctionalInterface
    interface Builder
    {
        Compilation build(CompilationContext context);
    }
}
