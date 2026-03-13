package org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.resolution.valueSpecification.functionExpressionResolver;

import meta.pure.metamodel.SourceInformation;
import meta.pure.metamodel.function.Function;
import meta.pure.metamodel.function.LambdaFunction;
import meta.pure.metamodel.multiplicity.Multiplicity;
import meta.pure.metamodel.type.FunctionType;
import meta.pure.metamodel.type.Type;
import meta.pure.metamodel.type.generics.GenericType;
import meta.pure.metamodel.valuespecification.AtomicValue;
import meta.pure.metamodel.valuespecification.Collection;
import meta.pure.metamodel.valuespecification.CompilerGenericTypeAndMultiplicityHolder;
import meta.pure.metamodel.valuespecification.FunctionApplication;
import meta.pure.metamodel.valuespecification.FunctionExpression;
import meta.pure.metamodel.valuespecification.ValueSpecification;
import meta.pure.metamodel.valuespecification.VariableExpression;
import meta.pure.metamodel.valuespecification.VariableExpressionImpl;
import org.eclipse.collections.api.list.ListIterable;
import org.eclipse.collections.api.list.MutableList;
import org.eclipse.collections.api.map.MutableMap;
import org.eclipse.collections.api.set.MutableSet;
import org.eclipse.collections.impl.factory.Lists;
import org.eclipse.collections.impl.factory.Sets;
import org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.PureLanguageCompilerContext;
import org.finos.legend.pure.m3.module.localModule.topLevel.CompilationContext;
import org.finos.legend.pure.m3.module.localModule.topLevel.CompilationError;
import org.finos.legend.pure.m3.pureLanguage.metadata.CompositePureLanguageMetadata;
import org.finos.legend.pure.m3.pureLanguage.metadata.FunctionIndexEntry;
import org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper.ParametersBinding;
import org.finos.legend.pure.m3.module.MetadataAccess;
import org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._FunctionExpression;
import org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._GenericType;
import org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._Multiplicity;
import org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.resolution.valueSpecification.ValueSpecificationResolver;
import org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.resolution.valueSpecification.functionExpressionResolver.functionSpecific.RelationColumnResolver;
import org.finos.legend.pure.m3.pureLanguage.metadata.PureLanguageMetadata;

import java.util.Comparator;

import static org.finos.legend.pure.m3.module.localModule.topLevel.CompilationContext.lazy;

public class FunctionApplicationResolver
{
    /**
     * Resolve a FunctionApplication or ArrowFunction expression by looking up
     * function candidates by name and arity, then resolving args, collecting
     * bindings, resolving lambdas, and picking the best match.
     */
    public static Function resolveFunctionApplication(
            FunctionApplication expr,
            MetadataAccess model,
            CompilationContext context)
    {
        String functionName = expr._functionName();
        int paramCount = expr._parametersValues().size();

        // Find candidates by name and arity
        MutableList<FunctionIndexEntry> candidates = findCandidates(functionName, paramCount, model, context);

        if (candidates.isEmpty())
        {
            String message = "The function '" + functionName + "' with " + paramCount + " parameter(s) can't be found";
            message += buildFunctionSuggestions(functionName, paramCount, model, context);
            context.addError(new CompilationError(message, expr._sourceInformation()));
            return null;
        }
        else if (candidates.size() == 1)
        {
            // Single candidate — resolve directly, errors are legitimate
            FunctionIndexEntry entry = candidates.getFirst();
            resolveFunctionApplicationUsingTemplateFunctionForInference(expr, entry, model, context);
            Function func = expr._func();
            return func != null ? func : LazyPackageableFunction.create(entry, model);
        }
        else
        {
            // Multiple candidates (most-specific-first) — try each with rollback.
            MutableList<CompilationError> bestCandidateErrors = null;
            for (FunctionIndexEntry candidateEntry : candidates)
            {
                int errorCheckpoint = context.currentErrorCount();

                resolveFunctionApplicationUsingTemplateFunctionForInference(expr, candidateEntry, model, context);

                if (context.currentErrorCount() == errorCheckpoint)
                {
                    // Clean resolution — check for equally-specific ties
                    Comparator<FunctionIndexEntry> cmp = FunctionIndexEntry.mostSpecificFirst(model);
                    MutableList<FunctionIndexEntry> tied = candidates.select(c -> cmp.compare(candidateEntry, c) == 0);
                    if (tied.size() > 1)
                    {
                        String signatures = tied.collect(FunctionIndexEntry::signature).sortThis().makeString(", ");
                        SourceInformation srcInfo = expr._sourceInformation();
                        context.addError(new CompilationError("Ambiguous function call: multiple equally-specific matches found [" + signatures + "]", srcInfo));
                        return null;
                    }
                    return expr._func();
                }

                // Resolution produced errors — snapshot and roll back for next candidate
                MutableList<CompilationError> candidateErrors = context.snapshotErrorsFrom(errorCheckpoint);
                context.rollbackErrorsTo(errorCheckpoint);
                if (bestCandidateErrors == null)
                {
                    bestCandidateErrors = candidateErrors;
                }
                resetResolutionForFunctionExpression(expr, context);
            }
            // No candidate resolved cleanly — restore the best candidate's errors
            if (bestCandidateErrors != null)
            {
                context.addErrors(bestCandidateErrors);
            }
            return null;
        }
    }


    static class ParameterInfo
    {
        boolean resolved = false;
        MutableList<CompilationError> savedErrors;
        ValueSpecification newParam;
    }

    /**
     * Resolve args, collect type/multiplicity bindings, apply bindings to
     * lambdas, validate, and populate resolved parameters on the expression.
     */
    private static void resolveFunctionApplicationUsingTemplateFunctionForInference(
            FunctionExpression expr,
            FunctionIndexEntry entry,
            MetadataAccess model,
            CompilationContext context)
    {
        // Seed with reverse bindings from parent (resolvedTypeParams/resolvedMultiplicityParams).
        // These take priority over bindings collected from arg types.
        ParametersBinding bindings = _FunctionExpression.extractResolvedParametersBinding(expr);

        MutableList<ValueSpecification> paramValues = expr._parametersValues();
        MutableList<VariableExpression> funcParams = entry.functionType()._parameters();
        MutableSet<String> scopeTypeParams = context.compilerContextExtensions(PureLanguageCompilerContext.class).scopeTypeParamNames();
        MutableSet<String> scopeMulParams = context.compilerContextExtensions(PureLanguageCompilerContext.class).scopeMultiplicityParamNames();

        // Fixpoint loop: resolve parameters iteratively until no more progress
        context.debug("resolveFunctionApplicationUsingTemplateFunction: %s (%d args)", entry.fullPath(), paramValues.size());
        context.debugDepthInc();

        ParameterInfo[] parameterInfos = new ParameterInfo[paramValues.size()];
        boolean progress = true;
        int iteration = 0;
        while (progress)
        {
            iteration++;
            context.debug("--- fixpoint iteration %d ---", iteration);
            progress = false;
            for (int i = 0; i < paramValues.size(); i++)
            {
                ParameterInfo parameterInfo = parameterInfos[i] == null ? parameterInfos[i] = new ParameterInfo() : parameterInfos[i];
                if (!parameterInfo.resolved)
                {
                    ValueSpecification parameterValue = expr._parametersValues().get(i);
                    GenericType paramGT = funcParams.get(i)._genericType();
                    Multiplicity paramMul = funcParams.get(i)._multiplicity();
                    context.debug("parameterValue[%d] class=%s paramGT=%s bindings=%s", i, parameterValue.getClass().getSimpleName(), lazy(() -> _GenericType.print(paramGT)), bindings);
                    int checkpoint = context.currentErrorCount();
                    context.debugDepthInc();
                    ValueSpecification reprocessed = resolveParameterValue(parameterValue, paramGT, paramMul, bindings, scopeTypeParams, scopeMulParams, model, context);
                    parameterInfo.newParam = reprocessed;
                    if (isSuccessfullyProcessed(parameterValue, reprocessed, scopeTypeParams, scopeMulParams))
                    {
                        progress = true;
                        parameterInfo.resolved = true;
                        parameterInfo.savedErrors = null;
                        context.debug(parameterValue instanceof CompilerGenericTypeAndMultiplicityHolder ?
                                "=> RESOLVED (TypeHolder = compiler-managed, enriched later)" :
                                "=> RESOLVED gt=%s mul=%s", lazy(() -> _GenericType.print(parameterValue._genericType())), lazy(() -> _Multiplicity.print(parameterValue._multiplicity()))
                        );
                    }
                    else
                    {
                        parameterInfo.savedErrors = context.snapshotErrorsFrom(checkpoint);
                        context.rollbackErrorsTo(checkpoint);
                        ValueSpecificationResolver.resetResolution(parameterValue, context);
                        context.debug("=> NOT RESOLVED");
                    }
                    context.debugDepthDec();
                }
            }
        }
        context.debugDepthDec();

        // Resolve any remaining unresolved args.  If any arg still has no
        // concrete type after resolution, return early — the child will have
        // already reported its own error and validating the parent would
        // produce misleading cascading errors.
        for (int i = 0; i < paramValues.size(); i++)
        {
            if (!parameterInfos[i].resolved)
            {
                if (parameterInfos[i].savedErrors != null)
                {
                    context.debug("=> Pushed saved errors %d", parameterInfos[i].savedErrors.size());
                    context.addErrors(parameterInfos[i].savedErrors);
                }
                return;
            }
        }
        expr._parametersValues(Lists.mutable.with(parameterInfos).collect(x -> x.newParam))
                ._func(LazyPackageableFunction.create(entry, model));

        // Validate bindings, check arg types, and populate resolved parameters
        validateAndPopulate(expr, entry, bindings, model, context);
    }

    // ==================================================================================================
    // Validation and population of the FunctionExpression's genericType, multiplicity & type parameters
    // ==================================================================================================

    /**
     * Validate binding consistency, check arg types against params,
     * and populate resolved parameters on the expression.
     */
    private static void validateAndPopulate(
            FunctionExpression expr,
            FunctionIndexEntry entry,
            ParametersBinding bindings,
            MetadataAccess model,
            CompilationContext context)
    {
        ListIterable<? extends ValueSpecification> paramValues = expr._parametersValues();
        FunctionType functionType = entry.functionType();

        // Compiler-tracked enrichment: for relation column functions, the
        // TypeHolder's RelationType can't be inferred from bindings alone —
        // the column types come from the lambda return types.
        RelationColumnResolver.enrichRelationTypeHolderForMagicalFunctions(expr, functionType, paramValues, bindings, model, context);

        // A type/multiplicity parameter bound to
        // multiple values should be resolved to their common type/multiplicity
        bindings.unify(model);

        int checkpoint = context.currentErrorCount();
        // Validate arg types and multiplicities against param expectations
        validateParameterValuesToFunctionType(expr, functionType, bindings, model, context);

        if (context.currentErrorCount() == checkpoint)
        {
            _FunctionExpression.populateResolvedParameters(expr, bindings, model);
        }
    }

    private static void validateParameterValuesToFunctionType(FunctionExpression expr, FunctionType functionType, ParametersBinding bindings, MetadataAccess model, CompilationContext context)
    {
        ListIterable<? extends ValueSpecification> paramValues = expr._parametersValues();
        for (int i = 0; i < paramValues.size(); i++)
        {
            GenericType argGT = paramValues.get(i)._genericType();
            Multiplicity argMul = paramValues.get(i)._multiplicity();
            if (argGT != null && argMul != null)
            {
                GenericType expectedGT = _GenericType.makeAsConcreteAsPossible(functionType._parameters().get(i)._genericType(), bindings, model);
                Multiplicity expectedMul = _Multiplicity.makeAsConcreteAsPossible(functionType._parameters().get(i)._multiplicity(), bindings);
                // Reconcile stale Inferred types/multiplicities in the arg's type to match
                // the expected type from unified bindings (e.g. m=[2] -> m=[*])
                _GenericType.reconcileInferred(expectedGT, argGT, model);
                if (!_GenericType.isCompatible(expectedGT, argGT, model) || !_Multiplicity.subsumes(expectedMul, argMul))
                {
                    context.addError(new CompilationError("No matching function '" + expr._functionName() + "' found for argument types (" +
                            paramValues.collect(vs ->
                                    _GenericType.print(vs._genericType()) + _Multiplicity.print(vs._multiplicity())).makeString(", ") +
                            ")", expr._sourceInformation()));
                    break;
                }
            }
        }
    }

    // ========================================================================
    // Resolution actions
    // ========================================================================

    /**
     * Resolve a single argument: dispatch to the appropriate strategy
     * based on the argument type and current bindings.
     * Returns true if the argument was fully resolved.
     */
    private static ValueSpecification resolveParameterValue(
            ValueSpecification arg,
            GenericType paramGT,
            Multiplicity paramMul,
            ParametersBinding bindings,
            MutableSet<String> scopeTypeParams,
            MutableSet<String> scopeMulParams,
            MetadataAccess model,
            CompilationContext context)
    {
        int checkpoint = context.currentErrorCount();

        ValueSpecification processed = ValueSpecificationResolver.resolve(arg, model, context);

        if (isConcreteInContext(processed._genericType(), processed._multiplicity(), scopeTypeParams, scopeMulParams))
        {
            context.debug("resolveArg: CONCRETE (in context) gt=%s", lazy(() -> _GenericType.print(arg._genericType())));
            _GenericType.collectTypeParameterBindings(paramGT, processed._genericType(), bindings);
            _Multiplicity.collectMultiplicityParameterBindings(paramMul, processed._multiplicity(), bindings);
            return processed;
        }
        else if (processed instanceof AtomicValue av && av._value() instanceof LambdaFunction lambda)
        {
            context.rollbackErrorsTo(checkpoint);
            context.debug("resolveArg: LAMBDA");
            setMissingLambdaParameterInformation(lambda, paramGT, bindings, model);
            return finishProcessing(paramGT, paramMul, bindings, model, context, processed);
        }
        else if (processed instanceof FunctionApplication childExpr && childExpr._func() != null)
        {
            context.rollbackErrorsTo(checkpoint);
            context.debug("resolveArg: REVERSE_MATCH func=%s childGT=%s", childExpr._functionName(), lazy(() -> _GenericType.print(childExpr._genericType())));
            reverseMatch(childExpr, paramGT, paramMul, bindings, model, context);
            return finishProcessing(paramGT, paramMul, bindings, model, context, childExpr);
        }
        else if (processed instanceof Collection col && col._values() != null)
        {
            context.rollbackErrorsTo(checkpoint);
            GenericType resolvedParamGT = _GenericType.makeAsConcreteAsPossible(paramGT, bindings, model);
            Multiplicity resolvedParamMul = _Multiplicity.makeAsConcreteAsPossible(paramMul, bindings);
            // Enrich scope with type params still referenced in the resolved param type
            // (e.g., V,U from AggregateValue<Trade,V,U>) so that resolveArg's
            // isParamConcreteWithBindings gate passes for child elements
            MutableSet<String> enrichedScopeTypeParams = Sets.mutable.withAll(scopeTypeParams);
            _GenericType.collectReferencedTypeParameterNames(resolvedParamGT, enrichedScopeTypeParams);
            context.debug("resolveArg: COLLECTION (%d elements) resolvedParamGT=%s", col._values().size(), lazy(() -> _GenericType.print(resolvedParamGT)));
            context.debugDepthInc();
            for (ValueSpecification element : col._values())
            {
                context.debug("element: class=%s%s gt=%s",
                        element.getClass().getSimpleName(),
                        element instanceof FunctionExpression fe ? " fname=" + fe._functionName() : "",
                        lazy(() -> _GenericType.print(element._genericType())));
                // Each element resolves with isolated bindings so that inner function
                // type params (e.g., L from agg<K,L,M>) don't leak between elements.
                ParametersBinding elementBindings = bindings.copy();
                resolveParameterValue(element, resolvedParamGT, resolvedParamMul, elementBindings, enrichedScopeTypeParams, scopeMulParams, model, context);
                context.debug("element after: gt=%s", lazy(() -> _GenericType.print(element._genericType())));
            }
            context.debugDepthDec();

            return finishProcessing(paramGT, paramMul, bindings, model, context, processed);
        }
        context.debug("resolveArg: NO_MATCH class=%s gt=%s", arg.getClass().getSimpleName(), lazy(() -> _GenericType.print(arg._genericType())));
        return processed;
    }

    private static ValueSpecification finishProcessing(GenericType paramGT, Multiplicity paramMul, ParametersBinding bindings, MetadataAccess model, CompilationContext context, ValueSpecification vs)
    {
        context.debugDepthInc();
        ValueSpecificationResolver.resolve(vs, model, context);
        context.debugDepthDec();

        // Mark all resolved parameters as inferred.
        if (vs instanceof FunctionExpression childExpr && childExpr._resolvedTypeParameters() != null)
        {
            childExpr._resolvedTypeParameters().forEach(rtp ->
                    ((meta.pure.metamodel.type.generics.ResolvedTypeParameterImpl) rtp)
                            ._value(_GenericType.asInferred(rtp._value())));
        }
        if (vs instanceof FunctionExpression childExpr && childExpr._resolvedMultiplicityParameters() != null)
        {
            childExpr._resolvedMultiplicityParameters().forEach(rmp ->
                    ((meta.pure.metamodel.type.generics.ResolvedMultiplicityParameterImpl) rmp)
                            ._value(_Multiplicity.asInferred(rmp._value(), model)));
        }

        // Collect bindings from the re-resolved child type, mapped to the
        _GenericType.collectTypeParameterBindings(paramGT, vs._genericType(), bindings);
        _Multiplicity.collectMultiplicityParameterBindings(paramMul, vs._multiplicity(), bindings);

        return vs;
    }

    /**
     * Resolve a single lambda's parameter types and body using the function parameter's
     * expected type and the current type/multiplicity bindings.
     */
    private static void setMissingLambdaParameterInformation(
            LambdaFunction lambda,
            GenericType paramGT,
            ParametersBinding bindings,
            MetadataAccess model)
    {
        if (paramGT != null)
        {
            // Resolve the function parameter's Function<{FunctionType}> to concrete types using current bindings
            // e.g. Function<{T[1]->Boolean[1]}> with T=String => Function<{String[1]->Boolean[1]}>
            GenericType concreteGT = _GenericType.makeAsConcreteAsPossible(paramGT, bindings, model);
            if (concreteGT._typeArguments() != null && concreteGT._typeArguments().notEmpty())
            {
                GenericType innerGT = concreteGT._typeArguments().getFirst();
                if (innerGT._rawType() instanceof FunctionType ft)
                {
                    // Set (or update) lambda variable types from the resolved FunctionType parameters.
                    MutableList<VariableExpression> ftParams = ft._parameters();
                    MutableList<VariableExpression> lambdaParams = lambda._parameters();
                    for (int j = 0; j < lambdaParams.size() && j < ftParams.size(); j++)
                    {
                        VariableExpression lambdaParam = lambdaParams.get(j);
                        VariableExpression ftParam = ftParams.get(j);
                        GenericType resolvedType = _GenericType.makeAsConcreteAsPossible(ftParam._genericType(), bindings, model);
                        if (resolvedType != null && !_GenericType.isConcrete(lambdaParam._genericType()))
                        {
                            ((VariableExpressionImpl) lambdaParam)._genericType(_GenericType.asInferred(resolvedType));
                        }
                        Multiplicity resolvedMul = _Multiplicity.makeAsConcreteAsPossible(ftParam._multiplicity(), bindings);
                        if (resolvedMul != null && !_Multiplicity.isConcrete(lambdaParam._multiplicity()))
                        {
                            ((VariableExpressionImpl) lambdaParam)._multiplicity(_Multiplicity.asInferred(resolvedMul, model));
                        }
                    }
                }
            }
        }
    }

    /**
     * Reverse match: push parent bindings into a child FE's resolved
     * slots and re-resolve the child function application.
     */
    private static void reverseMatch(
            FunctionApplication childExpr,
            GenericType paramGT,
            Multiplicity paramMul,
            ParametersBinding bindings,
            MetadataAccess model,
            CompilationContext context)
    {
        GenericType argGT = childExpr._genericType();
        if (argGT != null)
        {
            ParametersBinding childBindings = new ParametersBinding();
            GenericType resolvedParamGT = _GenericType.makeAsConcreteAsPossible(paramGT, bindings, model);
            _GenericType.collectTypeParameterBindings(argGT, resolvedParamGT, childBindings);
            Multiplicity resolvedParamMul = _Multiplicity.makeAsConcreteAsPossible(paramMul, bindings);
            _Multiplicity.collectMultiplicityParameterBindings(childExpr._multiplicity(), resolvedParamMul, childBindings);
            context.debug("reverseMatch: argGT=%s resolvedParamGT=%s childBindings=%s",
                    lazy(() -> _GenericType.print(argGT)), lazy(() -> _GenericType.print(resolvedParamGT)), childBindings);

            childBindings.typeBindings().replaceAll((name, types) ->
                    types.collect(gt -> _GenericType.asInferred(_GenericType.makeAsConcreteAsPossible(gt, bindings, model))));
            context.debug("reverseMatch: pushing %s into %s", childBindings, childExpr._functionName());
            _FunctionExpression.populateResolvedParameters(childExpr, childBindings, model);
        }
    }

    /**
     * Find function candidates by name and arity.
     * Handles both qualified names (e.g., "test::if") and unqualified names.
     */
    private static MutableList<FunctionIndexEntry> findCandidates(String functionName, int paramCount, MetadataAccess model, CompilationContext context)
    {
        String lookupName = functionName;
        int lastSep = functionName.lastIndexOf("::");
        if (lastSep > 0)
        {
            lookupName = functionName.substring(lastSep + 2);
        }
        MutableList<FunctionIndexEntry> candidates = new CompositePureLanguageMetadata(model.getMetadataAccessExtension(PureLanguageMetadata.class), model).findFunctionHeadersByNameAndArity(lookupName, paramCount);
        if (lastSep > 0)
        {
            String prefix = functionName.substring(0, lastSep);
            return candidates.select(e -> e.fullPath().startsWith(prefix + "::"));
        }
        return candidates.select(e -> context.isElementVisible(e.fullPath()));
    }


    /**
     * Build suggestion text for function-not-found errors.
     */
    private static String buildFunctionSuggestions(String functionName, int paramCount, MetadataAccess model, CompilationContext context)
    {
        MutableMap<Integer, MutableList<FunctionIndexEntry>> allByParamCount = new CompositePureLanguageMetadata(model.getMetadataAccessExtension(PureLanguageMetadata.class), model).findFunctionHeadersByName(functionName);
        if (allByParamCount == null || allByParamCount.isEmpty())
        {
            return "";
        }

        MutableList<String> sameParamInScope = Lists.mutable.empty();
        MutableList<String> sameParamOutScope = Lists.mutable.empty();
        MutableList<String> diffParam = Lists.mutable.empty();

        allByParamCount.forEachKeyValue((pc, entries) ->
        {
            if (pc == paramCount)
            {
                entries.forEach(entry ->
                {
                    String sig = entry.signature();
                    if (context.isElementVisible(entry.fullPath()))
                    {
                        sameParamInScope.add(sig);
                    }
                    else
                    {
                        sameParamOutScope.add(sig);
                    }
                });
            }
            else
            {
                entries.forEach(entry -> diffParam.add(entry.signature()));
            }
        });

        StringBuilder sb = new StringBuilder();
        if (sameParamInScope.notEmpty())
        {
            sb.append(". Possible matches with ").append(paramCount).append(" parameter(s) in scope:");
            sameParamInScope.forEach(p -> sb.append("\n    ").append(p));
        }
        if (sameParamOutScope.notEmpty())
        {
            sb.append(". Possible matches with ").append(paramCount).append(" parameter(s) not in scope:");
            sameParamOutScope.forEach(p -> sb.append("\n    ").append(p));
        }
        if (diffParam.notEmpty())
        {
            sb.append(". Possible matches with different number of parameters:");
            diffParam.forEach(p -> sb.append("\n    ").append(p));
        }
        return sb.toString();
    }


    /**
     * Reset all resolution state and re-resolve args for the next candidate attempt.
     */
    public static void resetResolutionForFunctionExpression(FunctionExpression expr, CompilationContext context)
    {
        context.debug("resetResolution: %s func=%s gt=%s mul=%s", expr._functionName(), lazy(() -> CompilationContext.debugFunc(expr._func())), lazy(() -> _GenericType.print(expr._genericType())), lazy(() -> _Multiplicity.print(expr._multiplicity())));
        // Wipe matched function and resolved type/multiplicity parameters
        expr._func(null);
        expr._resolvedTypeParameters(null);
        expr._resolvedMultiplicityParameters(null);

        // Full reset on all parameter values, handling automap revert
        if (expr._parametersValues() != null)
        {
            MutableList<ValueSpecification> params = expr._parametersValues();
            for (int i = 0; i < params.size(); i++)
            {
                ValueSpecification replaced = ValueSpecificationResolver.resetResolution(params.get(i), context);
                if (replaced != params.get(i))
                {
                    params.set(i, replaced);
                }
            }
        }
    }

    // ========================================================================
    // Internal helpers
    // ========================================================================

    private static Type resolvedRawType(ValueSpecification vs)
    {
        GenericType gt = vs._genericType();
        return gt != null ? gt._rawType() : null;
    }

    private static boolean isSuccessfullyProcessed(ValueSpecification parameterValue, ValueSpecification reprocessed, MutableSet<String> scopeTypeParams, MutableSet<String> scopeMulParams)
    {
        return parameterValue instanceof CompilerGenericTypeAndMultiplicityHolder || isConcreteInContext(reprocessed._genericType(), reprocessed._multiplicity(), scopeTypeParams, scopeMulParams);
    }

    private static boolean isConcreteInContext(
            GenericType paramGT,
            Multiplicity paramMul,
            MutableSet<String> scopeTypeParams,
            MutableSet<String> scopeMulParams)
    {
        return paramGT != null && _GenericType.isConcreteInContext(paramGT, scopeTypeParams)
                && paramMul != null && _Multiplicity.isConcreteInContext(paramMul, scopeMulParams);
    }
}
