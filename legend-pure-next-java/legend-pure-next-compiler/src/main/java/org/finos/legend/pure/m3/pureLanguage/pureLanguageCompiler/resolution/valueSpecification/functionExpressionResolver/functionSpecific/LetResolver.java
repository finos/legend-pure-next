package org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.resolution.valueSpecification.functionExpressionResolver.functionSpecific;

import meta.pure.metamodel.function.PackageableFunction;
import meta.pure.metamodel.valuespecification.AtomicValue;
import meta.pure.metamodel.valuespecification.FunctionExpression;
import meta.pure.metamodel.valuespecification.ValueSpecification;
import meta.pure.metamodel.valuespecification.VariableExpressionImpl;
import org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.PureLanguageCompilerContext;
import org.finos.legend.pure.m3.module.localModule.topLevel.CompilationContext;
import org.finos.legend.pure.m3.module.localModule.topLevel.CompilationError;

public class LetResolver
{
    /**
     * If the expression is a letFunction call, register the variable
     * into the current scope so subsequent expressions can reference it.
     */
    public static void registerLetVariable(ValueSpecification vs, CompilationContext context)
    {
        if (vs instanceof FunctionExpression fe
                && fe._func() != null
                && fe._func() instanceof PackageableFunction pf
                && "letFunction".equals(pf._functionName())
                && fe._parametersValues() != null
                && !fe._parametersValues().isEmpty()
                && fe._parametersValues().getFirst() instanceof AtomicValue av
                && av._value() instanceof String varName)
        {
            if (context.compilerContextExtensions(PureLanguageCompilerContext.class).resolveVariable(varName) != null)
            {
                context.addError(new CompilationError(
                        "Variable '" + varName + "' is already defined",
                        fe._sourceInformation()));
            }
            context.compilerContextExtensions(PureLanguageCompilerContext.class).addToCurrentScope(
                    new VariableExpressionImpl()
                            ._name(varName)
                            ._genericType(fe._genericType())
                            ._multiplicity(fe._multiplicity()));
        }
    }
}
