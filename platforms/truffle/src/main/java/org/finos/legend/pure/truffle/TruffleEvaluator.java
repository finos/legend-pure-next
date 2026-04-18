// Copyright 2024 Goldman Sachs
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package org.finos.legend.pure.truffle;

import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.frame.VirtualFrame;
import meta.pure.metamodel.function.FunctionDefinition;
import meta.pure.metamodel.function.LambdaFunction;
import meta.pure.metamodel.valuespecification.ValueSpecification;
import meta.pure.metamodel.valuespecification.VariableExpression;
import org.eclipse.collections.api.list.MutableList;
import org.finos.legend.pure.execution.NativeRepository;
import org.finos.legend.pure.execution.ValueSpecificationEvaluator;
import org.finos.legend.pure.execution._E_ValueSpecification;
import org.finos.legend.pure.truffle.ast.PureNode;
import org.finos.legend.pure.truffle.builder.PureASTBuilder;
import org.finos.legend.pure.truffle.frame.CompiledFunction;
import org.finos.legend.pure.truffle.frame.FrameDescriptorBuilder;
import org.finos.legend.pure.truffle.frame.FrameLayout;

import java.util.HashMap;
import java.util.List;
import java.util.WeakHashMap;

/**
 * A {@link ValueSpecificationEvaluator} subclass that routes single-expression
 * evaluation through a Truffle AST.
 *
 * <p>Phase B strategy: we override only {@link #evaluate(ValueSpecification)}.
 * Each incoming {@code ValueSpecification} is lowered to a Truffle AST
 * ({@link PureASTBuilder#lower}), wrapped in a {@link PureRootNode}, and
 * invoked via its {@link RootCallTarget}. Results are cached by VS identity
 * so a function body compiled as a Truffle AST is reused across calls.</p>
 *
 * <p>Other methods — {@code evaluateFunctionDefinition},
 * {@code executeFunction}, {@code pushScope}, etc. — are inherited unchanged
 * from the Java interpreter. Native functions that call back into this
 * evaluator end up executing ASTs via Truffle too because their internal
 * {@code eval.evaluate(...)} calls dispatch to this override.</p>
 */
public final class TruffleEvaluator extends ValueSpecificationEvaluator
{
    // Sentinel cached against a FunctionDefinition that analyzed as non-frame-eligible.
    // Avoids re-analyzing (and re-detecting lambdas) on every call.
    private static final CompiledFunction NOT_ELIGIBLE = new CompiledFunction(null);

    private final PureLanguage language;
    private final PureASTBuilder astBuilder;
    private final WeakHashMap<ValueSpecification, PureNode> cache = new WeakHashMap<>();
    private final WeakHashMap<FunctionDefinition, CompiledFunction> frameCache = new WeakHashMap<>();
    // Per-lambda RootCallTarget. Shared across every call site that
    // dispatches to the same {@link LambdaFunction}, so a single Truffle
    // compilation is reused by many inline caches. {@code null} marks a
    // lambda whose body couldn't be lowered (fallback to Java eval).
    private final WeakHashMap<LambdaFunction, RootCallTarget> lambdaCallTargets = new WeakHashMap<>();
    private static final RootCallTarget LAMBDA_NOT_COMPILABLE = null; // sentinel value for misses

    // Frame of the innermost frame-eligible FD currently executing. Bridged
    // natives that re-enter {@link #evaluate(ValueSpecification)} for a
    // sub-expression execute that sub-expression against this frame so
    // {@link org.finos.legend.pure.truffle.ast.FrameVariableReadNode} sees
    // the enclosing parameters and let-bindings. null means "no frame in
    // scope" (top-level call, or inside a non-frame-eligible FD).
    private VirtualFrame currentFrame;

    public TruffleEvaluator(NativeRepository natives, PureLanguage language)
    {
        super(natives);
        this.language = language;
        org.finos.legend.pure.truffle.builder.NativeNodeRegistry registry =
                org.finos.legend.pure.truffle.builder.NativeNodeRegistry.empty();
        org.finos.legend.pure.truffle.ast.natives.math.MathNodeFactories.registerAll(registry);
        org.finos.legend.pure.truffle.ast.natives.boolean_.BooleanNodeFactories.registerAll(registry);
        org.finos.legend.pure.truffle.ast.natives.collection.CollectionNodeFactories.registerAll(registry);
        org.finos.legend.pure.truffle.ast.natives.string.StringNodeFactories.registerAll(registry);
        org.finos.legend.pure.truffle.ast.natives.lang.LangNodeFactories.registerAll(registry);
        org.finos.legend.pure.truffle.ast.natives.meta.MetaNodeFactories.registerAll(registry);
        org.finos.legend.pure.truffle.ast.natives.date.DateNodeFactories.registerAll(registry);
        org.finos.legend.pure.truffle.ast.natives.io.IONodeFactories.registerAll(registry);
        org.finos.legend.pure.truffle.ast.natives.assert_.AssertNodeFactories.registerAll(registry);
        this.astBuilder = new PureASTBuilder(natives, registry);
    }

    public PureASTBuilder astBuilder()
    {
        return astBuilder;
    }

    @Override
    public ValueSpecification evaluate(ValueSpecification vs)
    {
        PureNode body = getBody(vs);
        // currentFrame is null outside frame-eligible FDs (HashMap path) and
        // the active frame inside one. Frame-aware nodes use it; others ignore.
        Object result = body.executeGeneric(currentFrame);
        return org.finos.legend.pure.truffle.types.ValueAdapter.ensureVS(result);
    }

    /**
     * Delegate to the Java tree-walking evaluator for a single {@link ValueSpecification}.
     *
     * <p>Used by Truffle nodes that haven't been fully ported (e.g., property
     * access) — they emit a node that calls this, which re-enters the Java
     * {@code evaluateFunctionExpression}/{@code evaluatePropertyFunc} logic.
     * Recursive {@code evaluate(arg)} calls from Java natives still hit the
     * Truffle-overridden {@link #evaluate} for leaf expressions.</p>
     */
    public ValueSpecification javaEvaluate(ValueSpecification vs)
    {
        return super.evaluate(vs);
    }

    /**
     * Frame-path override for {@link FunctionDefinition} invocation. If the FD
     * is frame-eligible (no lambdas in its expression tree, not itself a
     * LambdaFunction) and has not been disabled by a prior compile failure,
     * execute its body against a fresh {@link VirtualFrame} with typed
     * parameter slots. Otherwise fall back to the HashMap-scope path in
     * {@link ValueSpecificationEvaluator#evaluateFunctionDefinition}.
     */
    @Override
    public ValueSpecification evaluateFunctionDefinition(FunctionDefinition fd, List<ValueSpecification> args)
    {
        if (fd == null)
        {
            return super.evaluateFunctionDefinition(fd, args);
        }
        // QualifiedProperty dispatch — when Java's evaluatePropertyFunc
        // would have both (a) resolved the right overload via C3
        // linearization and (b) bound the target class's type variables
        // into scope (see ValueSpecificationEvaluator.evaluatePropertyFunc),
        // do both here so the frame path picks the same QP and sees the
        // same variables.
        FunctionDefinition resolved = fd;
        if (fd instanceof meta.pure.metamodel.function.property.QualifiedProperty qp && !args.isEmpty())
        {
            resolved = resolveQpDispatch(qp, args);
            bindQpTypeVariables(args.get(0));
        }
        FunctionDefinition finalFd = resolved;
        CompiledFunction cf = compile(finalFd);
        if (cf == NOT_ELIGIBLE)
        {
            // Non-eligible callees must not inherit the caller's layout —
            // their sub-expressions are lowered in *their* scope (HashMap),
            // not the outer FD's slot map. Clear the layout / frame for
            // the duration of the fallback call.
            return callWithoutFrame(() -> super.evaluateFunctionDefinition(finalFd, args));
        }
        return executeWithFrame(finalFd, cf, args);
    }

    /**
     * Mirrors {@code ValueSpecificationEvaluator.resolveQualifiedPropertyDispatch}:
     * walk the target's C3 linearization and pick the most-specific QP
     * that matches the call's name + arity. {@code DotApplication#_func()}
     * picks by name only, so a call like {@code .res('z')} may static-resolve
     * to the 0-arg {@code res()} overload and silently drop the extra arg.
     */
    private FunctionDefinition resolveQpDispatch(
            meta.pure.metamodel.function.property.QualifiedProperty staticQp,
            List<ValueSpecification> args)
    {
        if (args.isEmpty())
        {
            return staticQp;
        }
        Object target = org.finos.legend.pure.execution._E_ValueSpecification.unwrap(args.get(0));
        if (!(target instanceof org.finos.legend.pure.execution.DynamicInstance di)
                || di.getClassifierGenericType() == null)
        {
            return staticQp;
        }
        meta.pure.metamodel.type.Type runtimeType =
                org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._GenericType.type(di.getClassifierGenericType());
        if (runtimeType == null)
        {
            return staticQp;
        }
        String qpName = staticQp._name();
        int argCount = args.size();
        org.eclipse.collections.api.list.MutableList<meta.pure.metamodel.type.Type> mro =
                org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._Type.linearize(
                        runtimeType, natives().resolver());
        for (meta.pure.metamodel.type.Type type : mro)
        {
            if (type instanceof meta.pure.metamodel.type.Class cls && cls._qualifiedProperties() != null)
            {
                for (meta.pure.metamodel.function.property.QualifiedProperty candidate : cls._qualifiedProperties())
                {
                    if (qpName.equals(candidate._name())
                            && candidate._parameters() != null
                            && candidate._parameters().size() == argCount)
                    {
                        return candidate;
                    }
                }
            }
        }
        return staticQp;
    }

    /**
     * Mirrors the type-variable-binding block of
     * {@link org.finos.legend.pure.execution.ValueSpecificationEvaluator#evaluatePropertyFunc}
     * so Pure QualifiedProperty bodies see class-level type variables as
     * scope bindings. Writes into {@code varStack.peek()} — frame-path
     * {@code executeWithFrame} will copy that scope when it pushes.
     */
    private void bindQpTypeVariables(ValueSpecification targetArg)
    {
        Object target = org.finos.legend.pure.execution._E_ValueSpecification.unwrap(targetArg);
        if (!(target instanceof org.finos.legend.pure.execution.DynamicInstance di))
        {
            return;
        }
        meta.pure.metamodel.type.generics.GenericType cgt = di.getClassifierGenericType();
        if (!(cgt instanceof meta.pure.metamodel.type.generics.GenericTypeValue gtv)
                || gtv._typeVariableValues() == null || gtv._typeVariableValues().isEmpty())
        {
            return;
        }
        meta.pure.metamodel.type.Type ownerType =
                org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._GenericType.type(cgt);
        org.eclipse.collections.api.list.MutableList<VariableExpression> typeVars = null;
        if (ownerType instanceof meta.pure.metamodel.type.Class cls)
        {
            typeVars = cls._typeVariables();
        }
        else if (ownerType instanceof meta.pure.metamodel.type.PrimitiveType pt)
        {
            typeVars = pt._typeVariables();
        }
        if (typeVars == null || typeVars.isEmpty())
        {
            return;
        }
        org.eclipse.collections.api.list.MutableList<ValueSpecification> typeVarVals = gtv._typeVariableValues();
        int count = Math.min(typeVars.size(), typeVarVals.size());
        for (int i = 0; i < count; i++)
        {
            currentScope().put(typeVars.get(i)._name(), typeVarVals.get(i));
        }
    }

    /**
     * Run a callback with the frame-path context suspended. Restores on exit.
     * Used when we fall back to the HashMap path for a non-frame-eligible
     * callee so its sub-expressions don't inherit the caller's slot layout.
     */
    private ValueSpecification callWithoutFrame(java.util.function.Supplier<ValueSpecification> body)
    {
        if (currentFrame == null && !astBuilder.hasCurrentLayout())
        {
            return body.get();
        }
        FrameLayout prevLayout = astBuilder.pushLayout(null);
        VirtualFrame prevFrame = currentFrame;
        currentFrame = null;
        try
        {
            return body.get();
        }
        finally
        {
            currentFrame = prevFrame;
            astBuilder.popLayout(prevLayout);
        }
    }

    private CompiledFunction compile(FunctionDefinition fd)
    {
        CompiledFunction cached = frameCache.get(fd);
        if (cached != null)
        {
            return cached;
        }
        FrameLayout layout = FrameDescriptorBuilder.analyze(fd);
        if (layout == null)
        {
            frameCache.put(fd, NOT_ELIGIBLE);
            return NOT_ELIGIBLE;
        }
        CompiledFunction cf = new CompiledFunction(layout);
        frameCache.put(fd, cf);
        return cf;
    }

    /**
     * Returns a Truffle {@code RootCallTarget} for {@code lambda}, compiling
     * its body on first use. Shared by every {@code LambdaCallNode} that
     * dispatches to this lambda so a single compilation is reused. Returns
     * {@code null} if the lambda's body can't be lowered, in which case the
     * caller should fall back to {@code eval.executeFunction}.
     */
    public RootCallTarget callTargetForLambda(LambdaFunction lambda)
    {
        RootCallTarget cached = lambdaCallTargets.get(lambda);
        if (cached != null || lambdaCallTargets.containsKey(lambda))
        {
            return cached;
        }
        RootCallTarget target = buildLambdaCallTarget(lambda);
        lambdaCallTargets.put(lambda, target);
        return target;
    }

    private RootCallTarget buildLambdaCallTarget(LambdaFunction lambda)
    {
        CompiledFunction cf = compile(lambda);
        if (cf == NOT_ELIGIBLE)
        {
            return LAMBDA_NOT_COMPILABLE;
        }
        // Lower the body under the lambda's layout so variable reads resolve
        // via slot loads rather than HashMap lookups. Lower errors (e.g. a
        // DotApplication whose _func() throws) fall through to the null
        // sentinel so the call site uses the Java-eval fallback.
        try
        {
            FrameLayout prevLayout = astBuilder.pushLayout(cf.layout());
            try
            {
                PureNode[] body = astBuilder.lowerBody(lambda._expressionSequence(), cf.layout());
                org.eclipse.collections.api.list.MutableList<VariableExpression> openVars = lambda._openVariables();
                String[] openVarNames;
                if (openVars == null || openVars.isEmpty())
                {
                    openVarNames = new String[0];
                }
                else
                {
                    openVarNames = new String[openVars.size()];
                    for (int i = 0; i < openVars.size(); i++)
                    {
                        openVarNames[i] = openVars.get(i)._name();
                    }
                }
                LambdaRootNode root = new LambdaRootNode(language, lambdaName(lambda), cf.layout(), openVarNames, body);
                return root.getCallTarget();
            }
            finally
            {
                astBuilder.popLayout(prevLayout);
            }
        }
        catch (RuntimeException e)
        {
            return LAMBDA_NOT_COMPILABLE;
        }
    }

    private static Object unwrapIfPrimitive(ValueSpecification vs)
    {
        if (vs instanceof meta.pure.metamodel.valuespecification.AtomicValue av)
        {
            Object inner = av._value();
            if (inner instanceof Long || inner instanceof Double || inner instanceof Boolean)
            {
                return inner;
            }
            // String: only unwrap if the VS's genericType is String.
            // Some values (dates, enums) are stored as Java Strings but
            // carry a non-String genericType that must be preserved.
            if (inner instanceof String && isStringGenericType(av))
            {
                return inner;
            }
        }
        return vs;
    }

    private static boolean isStringGenericType(meta.pure.metamodel.valuespecification.AtomicValue av)
    {
        meta.pure.metamodel.type.generics.GenericType gt = av._genericType();
        if (gt == null)
        {
            return true;
        }
        meta.pure.metamodel.type.Type type =
                org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._GenericType.type(gt);
        if (type instanceof meta.pure.metamodel.PackageableElement pe)
        {
            return "String".equals(pe._name());
        }
        return false;
    }

    private static String lambdaName(LambdaFunction lambda)
    {
        return "lambda@" + System.identityHashCode(lambda);
    }

    /**
     * Installs the lambda's layout/frame/scope context and delegates to its
     * body. Called from {@link LambdaRootNode#execute} after the RootNode
     * has bound parameters and open vars onto the frame's slots. Mirrors
     * {@link #executeWithFrame} but skips the param/open-var binding step
     * because the RootNode already did it.
     */
    public Object runLambdaBody(LambdaRootNode root, VirtualFrame frame)
    {
        FrameLayout prevLayout = astBuilder.pushLayout(root.layout());
        VirtualFrame prevFrame = currentFrame;
        currentFrame = frame;
        pushScope();
        try
        {
            return root.executeBody(frame);
        }
        finally
        {
            popScope();
            currentFrame = prevFrame;
            astBuilder.popLayout(prevLayout);
        }
    }

    private ValueSpecification executeWithFrame(FunctionDefinition fd, CompiledFunction cf, List<ValueSpecification> args)
    {
        VirtualFrame frame = Truffle.getRuntime().createVirtualFrame(new Object[0], cf.layout().descriptor());
        bindParameters(fd, cf.layout(), args, frame);
        // If the callee is a Closure (a LambdaFunction with captured open
        // vars), seed the layout's open-var slots from the captured scope.
        // For non-lambda FDs the captured scope is irrelevant — cf.layout()
        // won't contain open-var slots, so the write loop is a no-op.
        if (fd instanceof org.finos.legend.pure.execution.Closure closure)
        {
            bindOpenVariables(closure, cf.layout(), frame);
        }

        // Install this FD's context: the astBuilder's currentLayout drives
        // slot-based lowering for any expression lowered via evaluate(vs);
        // currentFrame makes each expression read the right frame. Both
        // are reentrant — a frame-eligible FD can call into another.
        FrameLayout prevLayout = astBuilder.pushLayout(cf.layout());
        VirtualFrame prevFrame = currentFrame;
        currentFrame = frame;
        // Native calls inside the body (via BridgedNativeCallNode →
        // eval.evaluate(expr)) expect the evaluator's varStack to have a
        // scope. Push a placeholder so nested evaluator APIs don't explode.
        pushScope();
        try
        {
            ValueSpecification result = null;
            // Lazily lower each top-level expression via evaluate(vs) so
            // we only touch FunctionExpressions we actually run (avoids
            // eagerly resolving DotApplication _func() whose lookup can
            // throw before the target is bound).
            for (ValueSpecification expr : fd._expressionSequence())
            {
                result = evaluate(expr);
            }
            return result;
        }
        finally
        {
            popScope();
            currentFrame = prevFrame;
            astBuilder.popLayout(prevLayout);
        }
    }

    private void bindOpenVariables(org.finos.legend.pure.execution.Closure closure, FrameLayout layout, VirtualFrame frame)
    {
        // Two sources feed open-var slots:
        // 1. closure.capturedScope() — lexical capture at definition site.
        // 2. the current HashMap scope — dynamic bindings injected by Java
        //    natives before invoking the lambda. MetaNatives.validateConstraints
        //    does this for class type variables, binding names like `x` into
        //    varStack.peek() before calling the constraint lambda. Those
        //    aren't in capturedScope (the lambda was defined at class-compile
        //    time, before any instance exists), but they must still reach
        //    the body's slot-based variable reads.
        // Lexical captures win over dynamic bindings for the same name.
        java.util.Map<String, ValueSpecification> captured = closure.capturedScope();
        java.util.Map<String, ValueSpecification> dynamic = currentScope();
        MutableList<VariableExpression> openVars = closure._openVariables();
        if (openVars == null)
        {
            return;
        }
        for (VariableExpression ov : openVars)
        {
            String name = ov._name();
            Integer slot = layout.slotFor(name);
            if (slot == null)
            {
                continue;
            }
            Object value = null;
            if (captured != null)
            {
                ValueSpecification vs = captured.get(name);
                if (vs != null)
                {
                    value = unwrapIfPrimitive(vs);
                }
            }
            if (value == null && dynamic != null)
            {
                ValueSpecification vs = dynamic.get(name);
                if (vs != null)
                {
                    value = unwrapIfPrimitive(vs);
                }
            }
            if (value != null)
            {
                frame.setObject(slot, value);
            }
        }
    }

    private void bindParameters(FunctionDefinition fd, FrameLayout layout, List<ValueSpecification> args, VirtualFrame frame)
    {
        MutableList<VariableExpression> params = fd._parameters();
        if (params == null)
        {
            return;
        }
        int[] paramSlots = layout.paramSlots();
        int count = Math.min(params.size(), args.size());
        for (int i = 0; i < count; i++)
        {
            ValueSpecification arg = args.get(i);
            // Store raw primitives directly in the slot so specialized
            // nodes (IntegerHelper.asLong etc.) skip the AtomicValue
            // unwrap. Non-primitive payloads (DynamicInstance, Closure,
            // LambdaFunction, Collection) stay as ValueSpecification
            // because downstream code casts them to VS directly.
            frame.setObject(paramSlots[i], unwrapIfPrimitive(arg));
        }
    }

    private PureNode getBody(ValueSpecification vs)
    {
        // Under an active frame layout, cached layoutless nodes would emit
        // HashMap VariableReadNode instead of FrameVariableReadNode for
        // locals in scope. Re-lower fresh so the sub-expression's VarReads
        // bind to the enclosing FD's frame slots.
        if (astBuilder.hasCurrentLayout())
        {
            return astBuilder.lower(vs);
        }
        PureNode cached = cache.get(vs);
        if (cached != null)
        {
            return cached;
        }
        PureNode body = astBuilder.lower(vs);
        cache.put(vs, body);
        return body;
    }
}
