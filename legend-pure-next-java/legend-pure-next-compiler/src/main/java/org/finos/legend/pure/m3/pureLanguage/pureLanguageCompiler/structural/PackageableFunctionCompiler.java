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

import meta.pure.metamodel.function.NativeFunction;
import meta.pure.metamodel.function.PackageableFunction;
import meta.pure.metamodel.type.FunctionTypeImpl;
import meta.pure.metamodel.type.Type;
import meta.pure.metamodel.type.generics.ConcreteGenericTypeImpl;
import org.eclipse.collections.api.factory.Lists;
import org.eclipse.collections.api.list.MutableList;
import org.finos.legend.pure.m3.module.localModule.topLevel.CompilationContext;
import org.finos.legend.pure.m3.module.MetadataAccess;
import org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._Function;
import org.finos.legend.pure.next.parser.m3.helper._G_PackageableElement;

import java.util.Objects;

/**
 * Shared compilation logic for all {@link PackageableFunction} subtypes
 * (UserDefinedFunction, NativeFunction).
 */
public final class PackageableFunctionCompiler
{
    private PackageableFunctionCompiler()
    {
    }

    /**
     * Compile the common parts of a PackageableFunction: parameters,
     * return type, return multiplicity, type parameters, multiplicity parameters,
     * stereotypes, and tagged values.
     */
    public static void compileShared(
            PackageableFunction result,
            meta.pure.protocol.grammar.function.PackageableFunction grammar,
            MutableList<String> imports,
            MetadataAccess model,
            CompilationContext context)
    {
        result._typeParameters(grammar._typeParameters()
                        .collect(GenericTypeCompiler::compileTypeParameter)
                        .select(Objects::nonNull))
                ._multiplicityParameters(grammar._multiplicityParameters()
                        .collect(MultiplicityCompiler::compileMultiplicityParameter)
                        .select(Objects::nonNull))
                ._parameters(grammar._parameters()
                        .collect(p -> VariableCompiler.compileParameter(p, imports, model, context))
                        .select(Objects::nonNull));

        _Function.validateFunctionParameters(result._parameters(), context, SourceInformationCompiler.compile(grammar._sourceInformation()));

        result._returnGenericType(GenericTypeCompiler.compile(grammar._returnGenericType(), imports, model, context));
        result._returnMultiplicity(MultiplicityCompiler.compile(grammar._returnMultiplicity(), model));
        result._classifierGenericType(
                new ConcreteGenericTypeImpl()
                        ._rawType((Type) model.getElement(
                                result instanceof NativeFunction ? "meta::pure::metamodel::function::NativeFunction"
                                        : "meta::pure::metamodel::function::UserDefinedFunction"))
                        ._typeArguments(Lists.mutable.with(
                                new ConcreteGenericTypeImpl()._rawType(
                                        new FunctionTypeImpl()
                                                ._parameters(result._parameters())
                                                ._returnType(result._returnGenericType())
                                                ._returnMultiplicity(result._returnMultiplicity())))));
    }

    /**
     * Compute the fully-qualified path for a grammar PackageableFunction.
     */
    public static String fullPath(meta.pure.protocol.grammar.function.PackageableFunction grammar)
    {
        return _G_PackageableElement.fullPath(grammar);
    }
}

