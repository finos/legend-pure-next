package org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.structural;

import meta.pure.metamodel.constraint.Constraint;
import meta.pure.metamodel.constraint.ConstraintImpl;
import meta.pure.metamodel.function.LambdaFunctionImpl;
import meta.pure.metamodel.valuespecification.VariableExpression;
import meta.pure.metamodel.valuespecification.VariableExpressionImpl;
import meta.pure.protocol.grammar.function.LambdaFunction;
import org.eclipse.collections.api.factory.Lists;
import org.eclipse.collections.api.list.MutableList;
import org.finos.legend.pure.m3.module.localModule.topLevel.CompilationContext;
import org.finos.legend.pure.m3.module.MetadataAccess;
import org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.resolution.FunctionDefinitionResolver;

/**
 * Shared compilation utilities for constraints on types (Class, PrimitiveType, etc.).
 */
public final class ConstraintCompiler
{
    private ConstraintCompiler()
    {
    }

    /**
     * Create a constraint shell with metadata (name, owner, etc.) but no expression sequences.
     * Expression sequences are compiled in the third pass when all functions are available.
     */
    public static Constraint compileShell(meta.pure.protocol.grammar.constraint.Constraint grammarConstraint)
    {
        ConstraintImpl c = new ConstraintImpl();
        if (grammarConstraint._name() != null)
        {
            c._name(grammarConstraint._name());
        }
        if (grammarConstraint._owner() != null)
        {
            c._owner(grammarConstraint._owner());
        }
        if (grammarConstraint._externalId() != null)
        {
            c._externalId(grammarConstraint._externalId());
        }
        if (grammarConstraint._enforcementLevel() != null)
        {
            c._enforcementLevel(grammarConstraint._enforcementLevel());
        }
        if (grammarConstraint._sourceInformation() != null)
        {
            c._sourceInformation(SourceInformationCompiler.compile(grammarConstraint._sourceInformation()));
        }
        return c;
    }

    /**
     * Compile and resolve expression sequences for constraints in the third pass.
     * Creates lambda functions with a $this parameter and resolves all expressions.
     */
    public static void resolveConstraints(
            MutableList<Constraint> compiledConstraints,
            MutableList<? extends meta.pure.protocol.grammar.constraint.Constraint> grammarConstraints,
            VariableExpressionImpl thisVar,
            MutableList<VariableExpression> extraParams,
            MutableList<String> imports, MetadataAccess model, CompilationContext context)
    {
        int size = Math.min(compiledConstraints.size(), grammarConstraints.size());
        for (int i = 0; i < size; i++)
        {
            Constraint compiled = compiledConstraints.get(i);
            meta.pure.protocol.grammar.constraint.Constraint grammarC = grammarConstraints.get(i);

            if (grammarC._functionDefinition() instanceof LambdaFunction lambdaFunc)
            {
                LambdaFunctionImpl lambda = LambdaCompiler.compile(lambdaFunc, imports, model, context);
                MutableList<VariableExpression> params = Lists.mutable.<VariableExpression>with(thisVar);
                if (extraParams != null)
                {
                    params.addAll(extraParams);
                }
                if (lambda._parameters() != null)
                {
                    params.addAll(lambda._parameters());
                }
                lambda._parameters(params);
                lambda._expressionSequence(FunctionDefinitionResolver.resolveExpressionSequence(lambda._expressionSequence(), model, context));
                compiled._functionDefinition(lambda);
            }

            if (grammarC._messageFunction() instanceof LambdaFunction msgFunc)
            {
                LambdaFunctionImpl msgLambda = LambdaCompiler.compile(msgFunc, imports, model, context);
                MutableList<VariableExpression> msgParams = Lists.mutable.<VariableExpression>with(thisVar);
                if (extraParams != null)
                {
                    msgParams.addAll(extraParams);
                }
                if (msgLambda._parameters() != null)
                {
                    msgParams.addAll(msgLambda._parameters());
                }
                msgLambda._parameters(msgParams);
                msgLambda._expressionSequence(FunctionDefinitionResolver.resolveExpressionSequence(msgLambda._expressionSequence(), model, context));
                compiled._messageFunction(msgLambda);
            }
        }
    }
}
