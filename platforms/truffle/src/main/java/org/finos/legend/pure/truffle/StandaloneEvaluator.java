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
import meta.pure.metamodel.valuespecification.VariableExpression;
import org.eclipse.collections.api.list.MutableList;
import org.finos.legend.pure.m3.module.MetadataAccess;
import org.finos.legend.pure.truffle.ast.PureNode;
import org.finos.legend.pure.truffle.builder.NativeNodeRegistry;
import org.finos.legend.pure.truffle.builder.PureASTBuilder;
import org.finos.legend.pure.truffle.frame.CompiledFunction;
import org.finos.legend.pure.truffle.frame.FrameDescriptorBuilder;
import org.finos.legend.pure.truffle.frame.FrameLayout;
import org.finos.legend.pure.truffle.types.PureNull;
import org.finos.legend.pure.truffle.types.RawClosure;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.WeakHashMap;

/**
 * Standalone Pure interpreter — no {@code ValueSpecificationEvaluator},
 * no {@code NativeRepository}, no {@code ValueSpecification} types.
 *
 * <p>Every value flowing through this evaluator is a raw Java object:
 * {@code long}/{@code double}/{@code boolean}/{@code String} for
 * primitives, {@code PureSequence} for collections, generated Impl
 * classes for Pure class instances, {@code RawClosure} for lambdas,
 * {@code PureNull.INSTANCE} for empty.</p>
 *
 * <p>All scoping is frame-based ({@link VirtualFrame} with
 * {@link FrameLayout} slots). No HashMap {@code varStack}.</p>
 */
public final class StandaloneEvaluator
{
    private final MetadataAccess resolver;
    private final PureLanguage language;
    private final PureASTBuilder astBuilder;

    // Per-FD compilation cache: layout + lowered body
    private final WeakHashMap<FunctionDefinition, CompiledFunction> functionCache = new WeakHashMap<>();
    // Per-lambda RootCallTarget cache
    private final WeakHashMap<LambdaFunction, RootCallTarget> lambdaCache = new WeakHashMap<>();

    // Construction stack for new/copy (~) parent references
    private final Deque<Object> constructionStack = new ArrayDeque<>();

    // Current frame context (for sub-expression re-lowering under active layout)
    private VirtualFrame currentFrame;
    private FrameLayout currentLayout;

    public StandaloneEvaluator(MetadataAccess resolver, PureLanguage language,
                               NativeNodeRegistry registry,
                               org.finos.legend.pure.execution.NativeRepository nativesFallback)
    {
        this.resolver = resolver;
        this.language = language;
        // NativeRepository is needed for PureASTBuilder.isLazy() checks
        // and BridgedNativeCallNode fallback for the ~31 remaining bridge
        // signatures. Once all natives are specialized, this becomes null.
        this.astBuilder = new PureASTBuilder(nativesFallback, registry);
    }

    public MetadataAccess resolver()
    {
        return resolver;
    }

    // ---------------------------------------------------------------
    // Function execution — the main entry point
    // ---------------------------------------------------------------

    /**
     * Execute a compiled {@link FunctionDefinition} with raw args.
     * Returns a raw value (Long, String, PureSequence, etc.).
     */
    public Object executeFunction(FunctionDefinition fd, Object[] rawArgs)
    {
        CompiledFunction cf = compile(fd);
        FrameLayout layout = cf.layout();
        VirtualFrame frame = Truffle.getRuntime().createVirtualFrame(new Object[0], layout.descriptor());

        // Bind params
        MutableList<VariableExpression> params = fd._parameters();
        int[] paramSlots = layout.paramSlots();
        if (params != null)
        {
            int count = Math.min(params.size(), rawArgs.length);
            for (int i = 0; i < count; i++)
            {
                frame.setObject(paramSlots[i], rawArgs[i]);
            }
        }

        // QualifiedProperty type-variable binding
        if (fd instanceof meta.pure.metamodel.function.property.QualifiedProperty qp && rawArgs.length > 0)
        {
            fd = resolveQpDispatch(qp, rawArgs);
            bindQpTypeVariables(rawArgs[0], frame, layout);
        }

        return executeBody(fd, frame, layout);
    }

    /**
     * Execute a {@link RawClosure} with raw args.
     */
    public Object executeLambda(RawClosure closure, Object[] rawArgs)
    {
        if (closure.callTarget() != null)
        {
            // DirectCallNode path — args bundled as [closure, arg0, arg1, ...]
            Object[] callArgs = new Object[rawArgs.length + 1];
            callArgs[0] = closure;
            System.arraycopy(rawArgs, 0, callArgs, 1, rawArgs.length);
            return closure.callTarget().call(callArgs);
        }
        // Fallback: compile and execute inline
        return executeLambdaInline(closure, rawArgs);
    }

    private Object executeLambdaInline(RawClosure closure, Object[] rawArgs)
    {
        LambdaFunction lambda = closure.lambda();
        CompiledFunction cf = compile(lambda);
        FrameLayout layout = cf.layout();
        VirtualFrame frame = Truffle.getRuntime().createVirtualFrame(new Object[0], layout.descriptor());

        // Bind params
        int[] paramSlots = layout.paramSlots();
        MutableList<VariableExpression> params = lambda._parameters();
        if (params != null)
        {
            int count = Math.min(params.size(), rawArgs.length);
            for (int i = 0; i < count; i++)
            {
                frame.setObject(paramSlots[i], rawArgs[i]);
            }
        }

        // Bind captured values
        String[] capturedNames = closure.capturedNames();
        Object[] capturedValues = closure.capturedValues();
        for (int i = 0; i < capturedNames.length; i++)
        {
            Integer slot = layout.slotFor(capturedNames[i]);
            if (slot != null && capturedValues[i] != null)
            {
                frame.setObject(slot, capturedValues[i]);
            }
        }

        return executeBody(lambda, frame, layout);
    }

    // ---------------------------------------------------------------
    // Body execution
    // ---------------------------------------------------------------

    private Object executeBody(FunctionDefinition fd, VirtualFrame frame, FrameLayout layout)
    {
        FrameLayout prevLayout = this.currentLayout;
        VirtualFrame prevFrame = this.currentFrame;
        this.currentLayout = layout;
        this.currentFrame = frame;
        // Push layout onto the AST builder so VariableExpression lowers to
        // FrameVariableReadNode and letFunction lowers to FrameLetFunctionNode.
        FrameLayout prevBuilderLayout = astBuilder.pushLayout(layout);
        try
        {
            Object result = PureNull.INSTANCE;
            for (meta.pure.metamodel.valuespecification.ValueSpecification expr : fd._expressionSequence())
            {
                PureNode node = astBuilder.lower(expr);
                result = node.executeGeneric(frame);
            }
            return result;
        }
        finally
        {
            astBuilder.popLayout(prevBuilderLayout);
            this.currentLayout = prevLayout;
            this.currentFrame = prevFrame;
        }
    }

    // ---------------------------------------------------------------
    // Compilation
    // ---------------------------------------------------------------

    private CompiledFunction compile(FunctionDefinition fd)
    {
        CompiledFunction cached = functionCache.get(fd);
        if (cached != null)
        {
            return cached;
        }
        FrameLayout layout = FrameDescriptorBuilder.analyze(fd);
        if (layout == null)
        {
            // Should not happen in the standalone evaluator — every FD
            // gets a layout. Create a minimal one with just params.
            layout = FrameDescriptorBuilder.analyzeMinimal(fd);
        }
        CompiledFunction cf = new CompiledFunction(layout);
        functionCache.put(fd, cf);
        return cf;
    }

    // ---------------------------------------------------------------
    // Construction stack (for new/copy parent references)
    // ---------------------------------------------------------------

    public void pushConstruction(Object instance)
    {
        constructionStack.push(instance);
    }

    public void popConstruction()
    {
        constructionStack.pop();
    }

    public Object peekConstruction(int depth)
    {
        int i = 0;
        for (Object obj : constructionStack)
        {
            if (i == depth)
            {
                return obj;
            }
            i++;
        }
        throw new RuntimeException("Construction stack depth " + depth + " exceeds stack size " + constructionStack.size());
    }

    // ---------------------------------------------------------------
    // Property access — simple + QP dispatch
    // ---------------------------------------------------------------

    /**
     * Access a property on a target object. For generated Impl classes,
     * invokes the typed getter {@code _propertyName()}. For QualifiedProperties,
     * dispatches via C3 MRO and evaluates the body.
     */
    public Object accessProperty(Object target, String propertyName)
    {
        // Generated Impl classes have _propertyName() getters
        try
        {
            java.lang.reflect.Method method = target.getClass().getMethod("_" + propertyName);
            return method.invoke(target);
        }
        catch (NoSuchMethodException e)
        {
            throw new RuntimeException("Property '" + propertyName + "' not found on " + target.getClass().getName());
        }
        catch (Exception e)
        {
            throw new RuntimeException("Error accessing property '" + propertyName + "' on " + target.getClass().getName(), e);
        }
    }

    // ---------------------------------------------------------------
    // QualifiedProperty dispatch
    // ---------------------------------------------------------------

    private FunctionDefinition resolveQpDispatch(
            meta.pure.metamodel.function.property.QualifiedProperty staticQp,
            Object[] rawArgs)
    {
        if (rawArgs.length == 0)
        {
            return staticQp;
        }
        Object target = rawArgs[0];
        meta.pure.metamodel.type.generics.GenericType cgt = getClassifierGenericType(target);
        if (cgt == null)
        {
            return staticQp;
        }
        meta.pure.metamodel.type.Type runtimeType =
                org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._GenericType.type(cgt);
        if (runtimeType == null)
        {
            return staticQp;
        }
        String qpName = staticQp._name();
        int argCount = rawArgs.length;
        MutableList<meta.pure.metamodel.type.Type> mro =
                org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._Type.linearize(runtimeType, resolver);
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

    private void bindQpTypeVariables(Object target, VirtualFrame frame, FrameLayout layout)
    {
        meta.pure.metamodel.type.generics.GenericType cgt = getClassifierGenericType(target);
        if (!(cgt instanceof meta.pure.metamodel.type.generics.GenericTypeValue gtv)
                || gtv._typeVariableValues() == null || gtv._typeVariableValues().isEmpty())
        {
            return;
        }
        meta.pure.metamodel.type.Type ownerType =
                org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._GenericType.type(cgt);
        MutableList<VariableExpression> typeVars = null;
        if (ownerType instanceof meta.pure.metamodel.type.Class cls)
        {
            typeVars = cls._typeVariables();
        }
        if (typeVars == null || typeVars.isEmpty())
        {
            return;
        }
        MutableList<meta.pure.metamodel.valuespecification.ValueSpecification> typeVarVals = gtv._typeVariableValues();
        int count = Math.min(typeVars.size(), typeVarVals.size());
        for (int i = 0; i < count; i++)
        {
            String name = typeVars.get(i)._name();
            Integer slot = layout.slotFor(name);
            if (slot != null)
            {
                // Type variable values are still VS in the PDB — unwrap to raw
                Object raw = org.finos.legend.pure.execution._E_ValueSpecification.unwrap(typeVarVals.get(i));
                if (raw != null)
                {
                    frame.setObject(slot, raw);
                }
            }
        }
    }

    private static meta.pure.metamodel.type.generics.GenericType getClassifierGenericType(Object target)
    {
        if (target instanceof meta.pure.metamodel.type.Any any)
        {
            return any._classifierGenericType();
        }
        return null;
    }

    // ---------------------------------------------------------------
    // Accessors for nodes
    // ---------------------------------------------------------------

    public VirtualFrame currentFrame()
    {
        return currentFrame;
    }

    public FrameLayout currentLayout()
    {
        return currentLayout;
    }

    public PureASTBuilder astBuilder()
    {
        return astBuilder;
    }
}
