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

package org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper;

import meta.pure.metamodel.SourceInformation;
import meta.pure.metamodel.function.Function;
import org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.PureLanguageCompilerContext;
import org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.resolution.valueSpecification.functionExpressionResolver.LazyPackageableFunction;
import meta.pure.metamodel.type.FunctionType;
import meta.pure.metamodel.type.Type;
import meta.pure.metamodel.type.generics.GenericType;
import meta.pure.metamodel.valuespecification.VariableExpression;
import org.eclipse.collections.api.list.MutableList;
import org.finos.legend.pure.m3.module.localModule.topLevel.CompilationContext;
import org.finos.legend.pure.m3.module.localModule.topLevel.CompilationError;
import org.finos.legend.pure.m3.module.MetadataAccess;

/**
 * Helper methods for {@link meta.pure.metamodel.function.Function}.
 */
public class _Function
{
    private _Function()
    {
        // static utility
    }

    /**
     * Resolve the {@code Function<T>} generic type for a given source generic type
     * by walking up the type hierarchy to {@code Function}.
     *
     * <p>For example, given {@code Property<Person, String|*>}, this resolves
     * through the generalization chain to return
     * {@code Function<FunctionType{Person[1]->String[*]}>}.
     *
     * @param genericType the source generic type to resolve
     * @param model       the compiled PureModel (used to look up the Function type)
     * @return the resolved GenericType for Function, or {@code null} if Function
     *         is not in the source's hierarchy
     */
    public static FunctionType resolveFunctionType(GenericType genericType, MetadataAccess model)
    {
        Type functionType = (Type) model.getElement("meta::pure::metamodel::function::Function");
        return (FunctionType)_GenericType.resolveForTarget(genericType, functionType, model)._typeArguments().getFirst()._rawType();
    }

    /**
     * Get the {@link FunctionType} for a function, preferring the cached type
     * from the {@link LazyPackageableFunction} proxy (no element loading) when available,
     * falling back to {@link #resolveFunctionType} for non-lazy functions.
     */
    public static FunctionType getFunctionType(Function func, MetadataAccess model)
    {
        LazyPackageableFunction lazy = LazyPackageableFunction.unwrap(func);
        if (lazy != null)
        {
            return lazy.functionType();
        }
        return resolveFunctionType(func._classifierGenericType(), model);
    }

    public static void validateFunctionParameters(MutableList<VariableExpression> params, CompilationContext context, SourceInformation sourceInformation)
    {
        params.collect(meta.pure.metamodel.valuespecification.VariableExpression::_name)
                .toBag().selectDuplicates().toSet()
                .each(name -> context.addError(new CompilationError(
                        "Duplicate parameter name '" + name + "'",
                        sourceInformation)));

        // For lambda
        for (VariableExpression param : params)
        {
            VariableExpression existing = context.compilerContextExtensions(PureLanguageCompilerContext.class).resolveVariable(param._name());
            if (existing != null)
            {
                context.addError(new CompilationError("Variable '" + param._name() + "' is already defined", sourceInformation));
            }
        }
    }
}
