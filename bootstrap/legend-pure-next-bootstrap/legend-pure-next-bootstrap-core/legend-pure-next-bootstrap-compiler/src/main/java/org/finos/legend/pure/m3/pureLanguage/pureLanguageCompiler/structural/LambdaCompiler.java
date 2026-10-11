// Copyright 2026 Goldman Sachs
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

import platform.pure.metamodel.function.LambdaFunctionImpl;
import platform.pure.metamodel.valuespecification.VariableExpression;
import platform.pure.metamodel.valuespecification.VariableExpressionImpl;
import platform.pure.protocol.grammar.function.LambdaFunction;
import org.eclipse.collections.api.list.MutableList;
import org.finos.legend.pure.m3.module.MetadataAccess;
import org.finos.legend.pure.m3.module.sourceModule.topLevel.CompilationContext;
import platform.pure.metamodel.type.generics.CompilerNotSetGenericType;
import platform.pure.metamodel.type.generics.CompilerNotSetGenericTypeImpl;
import platform.pure.metamodel.multiplicity.CompilerNotSetMultiplicity;
import platform.pure.metamodel.multiplicity.CompilerNotSetMultiplicityImpl;
import org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._VariableExpression;

/**
 * Compiles grammar-level {@link LambdaFunction}
 * into metamodel-level {@link platform.pure.metamodel.function.LambdaFunction}.
 */
public final class LambdaCompiler
{
    private LambdaCompiler()
    {
    }

    /**
     * Compile a grammar-level LambdaFunction into a metamodel LambdaFunctionImpl.
     * Compiles the lambda's parameters (VariableExpression) and expression sequence.
     */
    public static LambdaFunctionImpl compile(LambdaFunction grammarLambda, MutableList<String> imports, MetadataAccess model, CompilationContext context)
    {
        LambdaFunctionImpl result = new LambdaFunctionImpl();
        // Pure ^LambdaFunction(...) auto-derives _classifierGenericType. Mirror
        // by setting it here so nested lambdas in expression bodies have a
        // populated classifier chain (matching Pure side encoding shape).
        result._classifierGenericType(org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._GenericType
                .buildInferredGenericType((platform.pure.metamodel.type.Type) model.getElement("platform::pure::metamodel::function::LambdaFunction"), model));
        if (grammarLambda._p_sourceInformation() != null)
        {
            result._sourceInformation(SourceInformationCompiler.compile(grammarLambda._p_sourceInformation(), context.getSourceId(), model));
        }

        MutableList<? extends platform.pure.protocol.grammar.valuespecification.VariableExpression> grammarParams = grammarLambda._parameters();
        if (grammarParams != null && grammarParams.notEmpty())
        {
            result._parameters(grammarParams.collect(gp -> compileParameter(gp, imports, model, context)));
        }

        MutableList<? extends platform.pure.protocol.grammar.valuespecification.ValueSpecification> grammarExprs = grammarLambda._expressionSequence();
        if (grammarExprs != null && grammarExprs.notEmpty())
        {
            result._expressionSequence(grammarExprs.collect(ge -> ValueSpecificationCompiler.compile(ge, imports, model, context)));
        }

        return result;
    }

    private static VariableExpression compileParameter(platform.pure.protocol.grammar.valuespecification.VariableExpression gp, MutableList<String> imports, MetadataAccess model, CompilationContext context)
    {
        VariableExpressionImpl cp = _VariableExpression.newVariableExpression(model)._name(gp._name() != null ? gp._name() : "");
        if (gp._p_sourceInformation() != null)
        {
            cp._sourceInformation(SourceInformationCompiler.compile(gp._p_sourceInformation(), context.getSourceId(), model));
        }
        cp._genericType(gp._genericType() != null
                ? GenericTypeCompiler.compile(gp._genericType(), imports, model, context)
                : new CompilerNotSetGenericTypeImpl());
        cp._multiplicity(gp._multiplicity() != null
                ? MultiplicityCompiler.compile(gp._multiplicity(), model)
                : new CompilerNotSetMultiplicityImpl());
        return cp;
    }
}
