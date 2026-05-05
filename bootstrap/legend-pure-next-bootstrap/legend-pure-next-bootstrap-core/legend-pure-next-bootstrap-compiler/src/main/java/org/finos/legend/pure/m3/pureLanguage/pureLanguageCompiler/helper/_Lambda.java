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

import meta.pure.metamodel.function.LambdaFunction;
import meta.pure.metamodel.type.generics.CompilerNotSetGenericType;
import meta.pure.metamodel.type.generics.CompilerNotSetGenericTypeImpl;
import meta.pure.metamodel.multiplicity.CompilerNotSetMultiplicity;
import meta.pure.metamodel.multiplicity.CompilerNotSetMultiplicityImpl;
import meta.pure.metamodel.type.FunctionType;
import meta.pure.metamodel.type.FunctionTypeImpl;
import meta.pure.metamodel.type.Type;
import meta.pure.metamodel.type.generics.GenericType;
import meta.pure.metamodel.type.generics.InferredGenericTypeImpl;
import meta.pure.metamodel.valuespecification.ValueSpecification;
import org.finos.legend.pure.m3.module.MetadataAccess;

/**
 * Helper methods for {@link LambdaFunction}.
 */
public final class _Lambda
{
    private _Lambda()
    {
        // static utility
    }

    /**
     * Build a {@link FunctionType} from a lambda's parameters and expression body.
     * <p>
     * The return type and multiplicity are inferred from the last expression
     * in the lambda's expression sequence.
     *
     * @param lambda the lambda function
     * @return a new FunctionType representing the lambda's signature
     */
    public static FunctionType buildFunctionType(LambdaFunction lambda, MetadataAccess model)
    {
        FunctionTypeImpl ft = _FunctionType.newFunctionType(model);
        if (lambda._parameters() != null)
        {
            ft._parameters(lambda._parameters());
        }
        if (lambda._expressionSequence() != null && lambda._expressionSequence().notEmpty())
        {
            ValueSpecification lastExpr = lambda._expressionSequence().getLast();
            ft._returnType(lastExpr._genericType() != null
                    ? lastExpr._genericType()
                    : new CompilerNotSetGenericTypeImpl());
            ft._returnMultiplicity(lastExpr._multiplicity() != null
                    ? lastExpr._multiplicity()
                    : new CompilerNotSetMultiplicityImpl());
        }
        return ft;
    }

    public static InferredGenericTypeImpl getLambdaClassifierGenericType(MetadataAccess model, LambdaFunction lambda)
    {
        return _GenericType.buildInferredGenericType((Type) model.getElement("meta::pure::metamodel::function::LambdaFunction"), model)
                ._typeArguments(org.eclipse.collections.impl.factory.Lists.mutable.with(
                        _GenericType.buildInferredGenericType(buildFunctionType(lambda, model), model)));
    }
}
