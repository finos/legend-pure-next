package org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.structural;

import meta.pure.metamodel.function.LambdaFunctionImpl;
import meta.pure.metamodel.valuespecification.VariableExpression;
import meta.pure.metamodel.valuespecification.VariableExpressionImpl;
import meta.pure.protocol.grammar.function.LambdaFunction;
import org.eclipse.collections.api.list.MutableList;
import org.finos.legend.pure.m3.module.localModule.topLevel.CompilationContext;
import org.finos.legend.pure.m3.module.MetadataAccess;

/**
 * Compiles grammar-level {@link LambdaFunction}
 * into metamodel-level {@link meta.pure.metamodel.function.LambdaFunction}.
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

        MutableList<? extends meta.pure.protocol.grammar.valuespecification.VariableExpression> grammarParams = grammarLambda._parameters();
        if (grammarParams != null && grammarParams.notEmpty())
        {
            result._parameters(grammarParams.collect(gp -> compileParameter(gp, imports, model, context)));
        }

        MutableList<? extends meta.pure.protocol.grammar.valuespecification.ValueSpecification> grammarExprs = grammarLambda._expressionSequence();
        if (grammarExprs != null && grammarExprs.notEmpty())
        {
            result._expressionSequence(grammarExprs.collect(ge -> ValueSpecificationCompiler.compile(ge, imports, model, context)));
        }

        return result;
    }

    private static VariableExpression compileParameter(meta.pure.protocol.grammar.valuespecification.VariableExpression gp, MutableList<String> imports, MetadataAccess model, CompilationContext context)
    {
        VariableExpressionImpl cp = new VariableExpressionImpl()._name(gp._name());
        if (gp._sourceInformation() != null)
        {
            cp._sourceInformation(SourceInformationCompiler.compile(gp._sourceInformation()));
        }
        if (gp._genericType() != null)
        {
            cp._genericType(GenericTypeCompiler.compile(gp._genericType(), imports, model, context));
        }
        if (gp._multiplicity() != null)
        {
            cp._multiplicity(MultiplicityCompiler.compile(gp._multiplicity(), model));
        }
        return cp;
    }
}
