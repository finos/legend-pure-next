package org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.resolution.valueSpecification.functionExpressionResolver;

import meta.pure.metamodel.SourceInformation;
import meta.pure.metamodel.function.LambdaFunction;
import meta.pure.metamodel.function.LambdaFunctionImpl;
import meta.pure.metamodel.multiplicity.Multiplicity;
import meta.pure.metamodel.type.FunctionType;
import meta.pure.metamodel.type.generics.GenericType;
import meta.pure.metamodel.type.generics.GenericTypeValue;
import meta.pure.metamodel.type.generics.ResolvedMultiplicityParameterImpl;
import meta.pure.metamodel.type.generics.ResolvedTypeParameterImpl;
import meta.pure.metamodel.type.generics.TypeParameter;
import meta.pure.metamodel.valuespecification.AtomicValue;
import meta.pure.metamodel.valuespecification.Collection;
import meta.pure.metamodel.valuespecification.CompilerGenericTypeAndMultiplicityHolder;
import meta.pure.metamodel.valuespecification.FunctionApplication;
import meta.pure.metamodel.valuespecification.FunctionExpression;
import meta.pure.metamodel.valuespecification.ValueSpecification;
import meta.pure.metamodel.valuespecification.VariableExpression;
import org.eclipse.collections.api.list.ListIterable;
import org.eclipse.collections.api.list.MutableList;
import org.eclipse.collections.api.map.MutableMap;
import org.eclipse.collections.api.set.MutableSet;
import org.eclipse.collections.impl.factory.Lists;
import org.eclipse.collections.impl.factory.Sets;
import org.finos.legend.pure.m3.module.MetadataAccess;
import org.finos.legend.pure.m3.module.localModule.topLevel.CompilationContext;
import org.finos.legend.pure.m3.module.localModule.topLevel.CompilationError;
import org.finos.legend.pure.m3.pureLanguage.metadata.CompositePureLanguageMetadata;
import org.finos.legend.pure.m3.pureLanguage.metadata.PureLanguageMetadata;
import org.finos.legend.pure.m3.pureLanguage.metadata.lazyFunctions.FunctionIndexEntry;
import org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.FunctionCallParametersBinding;
import org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.ParametersBinding;
import org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.PlainParametersBinding;
import org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.PureLanguageCompilerContext;
import org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._FunctionExpression;
import org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._GenericType;
import org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._Multiplicity;
import org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.resolution.valueSpecification.ValueSpecificationResolver;
import org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.resolution.valueSpecification.functionExpressionResolver.functionSpecific.RelationColumnResolver;

import java.util.Comparator;

import static org.finos.legend.pure.m3.module.localModule.topLevel.CompilationContext.lazy;

public class FunctionApplicationResolver
{
    /**
     * Resolve a FunctionApplication or ArrowFunction expression by looking up
     * function candidates by name and arity, then resolving args, collecting
     * bindings, resolving lambdas, and picking the best match.
     */
    public static FunctionApplication resolveFunctionApplication(
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
            return (FunctionApplication) expr._func(null);
        }
        else if (candidates.size() == 1)
        {
            // Single candidate — resolve directly, errors are legitimate
            FunctionIndexEntry entry = candidates.getFirst();
            context.incrementCandidateEvaluationCount();
            return (FunctionApplication) resolveFunctionApplicationUsingTemplateFunctionForInference(expr, entry, model, context);
        }
        else
        {
            // Pre-resolve non-lambda parameter values once to avoid exponential
            // re-resolution across candidate attempts.  The resolved type of a
            // sub-expression (e.g. plus('@', toString(42)) → String[1]) is
            // independent of the parent candidate's expected type.  Lambdas are
            // excluded because their parameter types depend on the candidate's
            // type-parameter bindings.
            int preResolveCheckpoint = context.currentErrorCount();
            FunctionApplication preResolvedExpr = (FunctionApplication)
                    ((FunctionApplication) expr._copy())._parametersValues(
                            expr._parametersValues().collect(
                            pv -> (pv instanceof AtomicValue av && av._value() instanceof LambdaFunction)
                                    ? pv
                                    : ValueSpecificationResolver.resolve(pv, model, context))
                    );
            context.rollbackErrorsTo(preResolveCheckpoint);

            // Multiple candidates (most-specific-first) — try each with rollback.
            MutableList<CompilationError> bestCandidateErrors = null;
            for (FunctionIndexEntry candidateEntry : candidates)
            {
                int errorCheckpoint = context.currentErrorCount();
                context.incrementCandidateEvaluationCount();
                FunctionApplication resolved = (FunctionApplication) resolveFunctionApplicationUsingTemplateFunctionForInference(preResolvedExpr, candidateEntry, model, context);

                if (context.currentErrorCount() == errorCheckpoint)
                {
                    // Clean resolution — check for equally-specific ties
                    Comparator<FunctionIndexEntry> cmp = FunctionIndexEntry.mostSpecificFirst(model);
                    MutableList<FunctionIndexEntry> tied = candidates.select(c -> cmp.compare(candidateEntry, c) == 0);
                    if (tied.size() > 1)
                    {
                        String signatures = tied.collect(FunctionIndexEntry::signature).sortThis().makeString(", ");
                        SourceInformation srcInfo = resolved._sourceInformation();
                        context.addError(new CompilationError("Ambiguous function call: multiple equally-specific matches found [" + signatures + "]", srcInfo));
                        return (FunctionApplication) resolved._func(null);
                    }
                    return resolved;
                }

                // Resolution produced errors — snapshot and roll back for next candidate
                MutableList<CompilationError> candidateErrors = context.snapshotErrorsFrom(errorCheckpoint);
                context.rollbackErrorsTo(errorCheckpoint);
                if (bestCandidateErrors == null)
                {
                    bestCandidateErrors = candidateErrors;
                }
            }
            // No candidate resolved cleanly — restore the best candidate's errors
            if (bestCandidateErrors != null)
            {
                context.addErrors(bestCandidateErrors);
            }
            return (FunctionApplication) ((FunctionApplication) expr._copy())._func(null);
        }
    }

    /**
     * Resolve args, collect type/multiplicity bindings, apply bindings to
     * lambdas, validate, and populate resolved parameters on the expression.
     */
    private static FunctionExpression resolveFunctionApplicationUsingTemplateFunctionForInference(
            FunctionExpression expr,
            FunctionIndexEntry entry,
            MetadataAccess model,
            CompilationContext context)
    {
        // Push binding node for this function resolution (seeds pre-resolved bindings automatically)
        PureLanguageCompilerContext plcc = context.compilerContextExtensions(PureLanguageCompilerContext.class);
        FunctionCallParametersBinding node = plcc.pushBindingNode(expr, entry);

        // Clear stale resolved params — they've been seeded into the node.
        FunctionExpression cleanExpr = ((FunctionExpression) expr._copy())
                ._resolvedTypeParameters(null)
                ._resolvedMultiplicityParameters(null);

        MutableList<VariableExpression> funcParams = entry.functionType()._parameters();
        MutableSet<String> scopeTypeParams = plcc.inScopeTypeParamNames();
        MutableSet<String> scopeMulParams = plcc.inScopeMultiplicityParamNames();

        try
        {
            context.debug("resolveFunctionApplicationUsingTemplateFunction: %s (%d args)", entry.fullPath(), cleanExpr._parametersValues().size());
            context.debugDepthInc();

            // Initialize: all params start unresolved
            MutableList<ParameterInfo> infos = cleanExpr._parametersValues().collect(pv -> new ParameterInfo());
            // Fixpoint: resolve params iteratively until no more progress
            MutableList<ParameterInfo> resolved = fixpointResolveParams(infos, cleanExpr, funcParams, node, scopeTypeParams, scopeMulParams, model, context, 1);

            context.debugDepthDec();

            // Push back saved errors from all unresolved args
            MutableList<ParameterInfo> unresolvedInfos = resolved.select(info -> !info.resolved);
            unresolvedInfos.flatCollect(info -> info.savedErrors != null ? info.savedErrors : Lists.mutable.empty())
                    .forEach(context::addError);

            // Assemble result: set func even if some params unresolved (enables reverse-match)
            FunctionExpression result = cleanExpr
                    ._parametersValues(resolved.collect(info -> info.newParam))
                    ._func(entry);

            if (unresolvedInfos.isEmpty())
            {
                return validateAndPopulate(result, entry, node, model, context);
            }
            return result;
        }
        finally
        {
            plcc.popBindingNode();
        }
    }

    /**
     * One fixpoint pass: try to resolve each unresolved parameter, collecting bindings
     * as each resolves. Recurse until no more progress is made.
     */
    private static MutableList<ParameterInfo> fixpointResolveParams(
            MutableList<ParameterInfo> infos,
            FunctionExpression expr,
            MutableList<VariableExpression> funcParams,
            FunctionCallParametersBinding node,
            MutableSet<String> scopeTypeParams,
            MutableSet<String> scopeMulParams,
            MetadataAccess model,
            CompilationContext context,
            int iteration)
    {
        context.debug("--- fixpoint iteration %d ---", iteration);

        // One pass: try to resolve each unresolved param (sequential — binding accumulation is order-dependent)
        MutableList<ParameterInfo> updated = infos.collectWithIndex((info, i) ->
                info.resolved?
                        info :
                        resolveOneParam(i, expr, funcParams, node, scopeTypeParams, scopeMulParams, model, context)
        );

        // Derive progress: did any param transition from unresolved to resolved?
        boolean madeProgress = infos.zip(updated).anySatisfy(pair -> !pair.getOne().resolved && pair.getTwo().resolved);

        return madeProgress
                ? fixpointResolveParams(updated, expr, funcParams, node, scopeTypeParams, scopeMulParams, model, context, iteration + 1)
                : updated;
    }

    /**
     * Try to resolve a single parameter at the given index.
     * Returns a new ParameterInfo (resolved or unresolved with saved errors).
     */
    private static ParameterInfo resolveOneParam(
            int index,
            FunctionExpression expr,
            MutableList<VariableExpression> funcParams,
            FunctionCallParametersBinding node,
            MutableSet<String> scopeTypeParams,
            MutableSet<String> scopeMulParams,
            MetadataAccess model,
            CompilationContext context)
    {
        ValueSpecification parameterValue = expr._parametersValues().get(index);
        GenericType paramGT = funcParams.get(index)._genericType();
        Multiplicity paramMul = funcParams.get(index)._multiplicity();

        context.debug("parameterValue[%d] class=%s paramGT=%s bindings=%s parentBindings=%s",
                index, parameterValue.getClass().getSimpleName(),
                lazy(() -> _GenericType.print(paramGT)), node, lazy(node::printParentBindings));

        int checkpoint = context.currentErrorCount();
        context.debugDepthInc();
        ValueSpecification reprocessed = resolveParameterValue(parameterValue, paramGT, paramMul, node, scopeTypeParams, scopeMulParams, model, context);
        context.debugDepthDec();

        if (isSuccessfullyProcessed(parameterValue, reprocessed, scopeTypeParams, scopeMulParams))
        {
            context.debug(parameterValue instanceof CompilerGenericTypeAndMultiplicityHolder
                    ? "=> RESOLVED (TypeHolder = compiler-managed, enriched later)"
                    : "=> RESOLVED gt=%s mul=%s",
                    lazy(() -> _GenericType.print(reprocessed._genericType())),
                    lazy(() -> _Multiplicity.print(reprocessed._multiplicity())));
            return ParameterInfo.resolved(reprocessed);
        }
        else
        {
            MutableList<CompilationError> errors = context.snapshotErrorsFrom(checkpoint);
            context.rollbackErrorsTo(checkpoint);
            context.debug("=> NOT RESOLVED");
            return ParameterInfo.unresolved(reprocessed, errors);
        }
    }

    // ==================================================================================================
    // Validation and population of the FunctionExpression's genericType, multiplicity & type parameters
    // ==================================================================================================

    /**
     * Validate binding consistency, check arg types against params,
     * and populate resolved parameters on the expression.
     */
    private static FunctionExpression validateAndPopulate(
            FunctionExpression expr,
            FunctionIndexEntry entry,
            FunctionCallParametersBinding functionCallParametersBinding,
            MetadataAccess model,
            CompilationContext context)
    {
        ListIterable<? extends ValueSpecification> paramValues = expr._parametersValues();
        FunctionType functionType = entry.functionType();

        // Compiler-tracked enrichment: for relation column functions, the
        // TypeHolder's RelationType can't be inferred from bindings alone -
        // the column types come from the lambda return types.
        FunctionExpression newExpression = RelationColumnResolver.enrichRelationTypeHolderForMagicalFunctions(expr, functionType, functionCallParametersBinding, model, context);

        // Cross-match, transitive resolution, and common-type reduction.
        // Owner-aware routing is handled inside FunctionCallNode.unify().
        functionCallParametersBinding.unify(model);

        int checkpoint = context.currentErrorCount();
        // Validate arg types and multiplicities against param expectations.
        // functionCallParametersBinding IS a ParametersBinding -- pass it directly.
        FunctionExpression validated = validateParameterValuesToFunctionType(newExpression, functionType, functionCallParametersBinding, model, context);

        if (context.currentErrorCount() == checkpoint)
        {
            return _FunctionExpression.populateResolvedParameters(validated, functionCallParametersBinding, functionCallParametersBinding.ownTypeParamNames(), functionCallParametersBinding.ownMulParamNames(), model);
        }
        return validated;
    }

    private static FunctionExpression validateParameterValuesToFunctionType(FunctionExpression expr, FunctionType functionType, ParametersBinding bindings, MetadataAccess model, CompilationContext context)
    {
        boolean[] errorReported = {false};
        return expr._parametersValues(functionType._parameters().zip(expr._parametersValues()).collect(pair ->
        {
            VariableExpression declaredParam = pair.getOne();
            ValueSpecification argVS = pair.getTwo();
            GenericType argGT = argVS._genericType();
            Multiplicity argMul = argVS._multiplicity();
            if (argGT == null || argMul == null)
            {
                return argVS;
            }
            GenericType expectedGT = _GenericType.makeAsConcreteAsPossible(declaredParam._genericType(), bindings, model);
            Multiplicity expectedMul = _Multiplicity.makeAsConcreteAsPossible(declaredParam._multiplicity(), bindings);
            GenericType reconciledArgGT = _GenericType.reconcileInferred(expectedGT, argGT, model);
            ValueSpecification reconciled = rebuildWithReconciledType(argVS, reconciledArgGT, model);
            if (!errorReported[0] && (!_GenericType.isCompatible(expectedGT, reconciledArgGT, model) || !_Multiplicity.subsumes(expectedMul, argMul)))
            {
                context.addError(new CompilationError("No matching function '" + expr._functionName() + "' found for argument types (" +
                        expr._parametersValues().collect(vs ->
                                _GenericType.print(vs._genericType()) + _Multiplicity.print(vs._multiplicity())).makeString(", ") +
                        ")", expr._sourceInformation()));
                errorReported[0] = true;
            }
            return reconciled;
        }));
    }

    /**
     * Rebuild a ValueSpecification with a reconciled GenericType.
     * If the VS wraps a lambda, rebuilds the lambda with the new FunctionType's parameters.
     * Mirrors the Pure pattern: {@code ^$av(value = ^$lambda(parameters = reconciledFT.parameters), genericType = reconciledGT)}
     */
    private static ValueSpecification rebuildWithReconciledType(ValueSpecification vs, GenericType reconciledGT, MetadataAccess model)
    {
        if (vs instanceof AtomicValue av && av._value() instanceof LambdaFunction lambda)
        {
            // Extract the reconciled FunctionType from the GenericType chain:
            // reconciledGT = LambdaFunction<{FunctionType}> → typeArguments[0] → rawType
            FunctionType reconciledFT = extractReconciledFunctionType(reconciledGT);
            if (reconciledFT != null && reconciledFT._parameters() != null)
            {
                // ^$lambda(parameters = reconciledFT._parameters())
                LambdaFunctionImpl newLambda = new LambdaFunctionImpl()
                        ._classifierGenericType((GenericTypeValue) reconciledGT)
                        ._parameters(reconciledFT._parameters())
                        ._expressionSequence(lambda._expressionSequence())
                        ._openVariables(lambda._openVariables());
                // ^$av(value = newLambda, genericType = reconciledGT)
                return new meta.pure.metamodel.valuespecification.AtomicValueImpl(model)
                        ._value(newLambda)
                        ._genericType(reconciledGT)
                        ._multiplicity(av._multiplicity())
                        ._sourceInformation(av._sourceInformation());
            }
        }
        // Non-lambda case: just update the genericType
        return ((ValueSpecification)vs._copy())._genericType(reconciledGT);
    }

    /**
     * Extract the FunctionType from a reconciled GenericType.
     * Handles both direct FunctionType rawType and the common
     * {@code Function<{FunctionType}>} / {@code LambdaFunction<{FunctionType}>} pattern.
     */
    private static FunctionType extractReconciledFunctionType(GenericType gt)
    {
        if (gt instanceof meta.pure.metamodel.type.generics.GenericTypeValue gtv)
        {
            if (gtv._type() instanceof FunctionType ft)
            {
                return ft;
            }
            if (gtv._typeArguments() != null && !gtv._typeArguments().isEmpty())
            {
                GenericType firstArg = gtv._typeArguments().get(0);
                if (firstArg instanceof meta.pure.metamodel.type.generics.GenericTypeValue argGTV
                        && argGTV._type() instanceof FunctionType ft)
                {
                    return ft;
                }
            }
        }
        return null;
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

        // If the argument already carries a concrete type and multiplicity
        // (e.g. pre-resolved before the candidate loop), skip the expensive
        // recursive ValueSpecificationResolver.resolve() and proceed directly
        // to binding collection.
        ValueSpecification processed = (arg._genericType() != null && _GenericType.isConcrete(arg._genericType())
                && arg._multiplicity() != null && _Multiplicity.isConcrete(arg._multiplicity()))
                ? arg
                : ValueSpecificationResolver.resolve(arg, model, context);

        if (isConcreteInContext(processed._genericType(), processed._multiplicity(), scopeTypeParams, scopeMulParams))
        {
            context.debug("resolveArg: CONCRETE (in context) gt=%s", lazy(() -> _GenericType.print(arg._genericType())));
            GenericType argGT = processed._genericType();
            // When the arg type is a subtype of the param type (e.g., Property vs Function),
            // resolve to the param's raw type level before collecting bindings to ensure
            // type arguments are positionally aligned.
            if (!(_GenericType.type(paramGT) instanceof TypeParameter) && _GenericType.type(paramGT) != _GenericType.type(argGT))
            {
                argGT = _GenericType.resolveForTarget(argGT, _GenericType.type(paramGT), model);
            }
            _GenericType.collectTypeParameterBindings(paramGT, argGT, bindings);
            _Multiplicity.collectMultiplicityParameterBindings(paramMul, processed._multiplicity(), bindings);
            return processed;
        }
        else if (processed instanceof AtomicValue av && av._value() instanceof LambdaFunction lambda)
        {
            context.rollbackErrorsTo(checkpoint);
            context.debug("resolveArg: LAMBDA");
            LambdaFunction reprocessedLambda = setMissingLambdaParameterInformation(lambda, paramGT, bindings, model);
            AtomicValue withReprocessedLambda = ((AtomicValue) processed._copy())._value(reprocessedLambda);
            return finishProcessing(paramGT, paramMul, bindings, model, context, withReprocessedLambda);
        }
        else if (processed instanceof FunctionApplication childExpr && childExpr._func() != null)
        {
            context.rollbackErrorsTo(checkpoint);
            context.debug("resolveArg: REVERSE_MATCH func=%s childGT=%s", childExpr._functionName(), lazy(() -> _GenericType.print(childExpr._genericType())));
            return finishProcessing(paramGT, paramMul, bindings, model, context, reverseMatch(childExpr, paramGT, paramMul, bindings, model, context));
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
            enrichedScopeTypeParams.addAll(_GenericType.collectReferencedTypeParameterNames(resolvedParamGT));
            context.debug("resolveArg: COLLECTION (%d elements) resolvedParamGT=%s", col._values().size(), lazy(() -> _GenericType.print(resolvedParamGT)));
            context.debugDepthInc();
            Collection newCollection = ((Collection)col._copy())._values(
                    col._values().collect(element ->
                    {
                        context.debug("element: class=%s%s gt=%s",
                                element.getClass().getSimpleName(),
                                element instanceof FunctionExpression fe ? " fname=" + fe._functionName() : "",
                                lazy(() -> _GenericType.print(element._genericType())));
                        // Each element resolves with isolated bindings so that inner function
                        // type params (e.g., L from agg<K,L,M>) don't leak between elements.
                        ParametersBinding elementBindings = bindings.copy();
                        ValueSpecification newValue = resolveParameterValue(element, resolvedParamGT, resolvedParamMul, elementBindings, enrichedScopeTypeParams, scopeMulParams, model, context);
                        context.debug("element after: gt=%s", lazy(() -> _GenericType.print(element._genericType())));
                        return newValue;
                    })
            );
            context.debugDepthDec();
            // Prevent finishProcessing's VSR.resolve from re-producing errors for
            // already-processed elements (e.g., unresolved lambdas would produce
            // duplicate "Can't resolve lambda parameter types" errors).
            int preFinishCheckpoint = context.currentErrorCount();
            ValueSpecification finished = finishProcessing(paramGT, paramMul, bindings, model, context, newCollection);
            if (context.currentErrorCount() > preFinishCheckpoint)
            {
                context.rollbackErrorsTo(preFinishCheckpoint);
            }
            return finished;
        }
        context.debug("resolveArg: NO_MATCH class=%s gt=%s", arg.getClass().getSimpleName(), lazy(() -> _GenericType.print(arg._genericType())));
        return processed;
    }

    private static ValueSpecification finishProcessing(GenericType paramGT, Multiplicity paramMul, ParametersBinding bindings, MetadataAccess model, CompilationContext context, ValueSpecification vs)
    {
        context.debugDepthInc();
        vs = ValueSpecificationResolver.resolve(vs, model, context);
        context.debugDepthDec();

        // Mark all resolved parameters as inferred — create new instances, no in-place mutation.
        if (vs instanceof FunctionExpression childExpr && childExpr._resolvedTypeParameters() != null)
        {
            childExpr._resolvedTypeParameters(childExpr._resolvedTypeParameters().collect(rtp ->
                    new ResolvedTypeParameterImpl(model)
                            ._name(rtp._name())
                            ._value(_GenericType.asInferred(rtp._value(), model))));
        }
        if (vs instanceof FunctionExpression childExpr && childExpr._resolvedMultiplicityParameters() != null)
        {
            childExpr._resolvedMultiplicityParameters(childExpr._resolvedMultiplicityParameters().collect(rmp ->
                    new ResolvedMultiplicityParameterImpl(model)
                            ._name(rmp._name())
                            ._value(_Multiplicity.asInferred(rmp._value(), model))));
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
    private static LambdaFunction setMissingLambdaParameterInformation(
            LambdaFunction lambda,
            GenericType paramGT,
            ParametersBinding bindings,
            MetadataAccess model)
    {
        // Resolve the function parameter's Function<{FunctionType}> to concrete types using current bindings
        // e.g. Function<{T[1]->Boolean[1]}> with T=String => Function<{String[1]->Boolean[1]}>
        GenericType concreteGT = _GenericType.makeAsConcreteAsPossible(paramGT, bindings, model);
        GenericType innerGT = _GenericType.typeArguments(concreteGT).getFirst();
        if (_GenericType.type(innerGT) instanceof FunctionType ft)
        {
            lambda =  ((LambdaFunctionImpl) lambda._copy())
                    ._parameters(ft._parameters().zip(lambda._parameters()).collect(pair ->
                    {
                        VariableExpression ftParam = pair.getOne();
                        VariableExpression lambdaParam = pair.getTwo();
                        GenericType resolvedType = _GenericType.makeAsConcreteAsPossible(ftParam._genericType(), bindings, model);
                        VariableExpression ve = (VariableExpression) lambdaParam._copy();
                        if (resolvedType != null && !_GenericType.isConcrete(lambdaParam._genericType()))
                        {
                            ve._genericType(_GenericType.asInferred(resolvedType, model));
                        }
                        Multiplicity resolvedMul = _Multiplicity.makeAsConcreteAsPossible(ftParam._multiplicity(), bindings);
                        if (resolvedMul != null && !_Multiplicity.isConcrete(lambdaParam._multiplicity()))
                        {
                            ve._multiplicity(_Multiplicity.asInferred(resolvedMul, model));
                        }
                        return ve;
                    })
            );
        }
        return lambda;
    }

    /**
     * Reverse match: push parent bindings into a child FE's resolved
     * slots and re-resolve the child function application.
     */
    private static FunctionApplication reverseMatch(
            FunctionApplication childExpr,
            GenericType paramGT,
            Multiplicity paramMul,
            ParametersBinding bindings,
            MetadataAccess model,
            CompilationContext context)
    {
        GenericType argGT = childExpr._genericType();
        ParametersBinding childBindings = PlainParametersBinding.empty();
        GenericType resolvedParamGT = _GenericType.makeAsConcreteAsPossible(paramGT, bindings, model);
        _GenericType.collectTypeParameterBindings(argGT, resolvedParamGT, childBindings);
        Multiplicity resolvedParamMul = _Multiplicity.makeAsConcreteAsPossible(paramMul, bindings);
        _Multiplicity.collectMultiplicityParameterBindings(childExpr._multiplicity(), resolvedParamMul, childBindings);
        context.debug("reverseMatch: argGT=%s resolvedParamGT=%s childBindings=%s",
                lazy(() -> _GenericType.print(argGT)), lazy(() -> _GenericType.print(resolvedParamGT)), childBindings);

        childBindings.typeBindings().replaceAll((name, types) ->
                types.collect(gt -> _GenericType.asInferred(_GenericType.makeAsConcreteAsPossible(gt, bindings, model), model)));
        context.debug("reverseMatch: pushing %s into %s", childBindings, childExpr._functionName());
        return (FunctionApplication) _FunctionExpression.populateResolvedParameters(childExpr, childBindings, model);
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


    // ========================================================================
    // Internal helpers
    // ========================================================================

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

    static class ParameterInfo
    {
        final boolean resolved;
        final MutableList<CompilationError> savedErrors;
        final ValueSpecification newParam;

        ParameterInfo()
        {
            this(false, null, null);
        }

        ParameterInfo(boolean resolved, ValueSpecification newParam, MutableList<CompilationError> savedErrors)
        {
            this.resolved = resolved;
            this.newParam = newParam;
            this.savedErrors = savedErrors;
        }

        static ParameterInfo resolved(ValueSpecification vs)
        {
            return new ParameterInfo(true, vs, null);
        }

        static ParameterInfo unresolved(ValueSpecification vs, MutableList<CompilationError> errors)
        {
            return new ParameterInfo(false, vs, errors);
        }
    }
}
