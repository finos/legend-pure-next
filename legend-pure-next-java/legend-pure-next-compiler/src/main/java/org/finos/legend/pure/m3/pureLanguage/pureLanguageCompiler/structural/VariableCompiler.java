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

import meta.pure.metamodel.type.generics.GenericType;
import meta.pure.metamodel.valuespecification.VariableExpression;
import meta.pure.metamodel.valuespecification.VariableExpressionImpl;
import org.eclipse.collections.api.list.MutableList;
import org.finos.legend.pure.m3.module.localModule.topLevel.CompilationContext;
import org.finos.legend.pure.m3.module.MetadataAccess;

/**
 * Compiles a grammar-level {@link meta.pure.protocol.grammar.valuespecification.VariableExpression}
 * into a metamodel-level {@link VariableExpression}, resolving the parameter's
 * generic type and multiplicity.
 */
public final class VariableCompiler
{
    private VariableCompiler()
    {
    }

    /**
     * Compile a grammar VariableExpression (function parameter) into a metamodel VariableExpression.
     * Returns null if the parameter's type cannot be resolved.
     *
     * @param grammarParam the grammar-level variable expression to compile
     * @param imports      import package paths from the enclosing section
     * @param model        the compiled PureModel used for element lookup
     * @param context      the compilation context for error collection
     * @return a fully resolved metamodel VariableExpression, or null if the type is unresolvable
     */
    public static VariableExpression compileParameter(meta.pure.protocol.grammar.valuespecification.VariableExpression grammarParam, MutableList<String> imports, MetadataAccess model, CompilationContext context)
    {
        int errorsBefore = context.currentErrorCount();
        GenericType genericType = GenericTypeCompiler.compile(grammarParam._genericType(), imports, model, context);
        if (genericType == null)
        {
            if (grammarParam._name() != null)
            {
                context.enrichCurrentErrorsFrom(errorsBefore, "parameter '" + grammarParam._name() + "'");
            }
            return null;
        }
        return new VariableExpressionImpl()
                ._name(grammarParam._name())
                ._genericType(genericType)
                ._multiplicity(MultiplicityCompiler.compile(grammarParam._multiplicity(), model));
    }
}
