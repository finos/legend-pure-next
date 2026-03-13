package org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.resolution.valueSpecification;


import meta.pure.metamodel.Inferred;
import meta.pure.metamodel.function.LambdaFunction;
import meta.pure.metamodel.valuespecification.AtomicValue;
import meta.pure.metamodel.valuespecification.Collection;
import meta.pure.metamodel.valuespecification.CollectionImpl;
import meta.pure.metamodel.valuespecification.FunctionExpression;
import meta.pure.metamodel.valuespecification.GenericTypeAndMultiplicityHolder;
import meta.pure.metamodel.valuespecification.ValueSpecification;
import meta.pure.metamodel.valuespecification.VariableExpression;
import meta.pure.metamodel.valuespecification.VariableExpressionImpl;
import org.eclipse.collections.api.list.MutableList;
import org.finos.legend.pure.m3.module.localModule.topLevel.CompilationContext;
import org.finos.legend.pure.m3.module.MetadataAccess;
import org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._GenericType;
import org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._Multiplicity;
import org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.resolution.valueSpecification.functionExpressionResolver.DotApplicationResolver;

import static org.finos.legend.pure.m3.module.localModule.topLevel.CompilationContext.lazy;
import static org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.resolution.valueSpecification.AtomicValueResolver.resolveAtomicValue;
import static org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.resolution.valueSpecification.CollectionResolver.resolveCollection;
import static org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.resolution.valueSpecification.GenericTypeAndMultiplicityHolderResolver.resolveGenericTypeAndMultiplicityHolder;
import static org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.resolution.valueSpecification.VariableExpressionResolver.resolveVariableExpression;
import static org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.resolution.valueSpecification.functionExpressionResolver.FunctionApplicationResolver.resetResolutionForFunctionExpression;
import static org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.resolution.valueSpecification.functionExpressionResolver.FunctionExpressionResolver.resolveFunctionExpression;

/**
 * Third-pass resolution for metamodel-level {@link ValueSpecification}:
 * variable types from scope, function references from the function index, etc.
 */
public final class ValueSpecificationResolver
{
    private ValueSpecificationResolver()
    {
    }

    /**
     * Resolve metamodel-level value specifications: variable types from scope,
     * function references from the function index, etc.
     *
     * @return the resolved value specification, which may be a replacement node
     *         (e.g. when automap rewrites a DotApplication into a FunctionApplication)
     */
    public static ValueSpecification resolve(ValueSpecification vs, MetadataAccess model, CompilationContext context)
    {
        context.debug("VSR.resolve: %s%s%s gt=%s mul=%s",
                vs.getClass().getSimpleName(),
                vs instanceof FunctionExpression fe ? " fname=" + fe._functionName() : "",
                vs instanceof AtomicValue av ? " value=" + (av._value() != null ? av._value() : "null") : "",
                lazy(() -> _GenericType.print(vs._genericType())), lazy(() -> _Multiplicity.print(vs._multiplicity())));
        context.debugDepthInc();
        try
        {
            ValueSpecification result = switch (vs)
            {
                case VariableExpression varExpr -> resolveVariableExpression(varExpr, model, context);
                case FunctionExpression fe -> resolveFunctionExpression(fe, model, context);
                case Collection col -> resolveCollection(col, model, context);
                case AtomicValue av -> resolveAtomicValue(av, model, context);
                case GenericTypeAndMultiplicityHolder gm -> resolveGenericTypeAndMultiplicityHolder(gm, model, context);
                default -> throw new UnsupportedOperationException("Unsupported ValueSpecification type: " + vs.getClass().getName());
            };
            String status = (result._genericType() != null && _GenericType.isConcrete(result._genericType())) ? "" : " (UNRESOLVED)";
            context.debug("VSR.result: %s gt=%s mul=%s%s",
                    result.getClass().getSimpleName(),
                    lazy(() -> _GenericType.print(result._genericType())), lazy(() -> _Multiplicity.print(result._multiplicity())),
                    status);
            return result;
        }
        finally
        {
            context.debugDepthDec();
        }
    }

    /**
     * Reset third-pass resolution state on a value specification tree.
     * Only clears inferred values (instances of {@link Inferred}),
     * preserving grammar-declared types so that a different function
     * candidate can be tried cleanly.
     * <p>
     * When an automap {@code FunctionApplication map(receiver, {v_automap | ...})}
     * is encountered, it is reverted to the original {@code DotApplication}
     * extracted from the lambda body.
     *
     * @return the same node, or the reverted DotApplication for automaps
     */
    public static ValueSpecification resetResolution(ValueSpecification vs, CompilationContext context)
    {
        return switch (vs)
        {
            case FunctionExpression fe ->
            {
                // Detect automap: FunctionApplication with map + v_automap lambda
                if (fe instanceof meta.pure.metamodel.valuespecification.FunctionApplication
                        && "map".equals(fe._functionName())
                        && DotApplicationResolver.isAutomap(fe))
                {
                    // Revert to the original DotApplication:
                    // reconstruct from receiver (first param) + access name (from lambda body)
                    ValueSpecification receiver = fe._parametersValues().getFirst();
                    resetResolution(receiver, context);

                    AtomicValue lambdaAV = (AtomicValue) fe._parametersValues().get(1);
                    LambdaFunction lambda = (LambdaFunction) lambdaAV._value();
                    FunctionExpression dotBody = (FunctionExpression) lambda._expressionSequence().getFirst();

                    meta.pure.metamodel.valuespecification.DotApplicationImpl originalDot =
                            new meta.pure.metamodel.valuespecification.DotApplicationImpl();
                    originalDot._functionName(dotBody._functionName());
                    originalDot._parametersValues(org.eclipse.collections.impl.factory.Lists.mutable.with(receiver));
                    originalDot._sourceInformation(fe._sourceInformation());
                    yield originalDot;
                }

                resetResolutionForFunctionExpression(fe, context);
                yield fe;
            }
            case AtomicValue av ->
            {
                // Reset lambda bodies and inferred param types
                if (av._value() instanceof LambdaFunction lambda)
                {
                    if (lambda._parameters() != null)
                    {
                        lambda._parameters().forEach(p ->
                        {
                            if (p._genericType() instanceof Inferred)
                            {
                                ((VariableExpressionImpl) p)._genericType(null);
                            }
                            if (p._multiplicity() instanceof Inferred)
                            {
                                ((VariableExpressionImpl) p)._multiplicity(null);
                            }
                        });
                    }
                    if (lambda._expressionSequence() != null)
                    {
                        MutableList<ValueSpecification> exprSeq =
                                (MutableList<ValueSpecification>) lambda._expressionSequence();
                        for (int i = 0; i < exprSeq.size(); i++)
                        {
                            ValueSpecification replaced = resetResolution(exprSeq.get(i), context);
                            if (replaced != exprSeq.get(i))
                            {
                                exprSeq.set(i, replaced);
                            }
                        }
                    }
                }
                yield av;
            }
            case meta.pure.metamodel.valuespecification.Collection col ->
            {
                if (col._genericType() instanceof Inferred)
                {
                    ((CollectionImpl) col)._genericType(null);
                }
                if (col._values() != null)
                {
                    MutableList<ValueSpecification> values = col._values();
                    for (int i = 0; i < values.size(); i++)
                    {
                        ValueSpecification replaced = resetResolution(values.get(i), context);
                        if (replaced != values.get(i))
                        {
                            values.set(i, replaced);
                        }
                    }
                }
                yield col;
            }
            default -> {
                if (vs._genericType() instanceof Inferred)
                {
                    vs._genericType(null);
                }
                if (vs._multiplicity() instanceof Inferred)
                {
                    vs._multiplicity(null);
                }
                yield vs;
            }
        };
    }
}
