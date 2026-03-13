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

package org.finos.legend.pure.m3.module;

import org.finos.legend.pure.m3.PureModel;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * A module that provides access to pre-compiled Pure elements.
 *
 * <p>Modules are the unit of dependency for the Pure compiler —
 * a project can depend on one or more modules, each providing
 * elements and (optionally) a function index for compilation.</p>
 *
 * <p>Extends {@link ModuleDefinition} for identity and dependency
 * contract, and {@link MetadataAccess} for element retrieval
 * and index access.</p>
 */
public interface Module extends ModuleDefinition, MetadataAccess
{
    /**
     * Set the owning PureModel, enabling cross-module resolution.
     */
    void setPureModel(PureModel model);

    /**
     * Compile this module. Returns a result with any compilation errors.
     * By default returns an empty (successful) result.
     */
    default CompilationResult compile()
    {
        return new CompilationResult(List.of());
    }
}
