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

import meta.pure.metamodel.type.FunctionType;
import meta.pure.metamodel.type.FunctionTypeImpl;
import org.eclipse.collections.api.list.MutableList;
import org.finos.legend.pure.m3.module.localModule.topLevel.CompilationContext;
import org.finos.legend.pure.m3.module.MetadataAccess;

/**
 * Compiles a grammar-level {@link meta.pure.protocol.grammar.type.FunctionType}
 * into a metamodel-level {@link FunctionType}.
 */
public final class FunctionTypeCompiler
{
    private FunctionTypeCompiler()
    {
    }

    /**
     * Compile a grammar-level {@link meta.pure.protocol.grammar.type.FunctionType}
     * into a metamodel-level {@link FunctionType}.
     */
    public static FunctionType compile(meta.pure.protocol.grammar.type.FunctionType ft, MutableList<String> imports, MetadataAccess model, CompilationContext context)
    {
        FunctionTypeImpl result = new FunctionTypeImpl();
        if (ft._returnType() != null)
        {
            result._returnType(GenericTypeCompiler.compile(ft._returnType(), imports, model, context));
        }
        if (ft._returnMultiplicity() != null)
        {
            result._returnMultiplicity(MultiplicityCompiler.compile(ft._returnMultiplicity(), model));
        }
        if (ft._parameters() != null && ft._parameters().notEmpty())
        {
            result._parameters(ft._parameters().collect(ve ->
                    VariableCompiler.compileParameter(ve, imports, model, context)));
        }
        return result;
    }
}
