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
import meta.pure.metamodel.valuespecification.VariableExpression;
import meta.pure.metamodel.valuespecification.VariableExpressionImpl;
import org.eclipse.collections.api.factory.Lists;
import org.eclipse.collections.api.list.MutableList;
import org.finos.legend.pure.m3.module.MetadataAccess;
import org.finos.legend.pure.m3.module.localModule.topLevel.CompilationContext;
import org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.PureLanguageCompilerContext;
import org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._GenericType;
import org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._VariableExpression;
import org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.resolution.FunctionDefinitionResolver;
import org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.structural.AnnotationCompiler;
import org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.structural.ConstraintCompiler;
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

    public static UserDefinedFunction firstPass(meta.pure.protocol.grammar.function.UserDefinedFunction grammar, MetadataAccess model)
    {
        return new UserDefinedFunctionImpl()
                ._name(grammar._name())
                ._functionName(grammar._functionName());
    }

    public static UserDefinedFunction secondPass(UserDefinedFunctionImpl result, meta.pure.protocol.grammar.function.UserDefinedFunction grammar, MutableList<String> imports, MetadataAccess model, CompilationContext context)
    {
        result = (UserDefinedFunctionImpl) PackageableFunctionCompiler.compileShared(result, grammar, imports, model, context);

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

        // Create constraint shells (expression sequences resolved in third pass)
        if (grammar._preConstraints() != null && grammar._preConstraints().notEmpty())
        {
            result._preConstraints(grammar._preConstraints().collect(gc -> ConstraintCompiler.compileShell(gc, model)));
        }
        if (grammar._postConstraints() != null && grammar._postConstraints().notEmpty())
        {
            result._postConstraints(grammar._postConstraints().collect(gc -> ConstraintCompiler.compileShell(gc, model)));
        }

        context.enrichCurrentErrors("function '" + PackageableFunctionCompiler.fullPath(grammar) + "'");
        return result;
    }

    /**
     * Third pass: resolve inferred function references in the expression sequence,
     * then validate return type and multiplicity compatibility.
     */
    public static UserDefinedFunction thirdPass(meta.pure.metamodel.function.UserDefinedFunction result,
                                                   meta.pure.protocol.grammar.function.UserDefinedFunction grammar,
                                                   MutableList<String> imports, MetadataAccess model, CompilationContext context)
    {
        // Set in-scope type/multiplicity params so inner FunctionExpressions
        // can validate that unresolved type params come from this enclosing scope.
        context.compilerContextExtensions(PureLanguageCompilerContext.class).setEnclosingOwner(result);

        FunctionDefinitionResolver.resolveAndValidate(
                result,
                "function",
                result._name(),
                result._returnGenericType(),
                result._returnMultiplicity(),
                model,
                context);

        // Resolve pre-constraints (parameters are the function parameters)
        resolveFunctionConstraints(result, grammar, imports, model, context);

        // Clear scope params
        context.compilerContextExtensions(PureLanguageCompilerContext.class).clearEnclosingOwner();

        return result;
    }

    private static void resolveFunctionConstraints(meta.pure.metamodel.function.UserDefinedFunction result,
                                                    meta.pure.protocol.grammar.function.UserDefinedFunction grammar,
                                                    MutableList<String> imports, MetadataAccess model, CompilationContext context)
    {
        MutableList<VariableExpression> funcParams = result._parameters();

        // Pre-constraints: lambda parameters are the function's parameters
        if (result._preConstraints() != null && result._preConstraints().notEmpty()
                && grammar._preConstraints() != null && grammar._preConstraints().notEmpty())
        {
            context.compilerContextExtensions(PureLanguageCompilerContext.class).pushScope(funcParams);
            try
            {
                resolveFunctionConstraintList(result._preConstraints(), grammar._preConstraints(), funcParams, imports, model, context);
            }
            finally
            {
                context.compilerContextExtensions(PureLanguageCompilerContext.class).popScope();
            }
        }

        // Post-constraints: lambda parameters include the function's parameters plus $return
        if (result._postConstraints() != null && result._postConstraints().notEmpty()
                && grammar._postConstraints() != null && grammar._postConstraints().notEmpty())
        {
            VariableExpressionImpl returnVar = _VariableExpression.newVariableExpression(model)
                    ._name("return")
                    ._genericType(result._returnGenericType())
                    ._multiplicity(result._returnMultiplicity());

            MutableList<VariableExpression> allParams = Lists.mutable.<VariableExpression>withAll(funcParams);
            allParams.add(returnVar);

            context.compilerContextExtensions(PureLanguageCompilerContext.class).pushScope(allParams);
            try
            {
                resolveFunctionConstraintList(result._postConstraints(), grammar._postConstraints(), allParams, imports, model, context);
            }
            finally
            {
                context.compilerContextExtensions(PureLanguageCompilerContext.class).popScope();
            }
        }
    }

    private static void resolveFunctionConstraintList(
            MutableList<meta.pure.metamodel.constraint.Constraint> compiledConstraints,
            MutableList<? extends meta.pure.protocol.grammar.constraint.Constraint> grammarConstraints,
            MutableList<VariableExpression> params,
            MutableList<String> imports, MetadataAccess model, CompilationContext context)
    {
        compiledConstraints.zip(grammarConstraints).forEach(pair ->
        {
            meta.pure.metamodel.constraint.Constraint compiled = pair.getOne();
            meta.pure.protocol.grammar.constraint.Constraint grammarC = pair.getTwo();

            if (grammarC._functionDefinition() instanceof meta.pure.protocol.grammar.function.LambdaFunction lambdaFunc)
            {
                meta.pure.metamodel.function.LambdaFunctionImpl lambda =
                        org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.structural.LambdaCompiler.compile(lambdaFunc, imports, model, context);
                lambda._parameters(Lists.mutable.<VariableExpression>withAll(params));
                lambda._expressionSequence(FunctionDefinitionResolver.resolveExpressionSequence(lambda._expressionSequence(), model, context));
                lambda._classifierGenericType((meta.pure.metamodel.type.generics.GenericTypeValue)
                        org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._Lambda.getLambdaClassifierGenericType(model, lambda));
                compiled._functionDefinition(lambda);
            }

            if (grammarC._messageFunction() instanceof meta.pure.protocol.grammar.function.LambdaFunction msgFunc)
            {
                meta.pure.metamodel.function.LambdaFunctionImpl msgLambda =
                        org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.structural.LambdaCompiler.compile(msgFunc, imports, model, context);
                msgLambda._parameters(Lists.mutable.<VariableExpression>withAll(params));
                msgLambda._expressionSequence(FunctionDefinitionResolver.resolveExpressionSequence(msgLambda._expressionSequence(), model, context));
                msgLambda._classifierGenericType((meta.pure.metamodel.type.generics.GenericTypeValue)
                        org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._Lambda.getLambdaClassifierGenericType(model, msgLambda));
                compiled._messageFunction(msgLambda);
            }
        });
    }
}

