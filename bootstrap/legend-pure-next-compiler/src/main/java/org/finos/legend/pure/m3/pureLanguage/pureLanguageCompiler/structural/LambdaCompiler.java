package org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.structural;

import meta.pure.metamodel.function.LambdaFunctionImpl;
import meta.pure.metamodel.valuespecification.VariableExpression;
import meta.pure.metamodel.valuespecification.VariableExpressionImpl;
import meta.pure.protocol.grammar.function.LambdaFunction;
import org.eclipse.collections.api.list.MutableList;
import org.finos.legend.pure.m3.module.MetadataAccess;
import org.finos.legend.pure.m3.module.localModule.topLevel.CompilationContext;
import meta.pure.metamodel.type.generics.CompilerNotSetGenericType;
import meta.pure.metamodel.type.generics.CompilerNotSetGenericTypeImpl;
import meta.pure.metamodel.multiplicity.CompilerNotSetMultiplicity;
import meta.pure.metamodel.multiplicity.CompilerNotSetMultiplicityImpl;
import org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._VariableExpression;

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
        if (grammarLambda._sourceInformation() != null)
        {
            result._sourceInformation(SourceInformationCompiler.compile(grammarLambda._sourceInformation(), context.getSourceId(), model));
        }

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
        VariableExpressionImpl cp = _VariableExpression.newVariableExpression(model)._name(gp._name() != null ? gp._name() : "");
        if (gp._sourceInformation() != null)
        {
            cp._sourceInformation(SourceInformationCompiler.compile(gp._sourceInformation(), model));
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
