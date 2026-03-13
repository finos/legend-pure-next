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

package org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.structural;

import meta.pure.metamodel.relationship.Generalization;
import meta.pure.metamodel.relationship.GeneralizationImpl;
import meta.pure.metamodel.type.Type;
import meta.pure.metamodel.type.generics.GenericType;
import org.eclipse.collections.api.list.MutableList;
import org.finos.legend.pure.m3.module.localModule.topLevel.CompilationContext;
import org.finos.legend.pure.m3.module.MetadataAccess;

/**
 * Compiles a grammar-level {@link meta.pure.protocol.grammar.relationship.Generalization}
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
     * @param model                 the compiled PureModel used for element lookup
     * @param context               the compilation context for error collection
     * @return a fully resolved metamodel Generalization, or null if the type is unresolvable
     */
    public static Generalization compile(meta.pure.protocol.grammar.relationship.Generalization grammarGeneralization, Type specific, MutableList<String> imports, MetadataAccess model, CompilationContext context)
    {
        int errorsBefore = context.currentErrorCount();
        GenericType general = GenericTypeCompiler.compile(grammarGeneralization._general(), imports, model, context);
        if (general == null)
        {
            context.enrichCurrentErrorsFrom(errorsBefore, "super type");
            return null;
        }
        return new GeneralizationImpl()
                ._general(general)
                ._specific(specific);
    }
}
