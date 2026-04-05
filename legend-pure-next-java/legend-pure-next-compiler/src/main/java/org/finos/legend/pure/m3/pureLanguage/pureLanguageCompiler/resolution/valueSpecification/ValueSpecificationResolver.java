package org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.resolution.valueSpecification;


import meta.pure.metamodel.valuespecification.AtomicValue;
import meta.pure.metamodel.valuespecification.Collection;
import meta.pure.metamodel.valuespecification.FunctionExpression;
import meta.pure.metamodel.valuespecification.GenericTypeAndMultiplicityHolder;
import meta.pure.metamodel.valuespecification.ValueSpecification;
import meta.pure.metamodel.valuespecification.VariableExpression;
import org.finos.legend.pure.m3.module.MetadataAccess;
import org.finos.legend.pure.m3.module.localModule.topLevel.CompilationContext;
import org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._GenericType;
import org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._Multiplicity;

import static org.finos.legend.pure.m3.module.localModule.topLevel.CompilationContext.lazy;
import static org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.resolution.valueSpecification.AtomicValueResolver.resolveAtomicValue;
import static org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.resolution.valueSpecification.CollectionResolver.resolveCollection;
import static org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.resolution.valueSpecification.GenericTypeAndMultiplicityHolderResolver.resolveGenericTypeAndMultiplicityHolder;
import static org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.resolution.valueSpecification.VariableExpressionResolver.resolveVariableExpression;
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
}
