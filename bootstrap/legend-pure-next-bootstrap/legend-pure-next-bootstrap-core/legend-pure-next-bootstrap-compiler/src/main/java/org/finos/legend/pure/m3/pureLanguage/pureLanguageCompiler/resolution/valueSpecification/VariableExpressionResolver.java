package org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.resolution.valueSpecification;

import meta.pure.metamodel.type.generics.GenericType;
import meta.pure.metamodel.valuespecification.VariableExpression;
import meta.pure.metamodel.valuespecification.VariableExpressionImpl;
import org.finos.legend.pure.m3.module.MetadataAccess;
import org.finos.legend.pure.m3.module.localModule.topLevel.CompilationContext;
import org.finos.legend.pure.m3.module.localModule.topLevel.CompilationError;
import meta.pure.metamodel.type.generics.CompilerNotSetGenericType;
import meta.pure.metamodel.type.generics.CompilerNotSetGenericTypeImpl;
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
                            ._multiplicity((meta.pure.metamodel.multiplicity.Multiplicity)
                                    model.getElement("meta::pure::metamodel::multiplicity::InferredPureOne"));
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
