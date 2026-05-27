package org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.resolution.valueSpecification.functionExpressionResolver;

import meta.pure.metamodel.function.property.AbstractProperty;
import meta.pure.metamodel.multiplicity.Multiplicity;
import meta.pure.metamodel.relation.Column;
import meta.pure.metamodel.type.FunctionType;
import meta.pure.metamodel.type.Type;
import meta.pure.metamodel.type.generics.GenericType;
import meta.pure.metamodel.valuespecification.DotApplication;
import meta.pure.metamodel.valuespecification.FunctionApplication;
import meta.pure.metamodel.valuespecification.FunctionExpression;
import meta.pure.metamodel.valuespecification.ValueSpecification;
import org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._Class;
import org.finos.legend.pure.m3.module.MetadataAccess;
import org.finos.legend.pure.m3.module.localModule.topLevel.CompilationContext;
import org.finos.legend.pure.m3.module.localModule.topLevel.CompilationError;
import meta.pure.metamodel.type.generics.CompilerNotSetGenericType;
import meta.pure.metamodel.type.generics.CompilerNotSetGenericTypeImpl;
import meta.pure.metamodel.multiplicity.CompilerNotSetMultiplicity;
import meta.pure.metamodel.multiplicity.CompilerNotSetMultiplicityImpl;
import org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.FunctionCallParametersBinding;
import org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.ParametersBinding;
import org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.PureLanguageCompilerContext;
import org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._Function;
import org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._FunctionExpression;
import org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._GenericType;
import org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._Multiplicity;
import org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._VariableExpression;
import org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.resolution.valueSpecification.ValueSpecificationResolver;
import org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.resolution.valueSpecification.functionExpressionResolver.functionSpecific.NewResolver;
import meta.pure.metamodel.valuespecification.AtomicValueImpl;
import meta.pure.metamodel.valuespecification.Collection;
import meta.pure.metamodel.valuespecification.FunctionInvocationImpl;
import meta.pure.metamodel.valuespecification.VariableExpression;
import meta.pure.metamodel.function.LambdaFunctionImpl;
import org.eclipse.collections.api.factory.Lists;
import org.eclipse.collections.api.list.MutableList;

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
        // Auto-map rewrite for `^expr(keys)` when the receiver's resolved
        // multiplicity is not [1]: convert to `expr->map(_automap | copy($_automap, [keys]))`
        // so the native `copy(T[1], KeyExpression[*]):T[1]` signature applies
        // per-element and the outer return type matches the receiver's
        // multiplicity ([0..1] or [*]).
        ValueSpecification autoMapResult = maybeRewriteCopyForCollection(expr, model, context);
        if (autoMapResult != null)
        {
            context.debugDepthDec();
            return autoMapResult;
        }
        // Topo-sort `new()`/`copy()` key expressions when one slot reads another
        // via `~.foo`: the runtime sets each slot value as it walks the
        // collection, so a `~.foo` reader must be ordered AFTER its `foo`
        // producer. Detects cycles and adds a compilation error.
        expr = reorderKeyExpressionsForParentReferences(expr, context);
        // Parent-reference (`~`) typing: when resolving `^X(...)` (new) or
        // `^$x(...)` (copy), push the construction's GenericType so any `~`
        // VariableExpression that appears inside the key expressions resolves
        // against the right enclosing type. Pop after the call's args have
        // been walked. Skipped (push null) when the type can't be extracted
        // at this point — fixpoint will catch it on a later iteration.
        PureLanguageCompilerContext plctx = context.compilerContextExtensions(PureLanguageCompilerContext.class);
        GenericType ctorType = extractConstructionType(expr, plctx);
        boolean pushed = false;
        if (ctorType != null)
        {
            plctx.pushConstructionType(ctorType);
            pushed = true;
        }
        try
        {
            if (expr instanceof DotApplication dotApplication && expr._parametersValues().notEmpty())
            {
                int checkpoint = context.currentErrorCount();
                ValueSpecification dotResult = DotApplicationResolver.resolveDotApplication(dotApplication, model, context);
                // Enum value resolution returns an AtomicValue, not a FunctionExpression
                if (dotResult instanceof FunctionExpression fe)
                {
                    return finalizeFunctionExpression(fe, checkpoint, model, context);
                }
                return dotResult;
            }
            else
            {
                int checkpoint = context.currentErrorCount();
                FunctionExpression expression = FunctionApplicationResolver.resolveFunctionApplication((FunctionApplication) expr, model, context);
                return finalizeFunctionExpression(expression, checkpoint, model, context);
            }
        }
        finally
        {
            if (pushed)
            {
                plctx.popConstructionType();
            }
            context.debugDepthDec();
        }
    }

    /**
     * If {@code expr} is a {@code copy(receiver, [keys])} call whose receiver's
     * resolved multiplicity is not exactly [1], rewrite it as
     * {@code receiver->map(_automap | copy($_automap, [keys]))} and return the
     * resolved rewrite. Otherwise returns {@code null} so the caller falls
     * through to standard resolution.
     *
     * <p>The native {@code copy(T[1], KeyExpression[*]):T[1]} signature requires
     * a single-cardinality receiver, so without this rewrite the function
     * resolver would emit "No matching function 'copy' found for argument types
     * (T[0..1], ...)".</p>
     *
     * <p>The pre-resolution of the receiver here gets re-done by the standard
     * path when no rewrite fires — accepted as a small cost since the alternative
     * (threading the pre-resolved receiver through) would complicate every
     * other code path.</p>
     */
    private static ValueSpecification maybeRewriteCopyForCollection(FunctionExpression expr, MetadataAccess model, CompilationContext context)
    {
        if (!"copy".equals(expr._functionName())
                || expr._parametersValues() == null
                || expr._parametersValues().isEmpty())
        {
            return null;
        }
        ValueSpecification receiver = expr._parametersValues().getFirst();
        ValueSpecification resolvedReceiver = ValueSpecificationResolver.resolve(receiver, model, context);
        if (resolvedReceiver == null) return null;
        Multiplicity mul = resolvedReceiver._multiplicity();
        if (mul == null || mul instanceof CompilerNotSetMultiplicity) return null;
        long lower = _Multiplicity.lowerBound(mul);
        Long upper = _Multiplicity.upperBound(mul);
        if (lower == 1L && upper != null && upper == 1L) return null;
        return buildCopyAutomap(expr, resolvedReceiver, model, context);
    }

    /**
     * Build {@code receiver->map({_automap:T[1] | copy($_automap, keys)})} and
     * resolve it. {@code keys} is the original {@code Collection<KeyExpression>}
     * from the source {@code copy(...)} call; we hand it to the inner copy
     * verbatim so any {@code ~}/{@code ~.~} parent references in key values
     * still resolve against the element-level construction.
     */
    private static ValueSpecification buildCopyAutomap(FunctionExpression expr, ValueSpecification resolvedReceiver, MetadataAccess model, CompilationContext context)
    {
        Multiplicity pureOne = (Multiplicity) model.getElement("meta::pure::metamodel::multiplicity::PureOne");
        // Lambda param: _automap : ReceiverElementType[1] — uses the receiver's
        // element-level genericType so the inner copy's signature match resolves on `T[1]`.
        VariableExpression lambdaParam = _VariableExpression.newVariableExpression(model)
                ._name("_automap")
                ._genericType(resolvedReceiver._genericType())
                ._multiplicity(pureOne);
        // Lambda body: copy($_automap, [keys]). The inner var ref is left
        // unresolved (no genericType/multiplicity) so the resolveFunctionExpression
        // call below binds it via the lambda's introduced scope, matching how
        // DotApplicationResolver's automap path constructs its body.
        VariableExpression varRef = _VariableExpression.newVariableExpression(model)
                ._name("_automap");
        MutableList<ValueSpecification> innerParams = Lists.mutable.with((ValueSpecification) varRef);
        if (expr._parametersValues().size() >= 2)
        {
            innerParams.add(expr._parametersValues().get(1));
        }
        FunctionInvocationImpl innerCopy = new FunctionInvocationImpl(model);
        innerCopy._functionName("copy");
        innerCopy._parametersValues(innerParams);
        innerCopy._sourceInformation(expr._sourceInformation());
        // Build lambda — classifierGenericType filled by later inference.
        LambdaFunctionImpl lambda = new LambdaFunctionImpl();
        lambda._classifierGenericType(_GenericType.buildUserDefinedGenericType(
                (meta.pure.metamodel.type.Type) model.getElement("meta::pure::metamodel::function::LambdaFunction"),
                model));
        lambda._parameters(Lists.mutable.with(lambdaParam));
        lambda._expressionSequence(Lists.mutable.with(innerCopy));
        // Wrap lambda in AtomicValue
        AtomicValueImpl lambdaAV = new AtomicValueImpl(model);
        lambdaAV._value(lambda);
        lambdaAV._multiplicity(pureOne);
        // Build map FunctionInvocation
        FunctionInvocationImpl mapExpr = new FunctionInvocationImpl(model);
        mapExpr._functionName("map");
        mapExpr._parametersValues(Lists.mutable.with(resolvedReceiver, lambdaAV));
        mapExpr._sourceInformation(expr._sourceInformation());
        return ValueSpecificationResolver.resolve(mapExpr, model, context);
    }

    /**
     * For `new(GTMH, KeyExpression[*])` or `copy(T, KeyExpression[*])`, return
     * the GenericType of the instance being constructed. Used to push a
     * scope for `~` VariableExpression resolution. Returns null when the
     * type can't yet be read off the call's first argument (the fixpoint
     * resolver will pick it up on a later pass).
     */
    private static GenericType extractConstructionType(FunctionExpression expr, PureLanguageCompilerContext plctx)
    {
        if (expr._functionName() == null || expr._parametersValues() == null || expr._parametersValues().isEmpty())
        {
            return null;
        }
        if ("new".equals(expr._functionName()))
        {
            ValueSpecification firstArg = expr._parametersValues().getFirst();
            if (firstArg instanceof meta.pure.metamodel.valuespecification.GenericTypeAndMultiplicityHolder gtmh
                    && gtmh._genericType() != null)
            {
                GenericType gt = gtmh._genericType();
                org.eclipse.collections.api.list.MutableList<? extends GenericType> typeArgs = _GenericType.typeArguments(gt);
                if (typeArgs != null && !typeArgs.isEmpty())
                {
                    return typeArgs.getFirst();
                }
            }
            return null;
        }
        if ("copy".equals(expr._functionName()))
        {
            ValueSpecification firstArg = expr._parametersValues().getFirst();
            // The receiver's type is what we want, but it may not be set yet
            // until after the first resolver pass — return null and let the
            // fixpoint try again.
            if (firstArg._genericType() != null && !(firstArg._genericType() instanceof CompilerNotSetGenericType))
            {
                return firstArg._genericType();
            }
            // Best effort for `^$x(...)`: look the variable up in scope by name.
            if (firstArg instanceof meta.pure.metamodel.valuespecification.VariableExpression ve)
            {
                meta.pure.metamodel.valuespecification.VariableExpression match = plctx.resolveVariable(ve._name());
                if (match != null && match._genericType() != null
                        && !(match._genericType() instanceof CompilerNotSetGenericType))
                {
                    return match._genericType();
                }
            }
            return null;
        }
        return null;
    }

    /**
     * For `new(GTMH, [keyExpression('a', valA), keyExpression('b', valB), …])`
     * (and the same for `copy`), reorder the key-expression collection so any
     * `~.foo` access in a value lands AFTER its producer in the iteration
     * order — the runtime sets each slot as it walks. Detects circular
     * dependencies (`a = ~.b, b = ~.a`) and emits a compilation error.
     */
    private static FunctionExpression reorderKeyExpressionsForParentReferences(FunctionExpression expr, CompilationContext context)
    {
        if (expr._functionName() == null) return expr;
        if (!"new".equals(expr._functionName()) && !"copy".equals(expr._functionName())) return expr;
        if (expr._parametersValues() == null || expr._parametersValues().size() < 2) return expr;
        ValueSpecification keyExprsArg = expr._parametersValues().get(1);
        if (!(keyExprsArg instanceof meta.pure.metamodel.valuespecification.Collection col)) return expr;
        org.eclipse.collections.api.list.MutableList<ValueSpecification> items = col._values();
        if (items == null || items.size() <= 1) return expr;

        int n = items.size();
        String[] names = new String[n];
        for (int i = 0; i < n; i++)
        {
            names[i] = extractKeyExpressionName(items.get(i));
        }

        java.util.Map<String, Integer> nameToIdx = new java.util.LinkedHashMap<>();
        for (int i = 0; i < n; i++)
        {
            if (names[i] != null && !nameToIdx.containsKey(names[i])) nameToIdx.put(names[i], i);
        }

        java.util.List<java.util.Set<Integer>> deps = new java.util.ArrayList<>(n);
        for (int i = 0; i < n; i++) deps.add(new java.util.LinkedHashSet<>());
        boolean anyDep = false;
        for (int i = 0; i < n; i++)
        {
            ValueSpecification keVS = items.get(i);
            if (!(keVS instanceof FunctionApplication fi)) continue;
            if (fi._parametersValues() == null || fi._parametersValues().size() < 2) continue;
            java.util.Set<String> siblingDeps = new java.util.LinkedHashSet<>();
            collectParentReferenceSiblings(fi._parametersValues().get(1), 0, siblingDeps);
            for (String name : siblingDeps)
            {
                Integer depIdx = nameToIdx.get(name);
                if (depIdx != null && depIdx != i)
                {
                    deps.get(i).add(depIdx);
                    anyDep = true;
                }
            }
        }
        if (!anyDep) return expr;

        int[] state = new int[n];
        java.util.List<Integer> order = new java.util.ArrayList<>(n);
        java.util.List<Integer> path = new java.util.ArrayList<>();
        for (int i = 0; i < n; i++)
        {
            if (state[i] == 0 && !topoVisit(i, deps, state, order, path, names, expr, context))
            {
                return expr;
            }
        }
        if (order.size() != n)
        {
            return expr;
        }

        org.eclipse.collections.api.list.MutableList<ValueSpecification> sorted =
                org.eclipse.collections.api.factory.Lists.mutable.empty();
        for (int idx : order) sorted.add(items.get(idx));

        meta.pure.metamodel.valuespecification.Collection newCol =
                (meta.pure.metamodel.valuespecification.Collection) ((meta.pure.metamodel.valuespecification.Collection) col)._copy();
        ((meta.pure.metamodel.valuespecification.CollectionImpl) newCol)._values(sorted);

        org.eclipse.collections.api.list.MutableList<ValueSpecification> newParams =
                org.eclipse.collections.api.factory.Lists.mutable.empty();
        newParams.add(expr._parametersValues().get(0));
        newParams.add((ValueSpecification) newCol);
        for (int i = 2; i < expr._parametersValues().size(); i++)
        {
            newParams.add(expr._parametersValues().get(i));
        }
        return (FunctionExpression) ((FunctionExpression) expr._copy())._parametersValues(newParams);
    }

    private static String extractKeyExpressionName(ValueSpecification keVS)
    {
        if (!(keVS instanceof FunctionApplication fi)) return null;
        if (!"keyExpression".equals(fi._functionName())) return null;
        if (fi._parametersValues() == null || fi._parametersValues().isEmpty()) return null;
        ValueSpecification nameVS = fi._parametersValues().getFirst();
        if (nameVS instanceof meta.pure.metamodel.valuespecification.AtomicValue av
                && av._value() instanceof String s) return s;
        return null;
    }

    /**
     * Walk {@code vs} collecting every property name accessed as {@code ~.foo}
     * at the SAME construction level (i.e. inside the current `^X(...)`, not
     * inside a nested `^Y(...)`). Tracks the construction-nesting depth and
     * the tilde count of each VariableExpression — only {@code ~} at the
     * current construction (1 tilde, depth 0) counts as a sibling
     * dependency. {@code ~.~.foo} (2 tildes) references a parent and is
     * skipped.
     */
    private static void collectParentReferenceSiblings(ValueSpecification vs, int constructionDepth, java.util.Set<String> out)
    {
        if (vs == null) return;
        if (vs instanceof meta.pure.metamodel.valuespecification.DotApplication da
                && da._parametersValues() != null && !da._parametersValues().isEmpty())
        {
            ValueSpecification receiver = da._parametersValues().getFirst();
            if (receiver instanceof meta.pure.metamodel.valuespecification.VariableExpression ve
                    && ve._name() != null
                    && org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.PureLanguageCompilerContext.parentReferenceTildeCount(ve._name()) == 1
                    && constructionDepth == 0)
            {
                out.add(da._functionName());
            }
            for (ValueSpecification arg : da._parametersValues())
            {
                collectParentReferenceSiblings(arg, constructionDepth, out);
            }
            return;
        }
        if (vs instanceof FunctionApplication fi)
        {
            int childDepth = constructionDepth
                    + (("new".equals(fi._functionName()) || "copy".equals(fi._functionName())) ? 1 : 0);
            if (fi._parametersValues() != null)
            {
                for (ValueSpecification arg : fi._parametersValues())
                {
                    collectParentReferenceSiblings(arg, childDepth, out);
                }
            }
            return;
        }
        if (vs instanceof meta.pure.metamodel.valuespecification.Collection col && col._values() != null)
        {
            for (ValueSpecification item : col._values())
            {
                collectParentReferenceSiblings(item, constructionDepth, out);
            }
        }
    }

    /** DFS topological visit. Returns false on cycle (and reports the cycle as a compilation error). */
    private static boolean topoVisit(int i,
                                     java.util.List<java.util.Set<Integer>> deps,
                                     int[] state,
                                     java.util.List<Integer> order,
                                     java.util.List<Integer> path,
                                     String[] names,
                                     FunctionExpression expr,
                                     CompilationContext context)
    {
        if (state[i] == 1)
        {
            // Cycle: chain from where i first appears on the path through to
            // the current head, then back to i. `path` is the visit stack in
            // chronological order (path.get(0) was visited first).
            int startIdx = path.indexOf(i);
            StringBuilder cycle = new StringBuilder();
            for (int j = startIdx; j < path.size(); j++)
            {
                if (cycle.length() > 0) cycle.append(" -> ");
                cycle.append(names[path.get(j)] != null ? "'" + names[path.get(j)] + "'" : "?");
            }
            cycle.append(" -> ").append(names[i] != null ? "'" + names[i] + "'" : "?");
            context.addError(new CompilationError(
                    "Circular parent-reference dependency in key expressions: " + cycle,
                    expr._sourceInformation()));
            return false;
        }
        if (state[i] == 2) return true;
        state[i] = 1;
        path.add(i);
        for (int dep : deps.get(i))
        {
            if (!topoVisit(dep, deps, state, order, path, names, expr, context)) return false;
        }
        path.remove(path.size() - 1);
        state[i] = 2;
        order.add(i);
        return true;
    }

    public static FunctionExpression finalizeFunctionExpression(FunctionExpression resolved, int errorCheckpoint, MetadataAccess model, CompilationContext context)
    {
        if (resolved._func() != null)
        {
            ParametersBinding bindings = _FunctionExpression.extractResolvedParametersBinding(resolved);
            // For class properties, enrich bindings with class type parameter bindings
            // from the receiver's generic type so that T resolves to the concrete type argument.
            boolean isProperty = resolved._func() instanceof AbstractProperty || resolved._func() instanceof Column;
            boolean classBindingsApplied = false;
            if (isProperty
                    && resolved._parametersValues() != null && resolved._parametersValues().notEmpty())
            {
                ValueSpecification receiver = resolved._parametersValues().getFirst();
                if (receiver._genericType() != null && !(receiver._genericType() instanceof CompilerNotSetGenericType))
                {
                    Type ownerType = _GenericType.type(receiver._genericType());
                    if (ownerType instanceof meta.pure.metamodel.type.Class cls)
                    {
                        ParametersBinding classBindings = _Class.buildBindingsFromGenericType(cls, receiver._genericType());
                        classBindings.typeBindings().forEachKeyValue((k, v) ->
                                bindings.typeBindings().computeIfAbsent(k, x -> org.eclipse.collections.impl.factory.Lists.mutable.empty()).addAll(v));
                        classBindings.multiplicityBindings().forEachKeyValue((k, v) ->
                                bindings.multiplicityBindings().computeIfAbsent(k, x -> org.eclipse.collections.impl.factory.Lists.mutable.empty()).addAll(v));
                        classBindingsApplied = true;
                    }
                }
            }
            FunctionType ft = _Function.getFunctionType(resolved._func(), model);
            // For non-class properties (e.g., Column on RelationType) where DotApplicationResolver
            // already set the resolved type, preserve it rather than overwriting with unresolved template.
            GenericType returnGT;
            Multiplicity returnMul;
            if (isProperty && !classBindingsApplied
                    && resolved._genericType() != null && !(resolved._genericType() instanceof CompilerNotSetGenericType))
            {
                returnGT = _GenericType.asInferred(resolved._genericType(), model);
                returnMul = _Multiplicity.asInferred(resolved._multiplicity(), model);
            }
            else
            {
                returnGT = _GenericType.asInferred(_GenericType.makeAsConcreteAsPossible(ft._returnType(), bindings, model), model);
                returnMul = _Multiplicity.asInferred(_Multiplicity.makeAsConcreteAsPossible(ft._returnMultiplicity(), bindings), model);
            }
            FunctionCallParametersBinding currentNode = context.compilerContextExtensions(PureLanguageCompilerContext.class).currentFunctionCallNode();
            context.debug("finalize: %s func=%s gt=%s mul=%s bindings=%s parentBindings=%s", resolved._functionName(), lazy(() -> CompilationContext.debugFunc(resolved._func())), lazy(() -> _GenericType.print(returnGT)), lazy(() -> _Multiplicity.print(returnMul)), bindings, lazy(() -> currentNode != null ? currentNode.printParentBindings() : "[]"));
            FunctionExpression updated = (FunctionExpression) ((FunctionExpression)resolved._copy())
                    ._genericType(returnGT)
                    ._multiplicity(returnMul);

            // Validate properties for new and copy expressions
            if (updated._functionName() != null)
            {
                if (updated._functionName().equals("new"))
                {
                    NewResolver.validateNewRequiredProperties(updated, model, context);
                }
                else if (updated._functionName().equals("copy"))
                {
                    NewResolver.validateCopyProperties(updated, model, context);
                }
            }
            registerLetVariable(updated, model, context);
            return updated;
        }
        // Parent-reference chain step (`~.~`, `~.~.~`, …): DotApplicationResolver
        // already stamped the GenericType + Multiplicity from the construction-
        // type stack lookup, but there's no `_func` to set — "~" isn't a real
        // function. Skip the unresolved-function error and return the typed
        // node unchanged.
        else if (resolved instanceof DotApplication da && "~".equals(da._functionName())
                && resolved._genericType() != null
                && !(resolved._genericType() instanceof CompilerNotSetGenericType))
        {
            return resolved;
        }
        else
        {
            FunctionExpression updated = (FunctionExpression) ((FunctionExpression) resolved._copy())
                    ._genericType(new CompilerNotSetGenericTypeImpl())
                    ._multiplicity(new CompilerNotSetMultiplicityImpl());
            // Only report if no specific error was already added by resolveFunctionApplication.
            // This avoids duplicate errors when e.g. "No matching function 'X' found" was already reported.
            if (context.currentErrorCount() == errorCheckpoint)
            {
                context.addError(new CompilationError(
                        "Can't resolve the function '" + resolved._functionName() + "'",
                        resolved._sourceInformation()));
            }
            return updated;
        }
    }
}