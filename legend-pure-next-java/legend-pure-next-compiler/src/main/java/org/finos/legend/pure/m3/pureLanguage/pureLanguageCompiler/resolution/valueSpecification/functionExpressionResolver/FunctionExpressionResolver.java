package org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.resolution.valueSpecification.functionExpressionResolver;

import meta.pure.metamodel.multiplicity.Multiplicity;
import meta.pure.metamodel.type.FunctionType;
import meta.pure.metamodel.type.generics.GenericType;
import meta.pure.metamodel.valuespecification.DotApplication;
import meta.pure.metamodel.valuespecification.FunctionApplication;
import meta.pure.metamodel.valuespecification.FunctionExpression;
import meta.pure.metamodel.valuespecification.ValueSpecification;
import org.finos.legend.pure.m3.module.localModule.topLevel.CompilationContext;
import org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper.ParametersBinding;
import org.finos.legend.pure.m3.module.MetadataAccess;
import org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._Function;
import org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._FunctionExpression;
import org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._GenericType;
import org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._Multiplicity;
import org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.resolution.valueSpecification.functionExpressionResolver.functionSpecific.NewResolver;

import static org.finos.legend.pure.m3.module.localModule.topLevel.CompilationContext.lazy;
import static org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.resolution.valueSpecification.functionExpressionResolver.functionSpecific.LetResolver.registerLetVariable;

/**
 * Two-phase function resolution against call-site argument types.
 *
 * <p><b>Phase 1 — Candidate filtering:</b> Resolve non-lambda parameter values,
 * then filter candidates using known (non-lambda) argument types. Arguments with
 * unresolved types (e.g. lambdas) are skipped during filtering — they cannot
 * narrow the candidate set yet.</p>
 *
 * <p><b>Phase 2 — Type inference and selection:</b> For each remaining candidate,
 * resolve lambda parameters and bodies using the candidate's type bindings.
 * Then re-collect parameter bindings (now including lambda-derived types) and
 * re-validate the match. Pick the best candidate from those that still match.</p>
 */
public final class FunctionExpressionResolver
{
    private FunctionExpressionResolver()
    {
    }

    // ========================================================================
    // Entry point
    // ========================================================================

    /**
     * Resolve a function call expression to the best matching function.
     * Dispatches to {@link DotApplicationResolver#resolveDotApplication} for property/qualified-property
     * access, or {@link FunctionApplicationResolver#resolveFunctionApplication} for regular function calls.
     *
     * @return the resolved expression — the same {@code expr} when no tree rewrite
     * occurs, or a replacement {@code FunctionApplicationImpl} when an
     * automap rewrites a DotApplication into a {@code map(...)} call.
     */
    public static ValueSpecification resolveFunctionExpression(FunctionExpression expr, MetadataAccess model, CompilationContext context)
    {
        context.debug("resolveFunctionExpression: %s (%s)", expr._functionName(), expr.getClass().getSimpleName());
        context.debugDepthInc();
        try
        {
            if (expr instanceof DotApplication dotApplication && expr._parametersValues().notEmpty())
            {
                ValueSpecification dotResult = DotApplicationResolver.resolveDotApplication(dotApplication, model, context);
                // Enum value resolution returns an AtomicValue, not a FunctionExpression
                if (dotResult instanceof FunctionExpression fe)
                {
                    finalizeFunctionExpression(fe, model, context);
                }
                return dotResult;
            }
            else
            {
                FunctionExpression expression = expr._func(FunctionApplicationResolver.resolveFunctionApplication((FunctionApplication) expr, model, context));
                finalizeFunctionExpression(expression, model, context);
                return expression;
            }
        }
        finally
        {
            context.debugDepthDec();
        }
    }

    public static void finalizeFunctionExpression(FunctionExpression resolved, MetadataAccess model, CompilationContext context)
    {
        if (resolved._func() != null)
        {
            ParametersBinding bindings = _FunctionExpression.extractResolvedParametersBinding(resolved);
            FunctionType ft = _Function.getFunctionType(resolved._func(), model);
            GenericType returnGT = _GenericType.asInferred(_GenericType.makeAsConcreteAsPossible(ft._returnType(), bindings, model));
            Multiplicity returnMul = _Multiplicity.asInferred(_Multiplicity.makeAsConcreteAsPossible(ft._returnMultiplicity(), bindings), model);
            context.debug("finalize: %s func=%s gt=%s mul=%s bindings=%s", resolved._functionName(), lazy(() -> CompilationContext.debugFunc(resolved._func())), lazy(() -> _GenericType.print(returnGT)), lazy(() -> _Multiplicity.print(returnMul)), bindings);
            resolved._genericType(returnGT)
                    ._multiplicity(returnMul);

            // Validate required properties for new expressions
            if (resolved._functionName() != null && resolved._functionName().equals("new"))
            {
                NewResolver.validateNewRequiredProperties(resolved, model, context);
            }
            registerLetVariable(resolved, context);
        }
        else
        {
            resolved._genericType(null)
                    ._multiplicity(null);
        }
    }
}