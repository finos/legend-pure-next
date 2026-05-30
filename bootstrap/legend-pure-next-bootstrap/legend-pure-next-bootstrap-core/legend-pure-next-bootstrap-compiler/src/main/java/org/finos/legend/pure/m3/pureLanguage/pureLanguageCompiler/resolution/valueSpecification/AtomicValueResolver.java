package org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.resolution.valueSpecification;

import meta.pure.metamodel.PackageableElement;
import meta.pure.metamodel.function.LambdaFunction;
import meta.pure.metamodel.function.LambdaFunctionImpl;
import meta.pure.metamodel.type.generics.GenericType;
import meta.pure.metamodel.type.generics.GenericTypeValue;
import meta.pure.metamodel.valuespecification.AtomicValue;
import meta.pure.metamodel.valuespecification.AtomicValueImpl;
import meta.pure.metamodel.valuespecification.FunctionExpression;
import meta.pure.metamodel.valuespecification.ValueSpecification;
import meta.pure.metamodel.valuespecification.VariableExpression;
import meta.pure.protocol.grammar.Package_Pointer;
import meta.pure.metamodel.type.generics.CompilerNotSetGenericType;
import meta.pure.metamodel.type.generics.CompilerNotSetGenericTypeImpl;
import org.eclipse.collections.api.factory.Lists;
import org.eclipse.collections.api.factory.Sets;
import org.eclipse.collections.api.list.MutableList;
import org.eclipse.collections.api.set.MutableSet;
import org.finos.legend.pure.m3.module.MetadataAccess;
import org.finos.legend.pure.m3.module.localModule.topLevel.CompilationContext;
import org.finos.legend.pure.m3.module.localModule.topLevel.CompilationError;
import org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.PureLanguageCompilerContext;
import org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._Function;
import org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._GenericType;
import org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._Lambda;
import org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._Multiplicity;
import org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._PackageableElement;
import org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.resolution.FunctionDefinitionResolver;
import org.jspecify.annotations.Nullable;

import static org.finos.legend.pure.m3.module.localModule.topLevel.CompilationContext.lazy;

public class AtomicValueResolver
{
    /**
     * Resolve an AtomicValue: if it wraps a LambdaFunction, resolve the lambda body;
     * if it wraps a Package_Pointer, resolve it to the actual element from the model.
     */
    public static AtomicValue resolveAtomicValue(AtomicValue av, MetadataAccess model, CompilationContext context)
    {
        Object value = av._value();
        if (value instanceof LambdaFunction lambda)
        {
            return processLambda(av, model, context, lambda);
        }
        else if (value instanceof meta.pure.protocol.grammar.Package_Pointer pp)
        {
            return processPackagePointer(av, model, context, pp);
        }
        else if (av._genericType() == null || av._genericType() instanceof CompilerNotSetGenericType)
        {
            throw new RuntimeException("Not supported yet " + value.getClass());
        }
        return av;
    }

    private static AtomicValue processPackagePointer(AtomicValue av, MetadataAccess model, CompilationContext context, Package_Pointer pp)
    {
        String pointerValue = pp._value();
        int checkpoint = context.currentErrorCount();
        PackageableElement element = _PackageableElement.findElementOrReportError(pointerValue, context.imports(), model, context, av._sourceInformation());
        if (element != null)
        {
            return ((AtomicValueImpl) av._copy())
                    ._value(element)
                    ._genericType(_GenericType.asInferred(_GenericType.withUndefinedTypeParams(element._classifierGenericType(), model), model));
        }
        else if (context.currentErrorCount() == checkpoint)
        {
            context.addError(new CompilationError("The element '" + pointerValue + "' can't be found", av._sourceInformation()));
        }
        return av;
    }

    private static @Nullable AtomicValue processLambda(AtomicValue av, MetadataAccess model, CompilationContext context, LambdaFunction _lambda)
    {
        // Lazily populate the lambda's _openVariables (structurally — names of var refs that
        // aren't lambda's own params). The lambda body resolution depends on the SCOPE-RESOLVED
        // types of these names, which we include in the cache key. Without this, two visits with
        // identical paramShape but different outer bindings collide and return stale resolutions.
        if (_lambda._openVariables() == null || _lambda._openVariables().isEmpty())
        {
            MutableList<VariableExpression> openVarsForKey = computeOpenVariablesStructurally(_lambda);
            if (!openVarsForKey.isEmpty())
            {
                ((LambdaFunctionImpl) _lambda)._openVariables(openVarsForKey);
            }
        }

        LambdaFunction lambda = (LambdaFunction) _lambda._copy();
        MutableList<VariableExpression> params = lambda._parameters();
        PureLanguageCompilerContext plcc = context.compilerContextExtensions(PureLanguageCompilerContext.class);

        // Cache key shape: sourceInfo + paramShape + openVar-name=current-scope-type pairs.
        // Key includes scope-resolved open-var types so failure caching is safe.
        String cacheKey = buildLambdaCacheKey(av, params, lambda._openVariables(), plcc);

        // (1) Failure cache: previously determined this exact key can't resolve. Re-emit
        // the standard error and return immediately.
        if (cacheKey != null && plcc.lambdaFailureCache().contains(cacheKey))
        {
            context.addError(new CompilationError("Can't resolve lambda parameter types", av._sourceInformation()));
            return av;
        }

        // Skip body resolution if lambda params don't have concrete types.
        // Use isConcreteInContext to allow in-scope type params (e.g., class-level T)
        if (params.anySatisfy(p -> !_GenericType.isConcreteInContext(p._genericType(), plcc.inScopeTypeParamNames()) ||
                !_Multiplicity.isConcreteInContext(p._multiplicity(), plcc.inScopeMultiplicityParamNames())))
        {
            // Record the failure verdict so subsequent identical visits short-circuit at (1).
            // When setMissingLambdaParameterInformation later substitutes a free T → Integer
            // the shape (and therefore cacheKey) changes, so this entry won't match the new
            // attempt — we never block a retry that could legitimately succeed.
            if (cacheKey != null) plcc.lambdaFailureCache().add(cacheKey);
            context.addError(new CompilationError("Can't resolve lambda parameter types", av._sourceInformation()));
            return av;
        }

        // (2) Body cache: memoized body resolution outcomes including errors fired.
        if (cacheKey != null)
        {
            PureLanguageCompilerContext.CachedLambdaOutcome cached = plcc.lambdaResolutionCache().get(cacheKey);
            if (cached != null)
            {
                context.debug("resolveAtomicValue: LAMBDA cache hit");
                cached.errorsFired.forEach(context::addError);
                return cached.av;
            }
        }
        int errorCheckpointForCache = context.currentErrorCount();

        // Check for lambda param names that conflict with existing scope variables
        _Function.validateFunctionParameters(params, context, av._sourceInformation());
        plcc.pushScope(params);
        try
        {
            context.debug("resolveAtomicValue: LAMBDA resolving body");
            lambda._expressionSequence(FunctionDefinitionResolver.resolveExpressionSequence(lambda._expressionSequence(), model, context));
        }
        finally
        {
            plcc.popScope();
        }

        // Collect open variables: variable references whose names
        // are not lambda parameters (they come from enclosing scopes)
        MutableSet<String> paramNames = params.collect(VariableExpression::_name).toSet();
        MutableList<VariableExpression> openVars = Lists.mutable.empty();
        MutableSet<String> seenNames = Sets.mutable.empty();
        if (lambda._expressionSequence() != null)
        {
            lambda._expressionSequence().forEach(vs -> collectVariableReferences(vs, paramNames, seenNames, openVars));
        }
        ((LambdaFunctionImpl) lambda)._openVariables(openVars);

        // Always (re)build the lambda's genericType: LambdaFunction<{paramTypes -> returnType}>
        GenericType lambdaGT = _Lambda.getLambdaClassifierGenericType(model, lambda);

        context.debug("resolveAtomicValue: LAMBDA gt=%s", lazy(() -> _GenericType.print(lambdaGT)));

        AtomicValue resolvedAv = (AtomicValue) ((AtomicValue) av._copy())
                ._value(lambda._classifierGenericType((GenericTypeValue) lambdaGT))
                ._genericType(lambdaGT);

        // Cache the body resolution outcome including any errors fired. Cache key captures
        // (sourceInfo, paramShape, openVar-types) — fully determines body resolution given
        // the immutable AST + read-only metadata. Errors are replayed on hit to preserve
        // caller error-count delta.
        if (cacheKey != null)
        {
            org.eclipse.collections.api.list.MutableList<CompilationError> errorsFired =
                    context.snapshotErrorsFrom(errorCheckpointForCache);
            plcc.lambdaResolutionCache().put(cacheKey,
                    new PureLanguageCompilerContext.CachedLambdaOutcome(resolvedAv, errorsFired.toImmutable()));
        }
        return resolvedAv;
    }

    private static @Nullable String buildLambdaCacheKey(AtomicValue av, MutableList<VariableExpression> params,
                                                        MutableList<VariableExpression> openVars, PureLanguageCompilerContext plcc)
    {
        meta.pure.metamodel.SourceInformation si = av._sourceInformation();
        if (si == null)
        {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        sb.append(si._sourceId() != null ? si._sourceId() : "?");
        sb.append(':').append(si._startLine())
          .append(':').append(si._startColumn())
          .append('-').append(si._endLine())
          .append(':').append(si._endColumn())
          .append("||");
        for (int i = 0; i < params.size(); i++)
        {
            if (i > 0) sb.append('|');
            VariableExpression p = params.get(i);
            sb.append(_GenericType.print(p._genericType()));
            sb.append(':');
            sb.append(_Multiplicity.print(p._multiplicity()));
        }
        // Open-variable types: look each up in current scope, sort by name for determinism.
        if (openVars != null && !openVars.isEmpty())
        {
            sb.append("||");
            MutableList<VariableExpression> sorted = Lists.mutable.withAll(openVars).sortThisBy(VariableExpression::_name);
            for (int i = 0; i < sorted.size(); i++)
            {
                if (i > 0) sb.append('|');
                String name = sorted.get(i)._name();
                sb.append(name).append('=');
                VariableExpression resolved = plcc.resolveVariable(name);
                if (resolved != null)
                {
                    sb.append(_GenericType.print(resolved._genericType())).append(':');
                    sb.append(_Multiplicity.print(resolved._multiplicity()));
                }
                else
                {
                    sb.append('?');
                }
            }
        }
        return sb.toString();
    }

    /**
     * Walk a lambda's unresolved expressionSequence to collect variable references whose
     * names aren't in the lambda's own parameter list. Result identifies external (open)
     * variables — purely structural, no resolution needed.
     */
    private static MutableList<VariableExpression> computeOpenVariablesStructurally(LambdaFunction lambda)
    {
        MutableSet<String> paramNames = lambda._parameters() != null
                ? lambda._parameters().collect(VariableExpression::_name).toSet()
                : Sets.mutable.empty();
        MutableList<VariableExpression> openVars = Lists.mutable.empty();
        MutableSet<String> seenNames = Sets.mutable.empty();
        if (lambda._expressionSequence() != null)
        {
            lambda._expressionSequence().forEach(vs -> collectVariableReferences(vs, paramNames, seenNames, openVars));
        }
        return openVars;
    }


    /**
     * Recursively collect VariableExpression references from a value specification tree,
     * filtering to those not in the given parameter names (i.e., open variables).
     */
    private static void collectVariableReferences(
            ValueSpecification vs,
            MutableSet<String> paramNames,
            MutableSet<String> seenNames,
            MutableList<VariableExpression> result)
    {
        if (vs instanceof VariableExpression varExpr)
        {
            String name = varExpr._name();
            // `~` (and `~.~`, ...) is a construction parent-reference, resolved at
            // runtime against the enclosing `^X(...)` via the construction stack —
            // never a captured free variable, so it must not be an open variable.
            if (!paramNames.contains(name) && !name.startsWith("~") && seenNames.add(name))
            {
                result.add(varExpr);
            }
        }
        else if (vs instanceof FunctionExpression fe && fe._parametersValues() != null)
        {
            fe._parametersValues().forEach(pv -> collectVariableReferences(pv, paramNames, seenNames, result));
        }
        else if (vs instanceof AtomicValue av && av._value() instanceof LambdaFunction innerLambda)
        {
            // Recurse into nested lambda bodies, but exclude the inner lambda's own params
            MutableSet<String> innerParamNames = org.eclipse.collections.impl.factory.Sets.mutable.withAll(paramNames);
            if (innerLambda._parameters() != null)
            {
                innerLambda._parameters().forEach(p -> innerParamNames.add(p._name()));
            }
            if (innerLambda._expressionSequence() != null)
            {
                innerLambda._expressionSequence().forEach(inner -> collectVariableReferences(inner, innerParamNames, seenNames, result));
            }
        }
        else if (vs instanceof meta.pure.metamodel.valuespecification.Collection col && col._values() != null)
        {
            col._values().forEach(v -> collectVariableReferences(v, paramNames, seenNames, result));
        }
    }

}
