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

package org.finos.legend.pure.m3.module.localModule.topLevel;

import meta.pure.metamodel.PackageableElement;
import org.finos.legend.pure.m3.PureModel;
import org.finos.legend.pure.m3.module.MetadataAccess;

/**
 * Extension point for the compiler.
 *
 * <p>Implementations handle grammar element types that are not part
 * of the core M3 metamodel (e.g. test-only section types).
 * Extensions are passed to {@link PureModel#compile} and consulted
 * when the built-in switch does not match a grammar element.</p>
 */
public interface CompilerExtension
{
    CompilerContextExtension buildCompilerContextExtension();

    /**
     * First pass: create a metamodel element from a grammar element.
     *
     * @param grammar the grammar element to handle
     * @return a new metamodel element, or {@code null} if this
     *         extension does not handle the given element type
     */
    PackageableElement firstPass(meta.pure.protocol.grammar.PackageableElement grammar);

    /**
     * Second pass: resolve cross-references on a previously created element.
     *
     * @param entry   the index entry with the element and its grammar source
     * @param model   the model for element lookups
     * @param context the compilation context for error collection
     * @return the (possibly updated) element, or {@code null} if this
     *         extension does not handle the given element type
     */
    PackageableElement secondPass(IndexEntry entry, MetadataAccess model, CompilationContext context);

    /**
     * Hook called before the third pass begins.
     * Extensions can use this to build indices or perform
     * other preparation that must happen after the second pass
     * completes but before expression resolution starts.
     *
     * @param localModule
     * @param model       the model for element lookups
     */
    default void preThirdPass(MetadataAccess localModule, MetadataAccess model)
    {
    }

    /**
     * Third pass: resolve expressions, value specifications, and constraints.
     *
     * @param entry   the index entry with the element and its grammar source
     * @param model   the model for element lookups
     * @param context the compilation context for error collection
     * @return the (possibly updated) element, or {@code null} if this
     *         extension does not handle the given element type
     */
    PackageableElement thirdPass(IndexEntry entry, MetadataAccess model, CompilationContext context);
}
