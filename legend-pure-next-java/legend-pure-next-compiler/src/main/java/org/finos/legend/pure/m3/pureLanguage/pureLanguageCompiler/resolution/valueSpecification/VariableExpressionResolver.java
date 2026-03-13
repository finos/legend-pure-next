package org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.resolution.valueSpecification;

import meta.pure.metamodel.valuespecification.VariableExpression;
import meta.pure.metamodel.valuespecification.VariableExpressionImpl;
import org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.PureLanguageCompilerContext;
import org.finos.legend.pure.m3.module.localModule.topLevel.CompilationContext;
import org.finos.legend.pure.m3.module.localModule.topLevel.CompilationError;
import org.finos.legend.pure.m3.module.MetadataAccess;
import org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._GenericType;
import org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._Multiplicity;

public class VariableExpressionResolver
{
    public static VariableExpression resolveVariableExpression(VariableExpression varExpr, MetadataAccess model, CompilationContext context)
    {
        if (varExpr._genericType() == null)
        {
            VariableExpression match = context.compilerContextExtensions(PureLanguageCompilerContext.class).resolveVariable(varExpr._name());
            if (match != null)
            {
                ((VariableExpressionImpl) varExpr)
                        ._genericType(_GenericType.asInferred(match._genericType()))
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
