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

package org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.resolution.valueSpecification;

import platform.pure.metamodel.type.generics.GenericType;
import platform.pure.metamodel.valuespecification.VariableExpression;
import platform.pure.metamodel.valuespecification.VariableExpressionImpl;
import org.finos.legend.pure.m3.module.MetadataAccess;
import org.finos.legend.pure.m3.module.sourceModule.topLevel.CompilationContext;
import org.finos.legend.pure.m3.module.sourceModule.topLevel.CompilationError;
import platform.pure.metamodel.type.generics.CompilerNotSetGenericType;
import platform.pure.metamodel.type.generics.CompilerNotSetGenericTypeImpl;
import org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.PureLanguageCompilerContext;
import org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._GenericType;
import org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._Multiplicity;

public class VariableExpressionResolver
{
    public static VariableExpression resolveVariableExpression(VariableExpression varExpr, MetadataAccess model, CompilationContext context)
    {
        if (varExpr._genericType() == null || varExpr._genericType() instanceof CompilerNotSetGenericType)
        {
            PureLanguageCompilerContext plctx = context.compilerContextExtensions(PureLanguageCompilerContext.class);
            // Parent-reference variable: `~` is the enclosing `^X(...)`, `~.~` the
            // one out, etc. Its type is the construction's GenericType at the
            // corresponding depth on the resolver's construction-type stack.
            int tildeDepth = PureLanguageCompilerContext.parentReferenceTildeCount(varExpr._name());
            if (tildeDepth > 0)
            {
                GenericType ctorGT = plctx.lookupParentReference(tildeDepth - 1);
                if (ctorGT != null)
                {
                    return ((VariableExpressionImpl) varExpr._copy())
                            ._genericType(_GenericType.asInferred(ctorGT, model))
                            ._multiplicity((platform.pure.metamodel.multiplicity.Multiplicity)
                                    model.getElement("platform::pure::metamodel::multiplicity::InferredPureOne"));
                }
                context.addError(new CompilationError(
                        "Parent reference '" + varExpr._name() + "' is out of bounds: only "
                                + plctx.constructionDepth()
                                + " enclosing `^X(...)` construction(s) are visible here.",
                        varExpr._sourceInformation()));
                return varExpr;
            }
            VariableExpression match = plctx.resolveVariable(varExpr._name());
            if (match != null)
            {
                return ((VariableExpressionImpl) varExpr._copy())
                        ._genericType(_GenericType.asInferred(match._genericType(), model))
                        ._multiplicity(_Multiplicity.asInferred(match._multiplicity(), model));
            }
            else
            {
                context.addError(new CompilationError("The variable '" + varExpr._name() + "' is unknown!", varExpr._sourceInformation()));
            }
        }
        return varExpr;
    }
}
