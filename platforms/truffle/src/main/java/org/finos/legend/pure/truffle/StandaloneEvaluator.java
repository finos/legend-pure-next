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
import org.finos.legend.pure.truffle.types.PureSequence;
import org.finos.legend.pure.truffle.types.RawClosure;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.WeakHashMap;

import org.finos.legend.pure.next.parser.PureParser;

/**
 * Standalone Pure interpreter — no {@code ValueSpecificationEvaluator},
 * no {@code NativeRepository}, no {@code ValueSpecification} types.
 *
 * <p>Every value flowing through this evaluator is a raw Java object:
 * {@code long}/{@code double}/{@code boolean}/{@code String} for
 * primitives, {@code PureSequence} for collections, generated Impl
 * classes for Pure class instances, {@code RawClosure} for lambdas,
 * {@code PureSequence.EMPTY} for empty.</p>
 *
 * <p>All scoping is frame-based ({@link VirtualFrame} with
 * {@link FrameLayout} slots). No HashMap {@code varStack}.</p>
 */
public final class StandaloneEvaluator
{
    private final MetadataAccess resolver;
    private final PureLanguage language;
    private final PureASTBuilder astBuilder;
    private PureParser pureParser;

    // Per-FD compilation cache: layout + lowered body
    private final WeakHashMap<FunctionDefinition, CompiledFunction> functionCache = new WeakHashMap<>();
    // Per-lambda RootCallTarget cache (lambdas don't have stable paths)
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

    public PureParser pureParser()
    {
        return pureParser;
    }

    public void setPureParser(PureParser parser)
    {
        this.pureParser = parser;
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
        // QualifiedProperty dispatch needs special handling: the resolved FD
        // may differ from the original, and type variables must be bound into
        // the frame before body execution.
        if (fd instanceof meta.pure.metamodel.function.property.QualifiedProperty qp && rawArgs.length > 0)
        {
            return executeQualifiedProperty(qp, rawArgs);
        }

        CompiledFunction cf = compile(fd);

        // Fast path: use pre-compiled RootCallTarget (body nodes are properly
        // adopted, frame is Truffle-managed). This is the Truffle-idiomatic way.
        com.oracle.truffle.api.RootCallTarget ct = cf.callTarget();
        if (ct != null)
        {
            return ct.call(rawArgs);
        }

        // Fallback: inline execution (if callTarget creation failed during compile)
        FrameLayout layout = cf.layout();
        VirtualFrame frame = Truffle.getRuntime().createVirtualFrame(new Object[0], layout.descriptor());
        MutableList<VariableExpression> params = fd._parameters();
        int[] paramSlots = layout.paramSlots();
        if (params != null)
        {
            int count = Math.min(params.size(), rawArgs.length);
            for (int i = 0; i < count; i++)
            {
                frame.setObject(paramSlots[i], rawArgs[i] != null ? rawArgs[i] : PureSequence.EMPTY);
            }
        }
        return executeBody(fd, frame, layout);
    }

    private Object executeQualifiedProperty(
            meta.pure.metamodel.function.property.QualifiedProperty qp, Object[] rawArgs)
    {
        CompiledFunction cf = compile(qp);
        FrameLayout layout = cf.layout();
        VirtualFrame frame = Truffle.getRuntime().createVirtualFrame(new Object[0], layout.descriptor());

        // Bind params
        MutableList<VariableExpression> params = qp._parameters();
        int[] paramSlots = layout.paramSlots();
        if (params != null)
        {
            int count = Math.min(params.size(), rawArgs.length);
            for (int i = 0; i < count; i++)
            {
                frame.setObject(paramSlots[i], rawArgs[i] != null ? rawArgs[i] : PureSequence.EMPTY);
            }
        }

        FunctionDefinition resolved = resolveQpDispatch(qp, rawArgs);
        bindQpTypeVariables(rawArgs[0], frame, layout);
        // Eagerly compile the resolved FD so its body is cached for future calls
        compile(resolved);
        return executeBody(resolved, frame, layout);
    }

    /**
     * Execute a {@link RawClosure} with raw args.
     */
    public Object executeLambda(RawClosure closure, Object[] rawArgs)
    {
        // Always use inline path — the cached callTarget's RawLambdaRootNode
        // may have stale FrameDescriptor/slot bindings when different lambda
        // wrapper objects share the same callTarget cache entry.
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
                frame.setObject(paramSlots[i], rawArgs[i] != null ? rawArgs[i] : PureSequence.EMPTY);
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

        // Use pre-compiled body if available (same fix as executeBody)
        PureNode[] body = cf.body();
        if (body != null)
        {
            FrameLayout prevLayout = this.currentLayout;
            VirtualFrame prevFrame = this.currentFrame;
            this.currentLayout = layout;
            this.currentFrame = frame;
            FrameLayout prevBuilderLayout = astBuilder.pushLayout(layout);
            try
            {
                Object result = PureSequence.EMPTY;
                for (PureNode node : body)
                {
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
        FrameLayout prevBuilderLayout = astBuilder.pushLayout(layout);
        try
        {
            // Use pre-compiled body if available (avoids re-lowering on every call).
            // Safety: only use cached body if its layout matches the active frame —
            // QP dispatch can resolve to a different FD whose body was lowered
            // under a different FrameDescriptor.
            CompiledFunction cf = functionCache.get(fd);
            PureNode[] body = (cf != null && cf.layout().descriptor() == layout.descriptor())
                    ? cf.body() : null;
            if (body != null)
            {
                Object result = PureSequence.EMPTY;
                for (PureNode node : body)
                {
                    result = node.executeGeneric(frame);
                }
                return result;
            }

            // Fallback: re-lower (for FDs not in cache, e.g. QP dispatch
            // resolved to a different FD that hasn't been compiled yet)
            Object result = PureSequence.EMPTY;
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
            layout = FrameDescriptorBuilder.analyzeMinimal(fd);
        }
        CompiledFunction cf = new CompiledFunction(layout);
        // Cache first to prevent infinite recursion if lowering triggers compilation
        functionCache.put(fd, cf);

        // Pre-lower body once and create RootCallTarget for proper Truffle adoption.
        // If lowering fails (e.g. FlatBuffer wrapper hazards), body stays null and
        // executeBody falls back to re-lowering.
        try
        {
            PureNode[] body = astBuilder.lowerBody(fd._expressionSequence(), layout);
            cf.setBody(body);

            // Create RootCallTarget only for non-lambda FDs (lambdas use RawLambdaRootNode)
            if (!(fd instanceof LambdaFunction))
            {
                String name = getFunctionName(fd);
                PureFunctionRootNode root = new PureFunctionRootNode(language, name, layout, body);
                cf.setCallTarget(root.getCallTarget());
            }
        }
        catch (RuntimeException e)
        {
            // Lowering failed — body stays null, executeBody will re-lower as fallback
        }
        return cf;
    }

    private static String getFunctionName(FunctionDefinition fd)
    {
        if (fd instanceof meta.pure.metamodel.PackageableElement pe)
        {
            try
            {
                return org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._PackageableElement.path(pe);
            }
            catch (RuntimeException ignored)
            {
            }
        }
        return "fn@" + System.identityHashCode(fd);
    }

    /**
     * Returns a cached RootCallTarget for a lambda, compiling its body
     * on first access. Used by RawLambdaCallNode for DirectCallNode dispatch.
     */
    public RootCallTarget callTargetForLambda(LambdaFunction lambda)
    {
        RootCallTarget cached = lambdaCache.get(lambda);
        if (cached != null)
        {
            return cached;
        }
        CompiledFunction cf = compile(lambda);
        try
        {
            FrameLayout prevLayout = astBuilder.pushLayout(cf.layout());
            try
            {
                PureNode[] body = astBuilder.lowerBody(
                        lambda._expressionSequence(), cf.layout());
                org.eclipse.collections.api.list.MutableList<meta.pure.metamodel.valuespecification.VariableExpression> openVars =
                        lambda._openVariables();
                String[] openVarNames = openVars == null || openVars.isEmpty()
                        ? new String[0]
                        : openVars.collect(ov -> ov._name()).toArray(new String[0]);
                RawLambdaRootNode root = new RawLambdaRootNode(
                        language, "lambda@" + System.identityHashCode(lambda),
                        cf.layout(), cf.layout().paramSlots(), openVarNames, body);
                RootCallTarget ct = root.getCallTarget();
                lambdaCache.put(lambda, ct);
                return ct;
            }
            finally
            {
                astBuilder.popLayout(prevLayout);
            }
        }
        catch (RuntimeException e)
        {
            return null;
        }
    }

    private static PureNode createSequenceNode(PureNode[] body)
    {
        if (body.length == 1)
        {
            return body[0];
        }
        return new org.finos.legend.pure.truffle.ast.SequenceNode(body);
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
    /**
     * Set a property on a target object via its typed setter.
     */
    public void accessProperty(Object target, String propertyName, Object value)
    {
        String methodName = "_" + propertyName;
        try
        {
            for (java.lang.reflect.Method m : target.getClass().getMethods())
            {
                if (methodName.equals(m.getName()) && m.getParameterCount() == 1)
                {
                    Class<?> paramType = m.getParameterTypes()[0];
                    Object coerced = coerceForSetter(value, paramType);
                    if (coerced != null || value == null
                            || value instanceof org.finos.legend.pure.truffle.types.PureSequence _ps1 && _ps1.isEmpty())
                    {
                        m.invoke(target, coerced);
                        return;
                    }
                }
            }
            // Fallback: DynamicInstance from MetaNatives.createInstanceByPath (classes without generated Impl)
            if (target instanceof org.finos.legend.pure.execution.DynamicInstance di)
            {
                di.put(propertyName, value);
                return;
            }
            throw new RuntimeException("Property '" + propertyName + "' not found on " + target.getClass().getSimpleName());
        }
        catch (IllegalArgumentException iae)
        {
            throw new RuntimeException("argument type mismatch setting '" + propertyName + "' on "
                    + target.getClass().getSimpleName()
                    + " value=" + (value == null ? "null" : value.getClass().getName()), iae);
        }
        catch (java.lang.reflect.InvocationTargetException ite)
        {
            Throwable cause = ite.getCause() != null ? ite.getCause() : ite;
            if (cause instanceof RuntimeException re)
            {
                throw re;
            }
            throw new RuntimeException("Error setting property '" + propertyName + "' on "
                    + target.getClass().getSimpleName() + ": " + cause.getMessage(), cause);
        }
        catch (Exception e)
        {
            throw new RuntimeException("Error setting property '" + propertyName + "' on "
                    + target.getClass().getSimpleName() + ": " + e.getMessage(), e);
        }
    }

    private Object coerceForSetter(Object value, Class<?> targetType)
    {
        if (value == null || value instanceof org.finos.legend.pure.truffle.types.PureSequence _ps2 && _ps2.isEmpty())
        {
            return null;
        }
        if (targetType.isInstance(value))
        {
            return value;
        }
        // PureSequence property: wrap scalar/list into ObjectSequence
        if (org.finos.legend.pure.truffle.types.PureSequence.class.isAssignableFrom(targetType))
        {
            if (value instanceof org.eclipse.collections.api.list.MutableList<?> ml)
            {
                return new org.finos.legend.pure.truffle.types.ObjectSequence(ml.toArray());
            }
            if (value instanceof java.util.List<?> list)
            {
                return new org.finos.legend.pure.truffle.types.ObjectSequence(list.toArray());
            }
            return new org.finos.legend.pure.truffle.types.ObjectSequence(new Object[]{value});
        }
        // MutableList property (metamodel classes): wrap scalar/sequence into MutableList
        if (org.eclipse.collections.api.list.MutableList.class.isAssignableFrom(targetType))
        {
            if (value instanceof org.finos.legend.pure.truffle.types.PureSequence seq)
            {
                return org.eclipse.collections.api.factory.Lists.mutable.with(seq.toBoxedArray());
            }
            if (value instanceof java.util.List<?> list)
            {
                return org.eclipse.collections.api.factory.Lists.mutable.withAll(list);
            }
            return org.eclipse.collections.api.factory.Lists.mutable.with(value);
        }
        // Unwrap AtomicValue
        if (value instanceof meta.pure.metamodel.valuespecification.AtomicValue av)
        {
            Object inner = av._value();
            if (inner != null)
            {
                return coerceForSetter(inner, targetType);
            }
        }
        // Unwrap single-element PureSequence
        if (value instanceof org.finos.legend.pure.truffle.types.PureSequence seq && seq.size() == 1)
        {
            return coerceForSetter(seq.getBoxed(0), targetType);
        }
        // Object target type accepts everything
        if (targetType == Object.class)
        {
            return value;
        }
        // Numeric coercion
        if (targetType == Long.class && value instanceof Number n)
        {
            return n.longValue();
        }
        if (targetType == Double.class && value instanceof Number n)
        {
            return n.doubleValue();
        }
        // String coercion
        if (targetType == String.class)
        {
            return value.toString();
        }
        // Boolean
        if (targetType == Boolean.class && value instanceof Boolean)
        {
            return value;
        }
        // Enum coercion: value may be a PDB EnumImpl/FlatBufferWrapper,
        // target is a generated Java enum
        if (targetType.isEnum() && value instanceof meta.pure.metamodel.type.Enum enumVal)
        {
            String enumName = enumVal._name();
            if (enumName != null)
            {
                for (Object constant : targetType.getEnumConstants())
                {
                    if (constant instanceof java.lang.Enum<?> e && e.name().equals(enumName))
                    {
                        return constant;
                    }
                }
            }
        }
        // Interface coercion: the target is an interface and the value implements
        // a compatible interface (e.g., generated enum implementing the enum interface)
        if (targetType.isInterface() && value instanceof meta.pure.metamodel.type.Enum enumVal2)
        {
            String enumName = enumVal2._name();
            if (enumName != null)
            {
                // Try to find the generated Enum class that implements this interface
                String enumClassName = targetType.getName() + "Enum";
                try
                {
                    Class<?> enumClass = Class.forName(enumClassName);
                    if (enumClass.isEnum())
                    {
                        for (Object constant : enumClass.getEnumConstants())
                        {
                            if (constant instanceof java.lang.Enum<?> e && e.name().equals(enumName))
                            {
                                return constant;
                            }
                        }
                    }
                }
                catch (ClassNotFoundException ignored)
                {
                }
            }
        }
        // Can't coerce — return null to skip this setter method
        return null;
    }

    public Object accessProperty(Object target, String propertyName)
    {
        // Unwrap AtomicValue — the inner value is the real target
        if (target instanceof meta.pure.metamodel.valuespecification.AtomicValue av && av._value() != null)
        {
            target = av._value();
        }
        // Unwrap RawClosure — access the lambda's FD properties
        if (target instanceof org.finos.legend.pure.truffle.types.RawClosure rc)
        {
            target = rc.lambda();
        }
        // Generated Impl classes have _propertyName() getters
        try
        {
            java.lang.reflect.Method method = target.getClass().getMethod("_" + propertyName);
            return method.invoke(target);
        }
        catch (NoSuchMethodException e)
        {
            // Try without underscore prefix (some metamodel properties)
            // But skip methods inherited from Object (toString, hashCode, etc.)
            try
            {
                java.lang.reflect.Method method = target.getClass().getMethod(propertyName);
                if (method.getDeclaringClass() != Object.class)
                {
                    return method.invoke(target);
                }
                throw new NoSuchMethodException(propertyName);
            }
            catch (Exception e2)
            {
                // Enum value fallback: search the Enumeration's properties
                // for one matching propertyName and extract its defaultValue
                if (target instanceof meta.pure.metamodel.type.Enumeration en && en._properties() != null)
                {
                    var prop = en._properties().detect(p -> propertyName.equals(p._name()));
                    if (prop != null && prop._defaultValue() != null
                            && prop._defaultValue()._expressionSequence() != null
                            && !prop._defaultValue()._expressionSequence().isEmpty())
                    {
                        meta.pure.metamodel.valuespecification.ValueSpecification vs =
                                prop._defaultValue()._expressionSequence().getFirst();
                        if (vs instanceof meta.pure.metamodel.valuespecification.AtomicValue av && av._value() != null)
                        {
                            Object enumVal = av._value();
                            // Coerce PDB Enum value to generated Java enum constant for identity
                            if (enumVal instanceof meta.pure.metamodel.type.Enum enumObj)
                            {
                                Object javaEnum = coerceToJavaEnum(en, propertyName);
                                if (javaEnum != null)
                                {
                                    return javaEnum;
                                }
                            }
                            return enumVal;
                        }
                    }
                }
                // QualifiedProperty dispatch: look up QP from the target's type
                meta.pure.metamodel.type.Class cls = resolveClassForTarget(target);
                if (cls != null)
                {
                    meta.pure.metamodel.function.property.QualifiedProperty qp = findQualifiedProperty(cls, propertyName);
                    if (qp != null)
                    {
                        return executeFunction(qp, new Object[]{target});
                    }
                }
                // Fallback: DynamicInstance
                if (target instanceof org.finos.legend.pure.execution.DynamicInstance di)
                {
                    Object val = di.get(propertyName);
                    return val != null ? val : org.finos.legend.pure.truffle.types.PureSequence.EMPTY;
                }
                throw new RuntimeException("Property '" + propertyName + "' not found on " + target.getClass().getName());
            }
        }
        catch (Exception e)
        {
            throw new RuntimeException("Error accessing property '" + propertyName + "' on " + target.getClass().getName(), e);
        }
    }

    /**
     * Coerce a PDB enum value to the generated Java enum constant for identity preservation.
     */
    public static Object coerceToJavaEnum(meta.pure.metamodel.type.Enumeration en, String valueName)
    {
        if (en instanceof meta.pure.metamodel.PackageableElement pe)
        {
            String enumPath = org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._PackageableElement.path(pe);
            String enumClassName = enumPath.replace("::", ".") + "Enum";
            try
            {
                Class<?> enumClass = Class.forName(enumClassName);
                if (enumClass.isEnum())
                {
                    for (Object constant : enumClass.getEnumConstants())
                    {
                        if (constant instanceof java.lang.Enum<?> e && e.name().equals(valueName))
                        {
                            return constant;
                        }
                    }
                }
            }
            catch (ClassNotFoundException ignored)
            {
            }
        }
        return null;
    }

    // ---------------------------------------------------------------
    // QualifiedProperty dispatch
    // ---------------------------------------------------------------

    private meta.pure.metamodel.type.Class resolveClassForTarget(Object target)
    {
        // Try CGT first
        meta.pure.metamodel.type.generics.GenericType cgt = getClassifierGenericType(target);
        if (cgt != null)
        {
            meta.pure.metamodel.type.Type t =
                    org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._GenericType.type(cgt);
            if (t instanceof meta.pure.metamodel.type.Class cls)
            {
                return cls;
            }
        }
        // Fallback: derive class path from Java interface name
        Class<?>[] ifaces = target.getClass().getInterfaces();
        if (ifaces.length > 0)
        {
            String ifaceName = ifaces[0].getName().replace(".", "::");
            meta.pure.metamodel.PackageableElement elem = resolver.getElement(ifaceName);
            if (elem instanceof meta.pure.metamodel.type.Class cls)
            {
                return cls;
            }
        }
        return null;
    }

    private meta.pure.metamodel.function.property.QualifiedProperty findQualifiedProperty(
            meta.pure.metamodel.type.Class cls, String name)
    {
        // Search own QPs
        if (cls._qualifiedProperties() != null)
        {
            var qp = cls._qualifiedProperties().detect(q -> name.equals(q._name()));
            if (qp != null)
            {
                return qp;
            }
        }
        // Search supertypes
        if (cls._generalizations() != null)
        {
            for (var gen : cls._generalizations())
            {
                var gt = gen._general();
                if (gt != null)
                {
                    var parentType = org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._GenericType.type(gt);
                    if (parentType instanceof meta.pure.metamodel.type.Class parentCls)
                    {
                        var found = findQualifiedProperty(parentCls, name);
                        if (found != null)
                        {
                            return found;
                        }
                    }
                }
            }
        }
        return null;
    }

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

    public void setCurrentFrame(VirtualFrame frame)
    {
        this.currentFrame = frame;
    }

    public FrameLayout currentLayout()
    {
        return currentLayout;
    }

    public void setCurrentLayout(FrameLayout layout)
    {
        this.currentLayout = layout;
    }

    public PureASTBuilder astBuilder()
    {
        return astBuilder;
    }
}
