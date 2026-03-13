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

package org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.resolution;

import meta.pure.metamodel.SourceInformation;
import meta.pure.metamodel.function.FunctionDefinition;
import meta.pure.metamodel.multiplicity.Multiplicity;
import meta.pure.metamodel.type.generics.GenericType;
import meta.pure.metamodel.valuespecification.ValueSpecification;
import org.eclipse.collections.api.list.MutableList;
import org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.PureLanguageCompilerContext;
import org.finos.legend.pure.m3.module.localModule.topLevel.CompilationContext;
import org.finos.legend.pure.m3.module.localModule.topLevel.CompilationError;
import org.finos.legend.pure.m3.module.MetadataAccess;
import org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._GenericType;
import org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._Multiplicity;
import org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.resolution.valueSpecification.ValueSpecificationResolver;

/**
 * Pass-3 resolution helpers for {@link FunctionDefinition} instances
 * (UserDefinedFunction, QualifiedProperty, etc.).
 */
public class FunctionDefinitionResolver
{
    private FunctionDefinitionResolver()
    {
    }

    /**
     * Resolve expression sequence references and validate that the last
     * expression's type and multiplicity are compatible with the declared return type.
     */
    public static void resolveAndValidate(
            FunctionDefinition funcDef,
            String kind,
            String declaredName,
            GenericType declaredGenericType,
            Multiplicity declaredMultiplicity,
            MetadataAccess model,
            CompilationContext context)
    {
        context.debug("resolveAndValidate: %s '%s'", kind, declaredName);
        context.debugDepthInc();
        context.compilerContextExtensions(PureLanguageCompilerContext.class).pushScope(funcDef._parameters());
        try
        {
            // pushScope copies, so addToCurrentScope (from registerLetVariable) won't
            // mutate the original funcDef._parameters() list.
            funcDef._expressionSequence(resolveExpressionSequence(funcDef._expressionSequence(), model, context));

            // Validate return type and multiplicity of the last expression.
            // Skip if unresolved (null) — the real error was reported during resolution.
            ValueSpecification lastExpr = funcDef._expressionSequence().getLast();
            SourceInformation srcInfo = lastExpr._sourceInformation();
            validateReturnType(kind, declaredName, declaredGenericType, lastExpr._genericType(), srcInfo, model, context);
            validateReturnMultiplicity(kind, declaredName, declaredMultiplicity, lastExpr._multiplicity(), srcInfo, context);
        }
        finally
        {
            context.compilerContextExtensions(PureLanguageCompilerContext.class).popScope();
            context.debugDepthDec();
        }
    }

    /**
     * Resolve an expression sequence, replacing any nodes that are rewritten
     * (e.g. DotApplication → FunctionApplication via automap).
     * <p>
     * After each expression is resolved, let variables are registered into
     * scope and unresolved lambda types are reported as errors.
     */
    public static MutableList<ValueSpecification> resolveExpressionSequence(
            MutableList<? extends ValueSpecification> expressionSequence,
            MetadataAccess model,
            CompilationContext context)
    {
        return expressionSequence.collect(vs ->
            ValueSpecificationResolver.resolve(vs, model, context)
        );
    }

    private static void validateReturnType(
            String kind, String name, GenericType declaredGT, GenericType actualGT,
            SourceInformation srcInfo, MetadataAccess model, CompilationContext context)
    {
        if (actualGT != null && !_GenericType.isCompatible(declaredGT, actualGT, model))
        {
            context.addError(new CompilationError(
                    "Return type error in " + kind + " '" + name + "': "
                            + "expected " + _GenericType.print(declaredGT)
                            + ", got " + _GenericType.print(actualGT),
                    srcInfo));
        }
    }

    private static void validateReturnMultiplicity(
            String kind, String name, Multiplicity declaredMul, Multiplicity actualMul,
            SourceInformation srcInfo, CompilationContext context)
    {
        if (actualMul != null && !_Multiplicity.subsumes(declaredMul, actualMul))
        {
            context.addError(new CompilationError(
                    "Return multiplicity error in " + kind + " '" + name + "': "
                            + "expected " + _Multiplicity.print(declaredMul)
                            + ", got " + _Multiplicity.print(actualMul),
                    srcInfo));
        }
    }
}
