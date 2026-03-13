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

package org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.elements;

import meta.pure.metamodel.function.UserDefinedFunction;
import meta.pure.metamodel.function.UserDefinedFunctionImpl;
import meta.pure.metamodel.type.generics.MultiplicityParameter;
import meta.pure.metamodel.type.generics.TypeParameter;
import org.eclipse.collections.api.list.MutableList;
import org.eclipse.collections.impl.factory.Sets;
import org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.PureLanguageCompilerContext;
import org.finos.legend.pure.m3.module.localModule.topLevel.CompilationContext;
import org.finos.legend.pure.m3.module.MetadataAccess;
import org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.resolution.FunctionDefinitionResolver;
import org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.structural.AnnotationCompiler;
import org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.structural.PackageableFunctionCompiler;
import org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.structural.ValueSpecificationCompiler;

import java.util.Objects;

/**
 * Handler for UserDefinedFunction.
 */
public final class UserDefinedFunctionHandler
{
    private UserDefinedFunctionHandler()
    {
    }

    public static UserDefinedFunction firstPass(meta.pure.protocol.grammar.function.UserDefinedFunction grammar)
    {
        return new UserDefinedFunctionImpl()
                ._name(grammar._name())
                ._functionName(grammar._functionName());
    }

    public static UserDefinedFunction secondPass(UserDefinedFunctionImpl result, meta.pure.protocol.grammar.function.UserDefinedFunction grammar, MutableList<String> imports, MetadataAccess model, CompilationContext context)
    {
        PackageableFunctionCompiler.compileShared(result, grammar, imports, model, context);

        // Compile stereotypes and tagged values
        if (grammar._stereotypes() != null && grammar._stereotypes().notEmpty())
        {
            result._stereotypes(grammar._stereotypes()
                    .collect(s -> AnnotationCompiler.resolveStereotype(s, imports, model, context))
                    .select(Objects::nonNull));
        }
        if (grammar._taggedValues() != null && grammar._taggedValues().notEmpty())
        {
            result._taggedValues(grammar._taggedValues()
                    .collect(tv -> AnnotationCompiler.resolveTaggedValue(tv, imports, model, context))
                    .select(Objects::nonNull));
        }

        // Compile expression sequence
        result._expressionSequence(grammar._expressionSequence()
                .collect(vs -> ValueSpecificationCompiler.compile(vs, imports, model, context)));

        context.enrichCurrentErrors("function '" + PackageableFunctionCompiler.fullPath(grammar) + "'");
        return result;
    }

    /**
     * Third pass: resolve inferred function references in the expression sequence,
     * then validate return type and multiplicity compatibility.
     */
    public static UserDefinedFunction thirdPass(meta.pure.metamodel.function.UserDefinedFunction result, MetadataAccess model, CompilationContext context)
    {
        // Set in-scope type/multiplicity params so inner FunctionExpressions
        // can validate that unresolved type params come from this enclosing scope.
        context.compilerContextExtensions(PureLanguageCompilerContext.class).setScopeTypeParamNames(result._typeParameters()
                .collect(TypeParameter::_name).toSet());
        context.compilerContextExtensions(PureLanguageCompilerContext.class).setScopeMultiplicityParamNames(result._multiplicityParameters()
                .collect(MultiplicityParameter::_name).toSet());

        FunctionDefinitionResolver.resolveAndValidate(
                result,
                "function",
                result._name(),
                result._returnGenericType(),
                result._returnMultiplicity(),
                model,
                context);

        // Clear scope params
        context.compilerContextExtensions(PureLanguageCompilerContext.class).setScopeTypeParamNames(Sets.mutable.empty());
        context.compilerContextExtensions(PureLanguageCompilerContext.class).setScopeMultiplicityParamNames(Sets.mutable.empty());

        return result;
    }
}

