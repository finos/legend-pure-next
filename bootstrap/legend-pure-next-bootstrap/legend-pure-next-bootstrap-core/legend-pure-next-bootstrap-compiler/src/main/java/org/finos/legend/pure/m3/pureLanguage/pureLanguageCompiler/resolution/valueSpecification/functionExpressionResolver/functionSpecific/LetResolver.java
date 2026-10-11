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

package org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.resolution.valueSpecification.functionExpressionResolver.functionSpecific;

import platform.pure.metamodel.function.PackageableFunction;
import platform.pure.metamodel.valuespecification.AtomicValue;
import platform.pure.metamodel.valuespecification.FunctionExpression;
import platform.pure.metamodel.valuespecification.ValueSpecification;
import org.finos.legend.pure.m3.module.MetadataAccess;
import org.finos.legend.pure.m3.module.sourceModule.topLevel.CompilationContext;
import org.finos.legend.pure.m3.module.sourceModule.topLevel.CompilationError;
import org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.PureLanguageCompilerContext;
import org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._VariableExpression;

public class LetResolver
{
    /**
     * If the expression is a letFunction call, register the variable
     * into the current scope so subsequent expressions can reference it.
     * <p>
     * The variable's type and multiplicity come from the VALUE expression
     * (second parameter), not the letFunction's return type. This is
     * important because the return type T[m] depends on resolved bindings
     * which may be empty after snapshot rollback, while the value
     * expression has already been resolved to a concrete type.
     */
    public static void registerLetVariable(ValueSpecification vs, MetadataAccess model, CompilationContext context)
    {
        if (vs instanceof FunctionExpression fe
                && fe._func() != null
                && fe._func() instanceof PackageableFunction pf
                && "letFunction".equals(pf._functionName())
                && fe._parametersValues() != null
                && fe._parametersValues().size() >= 2
                && fe._parametersValues().getFirst() instanceof AtomicValue av
                && av._value() instanceof String varName)
        {
            if (context.compilerContextExtensions(PureLanguageCompilerContext.class).resolveVariable(varName) != null)
            {
                context.addError(new CompilationError(
                        "Variable '" + varName + "' is already defined",
                        fe._sourceInformation()));
            }
            // Use the value expression's (param[1]) type, which is the
            // concrete resolved type of the assigned value.
            ValueSpecification valueExpr = fe._parametersValues().get(1);
            context.compilerContextExtensions(PureLanguageCompilerContext.class).addToCurrentScope(
                    _VariableExpression.newVariableExpression(model)
                            ._name(varName)
                            ._genericType(valueExpr._genericType())
                            ._multiplicity(valueExpr._multiplicity()));
        }
    }
}
