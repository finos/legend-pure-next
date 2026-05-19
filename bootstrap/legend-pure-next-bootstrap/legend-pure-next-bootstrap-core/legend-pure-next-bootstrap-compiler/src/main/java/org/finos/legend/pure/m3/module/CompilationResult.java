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

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Result of compilation: errors collected, compilation statistics, and the
 * reverse reference index built during compile.
 *
 * <p>The {@code referencedBy} map records every cross-element lookup that
 * occurred during compilation as {@code (targetPath, callerPaths)}: a key is
 * the path of an element that was looked up; its value is the set of paths
 * of elements that referenced it. The map is built as a side-effect by
 * {@code RecordingMetadataAccess} and serialized into the produced PDB
 * alongside the elements. Consumers include validators (e.g. lean→test
 * reference enforcement), IDE features (find-references), and any tooling
 * that needs to navigate the call/use graph without re-walking the
 * metamodel.</p>
 */
public record CompilationResult(
        List<CompilationError> errors,
        CompilationStatistics statistics,
        Map<String, Set<String>> referencedBy)
{
}
