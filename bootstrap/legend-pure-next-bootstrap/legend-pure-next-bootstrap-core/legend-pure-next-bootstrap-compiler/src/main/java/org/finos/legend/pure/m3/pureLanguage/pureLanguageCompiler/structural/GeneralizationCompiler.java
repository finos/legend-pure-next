// Copyright 2024 Goldman Sachs
// ©2026 JP Morgan Chase & Co. All rights reserved.
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

package org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.structural;

import platform.pure.metamodel.relationship.Generalization;
import platform.pure.metamodel.relationship.GeneralizationImpl;
import platform.pure.metamodel.type.Type;
import platform.pure.metamodel.type.generics.GenericType;
import org.eclipse.collections.api.list.MutableList;
import org.finos.legend.pure.m3.module.MetadataAccess;
import org.finos.legend.pure.m3.module.sourceModule.topLevel.CompilationContext;

/**
 * Compiles a grammar-level {@link platform.pure.protocol.grammar.relationship.Generalization}
 * into a metamodel-level {@link Generalization}, leveraging {@link GenericTypeCompiler}
 * to resolve the {@code general} generic type.
 */
public final class GeneralizationCompiler
{
    private GeneralizationCompiler()
    {
    }

    /**
     * Compile a grammar Generalization into a metamodel Generalization.
     * Returns null if the general type cannot be resolved.
     *
     * @param grammarGeneralization the grammar-level generalization to compile
     * @param specific              the owning type (subtype side of the generalization)
     * @param imports               import package paths from the enclosing section
     * @param model                 the compiled JavaCompiler used for element lookup
     * @param context               the compilation context for error collection
     * @return a fully resolved metamodel Generalization, or null if the type is unresolvable
     */
    public static Generalization compile(platform.pure.protocol.grammar.relationship.Generalization grammarGeneralization, Type specific, MutableList<String> imports, MetadataAccess model, CompilationContext context)
    {
        int errorsBefore = context.currentErrorCount();
        GenericType general = GenericTypeCompiler.compile(grammarGeneralization._general(), imports, model, context);
        if (general == null)
        {
            context.enrichCurrentErrorsFrom(errorsBefore, "super type");
            return null;
        }
        return new GeneralizationImpl(model)
                ._general(general)
                ._specific(specific);
    }
}
