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
import org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.function.FunctionDefinition;
import org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.function.LambdaFunction;
import org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.valuespecification.VariableExpression;
import org.eclipse.collections.api.list.MutableList;
import org.finos.legend.pure.truffle.runtime.TruffleMetadataAccess;
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
    /**
     * Global instance — set before execution begins (e.g. in PureTruffleRuntime
     * or TrufflePureTestRunner). AST nodes read this directly instead of going
     * through a holder/ThreadLocal.
     */
    public static StandaloneEvaluator INSTANCE;

    private final TruffleMetadataAccess resolver;
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

    public StandaloneEvaluator(TruffleMetadataAccess resolver, PureLanguage language,
                               NativeNodeRegistry registry,
                               Object nativesFallback)
    {
        this.resolver = resolver;
        this.language = language;
        this.astBuilder = new PureASTBuilder(nativesFallback, registry);
    }

    public TruffleMetadataAccess resolver()
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
    // CallTarget access — used by RawUserFunctionCallNode for Truffle-native calls
    // ---------------------------------------------------------------

    /**
     * Returns a compiled {@link RootCallTarget} for a FunctionDefinition (including QPs).
     */
    public RootCallTarget getCallTarget(FunctionDefinition fd)
    {
        CompiledFunction cf = compile(fd);
        return cf.callTarget();
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
        com.oracle.truffle.api.RootCallTarget ct = cf.callTarget();
        if (ct != null)
        {
            return ct.call(rawArgs);
        }
        throw new RuntimeException("No CallTarget for: " + (fd instanceof org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.PackageableElement pe
                ? org.finos.legend.pure.truffle.runtime.helper._PackageableElement.path(pe) : fd.getClass().getSimpleName()));
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
                com.oracle.truffle.api.source.SourceSection rootSource = null;
                try
                {
                    org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.SourceInformation si = null;
                    if (fd instanceof org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.type.Any any)
                    {
                        si = any._sourceInformation();
                    }
                    if (si == null && fd._expressionSequence() != null && !fd._expressionSequence().isEmpty())
                    {
                        // Try getting source from the first expression in the body
                        Object firstExpr = fd._expressionSequence().getBoxed(0);
                        if (firstExpr instanceof org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.valuespecification.ValueSpecification vs)
                        {
                            si = vs._sourceInformation();
                        }
                    }
                    rootSource = org.finos.legend.pure.truffle.ast.PureSourceHelper.createSourceSection(si);
                }
                catch (Exception ignored) {}
                PureFunctionRootNode root = new PureFunctionRootNode(language, name, layout, body, rootSource);
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
        if (fd instanceof org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.PackageableElement pe)
        {
            try
            {
                return org.finos.legend.pure.truffle.runtime.helper._PackageableElement.path(pe);
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
                org.finos.legend.pure.truffle.types.PureSequence openVars =
                        lambda._openVariables();
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
                        Object ov = openVars.getBoxed(i);
                        if (ov instanceof org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.valuespecification.VariableExpression ve)
                        {
                            openVarNames[i] = ve._name();
                        }
                        else if (ov instanceof String s)
                        {
                            openVarNames[i] = s;
                        }
                        else
                        {
                            openVarNames[i] = String.valueOf(ov);
                        }
                    }
                }
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
        Object originalValue = (value instanceof org.finos.legend.pure.truffle.types.PureSequence ps && ps.isEmpty()) ? null : value;
        Object rawValue = unwrapForSetter(value);

        String setterName = "_" + propertyName;
        for (java.lang.reflect.Method method : target.getClass().getMethods())
        {
            if (method.getName().equals(setterName) && method.getParameterCount() == 1)
            {
                try
                {
                    // 1. Try original value (preserves VS instances like Collection, AtomicValue)
                    try
                    {
                        method.invoke(target, originalValue);
                        return;
                    }
                    catch (IllegalArgumentException ignored)
                    {
                    }

                    // 2. Try unwrapped value
                    if (rawValue != originalValue)
                    {
                        try
                        {
                            method.invoke(target, rawValue);
                            return;
                        }
                        catch (IllegalArgumentException ignored)
                        {
                        }
                    }

                    // 3. Coercion fallbacks
                    Class<?> paramType = method.getParameterTypes()[0];
                    if (org.finos.legend.pure.truffle.types.PureSequence.class.isAssignableFrom(paramType))
                    {
                        method.invoke(target, toPureSequence(rawValue));
                        return;
                    }
                    if (org.eclipse.collections.api.RichIterable.class.isAssignableFrom(paramType) || java.util.Collection.class.isAssignableFrom(paramType))
                    {
                        method.invoke(target, toMutableList(rawValue));
                        return;
                    }
                    // Enum coercion
                    if (rawValue instanceof org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.type.Enum enumVal)
                    {
                        Object coerced = paramType.isEnum()
                                ? coerceToJavaEnumConstant(paramType, enumVal._name())
                                : coerceEnumToInterface(paramType, enumVal._name());
                        if (coerced != null)
                        {
                            method.invoke(target, coerced);
                            return;
                        }
                    }
                    if (rawValue instanceof String enumName)
                    {
                        Object coerced = paramType.isEnum()
                                ? coerceToJavaEnumConstant(paramType, enumName)
                                : coerceEnumToInterface(paramType, enumName);
                        if (coerced != null)
                        {
                            method.invoke(target, coerced);
                            return;
                        }
                    }
                    // VS wrapping
                    if (org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.valuespecification.ValueSpecification.class.isAssignableFrom(paramType))
                    {
                        Object wrapped = wrapAsValueSpecification(rawValue);
                        if (wrapped != null)
                        {
                            method.invoke(target, wrapped);
                            return;
                        }
                    }
                }
                catch (java.lang.reflect.InvocationTargetException ite)
                {
                    Throwable cause = ite.getCause() != null ? ite.getCause() : ite;
                    if (cause instanceof RuntimeException re)
                    {
                        throw re;
                    }
                    throw new RuntimeException("Error setting '" + propertyName + "' on " + target.getClass().getSimpleName(), cause);
                }
                catch (Exception e)
                {
                    throw new RuntimeException("Error setting '" + propertyName + "' on " + target.getClass().getSimpleName(), e);
                }
            }
        }
        throw new RuntimeException("Property '" + propertyName + "' not found on " + target.getClass().getSimpleName());
    }

    /** Unwrap ValueSpecification wrappers one level to get raw values. No recursion. */
    static Object unwrapForSetter(Object value)
    {
        if (value == null || (value instanceof org.finos.legend.pure.truffle.types.PureSequence ps && ps.isEmpty()))
        {
            return null;
        }
        // Single-element PureSequence — unwrap one level
        if (value instanceof org.finos.legend.pure.truffle.types.PureSequence seq && seq.size() == 1)
        {
            return seq.getBoxed(0);
        }
        // AtomicValue — unwrap to raw value
        if (value instanceof org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.valuespecification.AtomicValue av && av._value() != null)
        {
            return av._value();
        }
        return value;
    }

    /** Unwrap Collection VS to PureSequence of its values (one level, each element unwrapped one level). */
    static org.finos.legend.pure.truffle.types.PureSequence unwrapCollectionVS(
            org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.valuespecification.Collection col)
    {
        var vals = col._values();
        if (vals == null || vals.isEmpty()) return new org.finos.legend.pure.truffle.types.ObjectSequence(new Object[0]);
        Object[] unwrapped = new Object[vals.size()];
        for (int i = 0; i < vals.size(); i++)
        {
            unwrapped[i] = unwrapForSetter(vals.getBoxed(i));
        }
        return new org.finos.legend.pure.truffle.types.ObjectSequence(unwrapped);
    }

    private static org.finos.legend.pure.truffle.types.PureSequence toPureSequence(Object value)
    {
        if (value instanceof org.finos.legend.pure.truffle.types.PureSequence seq)
        {
            return seq;
        }
        if (value instanceof org.eclipse.collections.api.list.MutableList<?> ml)
        {
            return new org.finos.legend.pure.truffle.types.ObjectSequence(ml.toArray());
        }
        if (value instanceof java.util.List<?> list)
        {
            return new org.finos.legend.pure.truffle.types.ObjectSequence(list.toArray());
        }
        if (value == null)
        {
            return new org.finos.legend.pure.truffle.types.ObjectSequence(new Object[0]);
        }
        return new org.finos.legend.pure.truffle.types.ObjectSequence(new Object[]{value});
    }

    private static org.eclipse.collections.api.list.MutableList<Object> toMutableList(Object value)
    {
        if (value instanceof org.finos.legend.pure.truffle.types.PureSequence seq)
        {
            return org.eclipse.collections.api.factory.Lists.mutable.with(seq.toBoxedArray());
        }
        if (value instanceof java.util.List<?> list)
        {
            return org.eclipse.collections.api.factory.Lists.mutable.withAll((java.util.Collection<?>) list);
        }
        if (value == null)
        {
            return org.eclipse.collections.api.factory.Lists.mutable.empty();
        }
        return org.eclipse.collections.api.factory.Lists.mutable.with(value);
    }

    private static Object coerceToJavaEnumConstant(Class<?> enumClass, String name)
    {
        if (name == null)
        {
            return null;
        }
        for (Object constant : enumClass.getEnumConstants())
        {
            if (constant instanceof java.lang.Enum<?> e && e.name().equals(name))
            {
                return constant;
            }
        }
        return null;
    }

    private static Object coerceEnumToInterface(Class<?> targetInterface, String name)
    {
        if (name == null)
        {
            return null;
        }
        // Extract just the enum value name (e.g. "meta::pure::...::M_GeographicEntityType.CITY" → "CITY")
        String valueName = name;
        int dotIdx = name.lastIndexOf('.');
        if (dotIdx >= 0)
        {
            valueName = name.substring(dotIdx + 1);
        }
        int colonIdx = valueName.lastIndexOf(':');
        if (colonIdx >= 0)
        {
            valueName = valueName.substring(colonIdx + 1);
        }
        try
        {
            Class<?> enumClass = Class.forName(targetInterface.getName() + "Enum");
            if (enumClass.isEnum())
            {
                return coerceToJavaEnumConstant(enumClass, valueName);
            }
        }
        catch (ClassNotFoundException ignored)
        {
        }
        return null;
    }

    /** Wrap a raw value as a ValueSpecification (CollectionImpl for lists, AtomicValueImpl for scalars). */
    private static Object wrapAsValueSpecification(Object rawValue)
    {
        if (rawValue instanceof org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.valuespecification.ValueSpecification)
        {
            return rawValue; // already a VS
        }
        if (rawValue instanceof org.eclipse.collections.api.list.MutableList<?> ml)
        {
            var col = new org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.valuespecification.CollectionImpl();
            Object[] items = new Object[ml.size()];
            for (int i = 0; i < ml.size(); i++)
            {
                Object item = ml.get(i);
                if (!(item instanceof org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.valuespecification.ValueSpecification))
                {
                    var av = new org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.valuespecification.AtomicValueImpl();
                    av._value(item);
                    item = av;
                }
                items[i] = item;
            }
            col._values(new org.finos.legend.pure.truffle.types.ObjectSequence(items));
            return col;
        }
        if (rawValue instanceof java.util.List<?> list)
        {
            var col = new org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.valuespecification.CollectionImpl();
            Object[] items = new Object[list.size()];
            for (int i = 0; i < list.size(); i++)
            {
                Object item = list.get(i);
                if (!(item instanceof org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.valuespecification.ValueSpecification))
                {
                    var av = new org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.valuespecification.AtomicValueImpl();
                    av._value(item);
                    item = av;
                }
                items[i] = item;
            }
            col._values(new org.finos.legend.pure.truffle.types.ObjectSequence(items));
            return col;
        }
        // Single scalar → wrap in AtomicValueImpl
        var av = new org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.valuespecification.AtomicValueImpl();
        av._value(rawValue);
        return av;
    }

    public Object accessProperty(Object target, String propertyName)
    {
        // Unwrap RawClosure — access the lambda's FD properties
        if (target instanceof org.finos.legend.pure.truffle.types.RawClosure rc)
        {
            target = rc.lambda();
        }

        // 1. Try _propertyName() on the original target (preserves VS instances)
        String methodName = "_" + propertyName;
        Object result = tryInvokeGetter(target, methodName);
        if (result != GETTER_NOT_FOUND)
        {
            return result;
        }

        // 2. Try without underscore prefix (some metamodel properties)
        result = tryInvokeGetter(target, propertyName);
        if (result != GETTER_NOT_FOUND)
        {
            return result;
        }

        // 3. Enum value fallback
        if (target instanceof org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.type.Enumeration en && en._properties() != null)
        {
            for (Object p : en._properties().toBoxedArray())
            {
                if (p instanceof org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.function.property.Property pp && propertyName.equals(pp._name()))
                {
                    if (pp._defaultValue() != null && pp._defaultValue()._expressionSequence() != null
                            && !pp._defaultValue()._expressionSequence().isEmpty())
                    {
                        Object vsObj = pp._defaultValue()._expressionSequence().getBoxed(0);
                        // Lower and execute the VS expression
                        if (vsObj instanceof org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.valuespecification.ValueSpecification vs)
                        {
                            PureNode node = astBuilder.lower(vs);
                            VirtualFrame tmpFrame = com.oracle.truffle.api.Truffle.getRuntime().createVirtualFrame(
                                    new Object[0],
                                    com.oracle.truffle.api.frame.FrameDescriptor.newBuilder().build());
                            Object enumVal = node.executeGeneric(tmpFrame);
                            if (enumVal instanceof org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.type.Enum)
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
            }
        }

        // 4. QualifiedProperty dispatch
        org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.type.Class cls = resolveClassForTarget(target);
        if (cls != null)
        {
            org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.function.property.QualifiedProperty qp = findQualifiedProperty(cls, propertyName);
            if (qp != null)
            {
                return executeFunction(qp, new Object[]{target});
            }
        }

        throw new RuntimeException("Property '" + propertyName + "' not found on " + target.getClass().getName());
    }

    private static final Object GETTER_NOT_FOUND = new Object();

    /** Try to invoke a no-arg getter method. Returns GETTER_NOT_FOUND if not found. */
    private static Object tryInvokeGetter(Object target, String methodName)
    {
        try
        {
            java.lang.reflect.Method method = target.getClass().getMethod(methodName);
            if (method.getDeclaringClass() == Object.class)
            {
                return GETTER_NOT_FOUND;
            }
            return method.invoke(target);
        }
        catch (NoSuchMethodException e)
        {
            return GETTER_NOT_FOUND;
        }
        catch (Exception e)
        {
            throw new RuntimeException("Error accessing '" + methodName + "' on " + target.getClass().getName(), e);
        }
    }

    /**
     * Coerce a PDB enum value to the generated Java enum constant for identity preservation.
     */
    public static Object coerceToJavaEnum(org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.type.Enumeration en, String valueName)
    {
        if (en instanceof org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.PackageableElement pe)
        {
            String enumPath = org.finos.legend.pure.truffle.runtime.helper._PackageableElement.path(pe);
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

    org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.type.Class resolveClassForTarget(Object target)
    {
        // Try CGT first
        org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.type.generics.GenericType cgt = getClassifierGenericType(target);
        if (cgt != null)
        {
            org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.type.Type t =
                    org.finos.legend.pure.truffle.runtime.helper._GenericType.type(cgt);
            if (t instanceof org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.type.Class cls)
            {
                return cls;
            }
        }
        // Fallback: derive class path from Java interface name
        Class<?>[] ifaces = target.getClass().getInterfaces();
        if (ifaces.length > 0)
        {
            String ifaceName = ifaces[0].getName().replace(".", "::");
            Object elem = resolver.getElement(ifaceName);
            if (elem instanceof org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.type.Class cls)
            {
                return cls;
            }
        }
        return null;
    }

    org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.function.property.QualifiedProperty findQualifiedProperty(
            org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.type.Class cls, String name)
    {
        // Search own QPs
        if (cls._qualifiedProperties() != null)
        {
            for (Object q : cls._qualifiedProperties().toBoxedArray())
            {
                if (q instanceof org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.function.property.QualifiedProperty qp && name.equals(qp._name()))
                {
                    return qp;
                }
            }
        }
        // Search supertypes
        if (cls._generalizations() != null)
        {
            for (Object gen : cls._generalizations().toBoxedArray())
            {
                if (gen instanceof org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.relationship.Generalization g)
                {
                    var gt = g._general();
                    if (gt != null)
                    {
                        var parentType = org.finos.legend.pure.truffle.runtime.helper._GenericType.type(gt);
                        if (parentType instanceof org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.type.Class parentCls)
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
        }
        return null;
    }

    public FunctionDefinition resolveQpDispatch(
            org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.function.property.QualifiedProperty staticQp,
            Object[] rawArgs)
    {
        if (rawArgs.length == 0)
        {
            return staticQp;
        }
        Object target = rawArgs[0];
        org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.type.generics.GenericType cgt = getClassifierGenericType(target);
        if (cgt == null)
        {
            return staticQp;
        }
        org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.type.Type runtimeType =
                org.finos.legend.pure.truffle.runtime.helper._GenericType.type(cgt);
        if (runtimeType == null)
        {
            return staticQp;
        }
        String qpName = staticQp._name();
        int argCount = rawArgs.length;
        java.util.List<org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.type.Type> mro =
                org.finos.legend.pure.truffle.runtime.helper._Type.linearize(runtimeType, resolver);
        for (org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.type.Type type : mro)
        {
            if (type instanceof org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.type.Class cls && cls._qualifiedProperties() != null)
            {
                for (Object candidate : cls._qualifiedProperties().toBoxedArray())
                {
                    if (candidate instanceof org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.function.property.QualifiedProperty cqp
                            && qpName.equals(cqp._name())
                            && cqp._parameters() != null
                            && cqp._parameters().size() == argCount)
                    {
                        return cqp;
                    }
                }
            }
        }
        return staticQp;
    }

    /**
     * Bind type variable values from the target's CGT into the frame.
     * Handles any number of type variables.
     */
    public static void bindQpTypeVariablesStatic(Object target, VirtualFrame frame, FrameLayout layout)
    {
        org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.type.generics.GenericType cgt = getClassifierGenericType(target);
        if (!(cgt instanceof org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.type.generics.GenericTypeValue gtv)
                || gtv._typeVariableValues() == null || gtv._typeVariableValues().isEmpty())
        {
            return;
        }
        org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.type.Type ownerType =
                org.finos.legend.pure.truffle.runtime.helper._GenericType.type(cgt);
        org.finos.legend.pure.truffle.types.PureSequence typeVars = null;
        if (ownerType instanceof org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.type.Class cls)
        {
            typeVars = cls._typeVariables();
        }
        if (typeVars == null || typeVars.isEmpty())
        {
            return;
        }
        org.finos.legend.pure.truffle.types.PureSequence typeVarVals = gtv._typeVariableValues();
        int count = Math.min(typeVars.size(), typeVarVals.size());
        for (int i = 0; i < count; i++)
        {
            Object tvObj = typeVars.getBoxed(i);
            String name = (tvObj instanceof org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.valuespecification.VariableExpression ve) ? ve._name() : String.valueOf(tvObj);
            Integer slot = layout.slotFor(name);
            if (slot != null)
            {
                // Type variable values are AtomicValue in the PDB — unwrap to raw
                Object tvVal = typeVarVals.getBoxed(i);
                if (tvVal instanceof org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.valuespecification.AtomicValue av)
                {
                    tvVal = av._value();
                }
                if (tvVal != null)
                {
                    frame.setObject(slot, tvVal);
                }
            }
        }
    }

    /**
     * Collect the names of type variables declared on the target's class.
     * Returns an empty set if the target has no type variables.
     */
    private static java.util.Set<String> collectTypeVariableNames(Object target)
    {
        java.util.Set<String> names = java.util.Collections.emptySet();
        org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.type.generics.GenericType cgt = getClassifierGenericType(target);
        if (cgt == null)
        {
            return names;
        }
        var type = org.finos.legend.pure.truffle.runtime.helper._GenericType.type(cgt);
        if (type instanceof org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.type.Class cls && cls._typeVariables() != null)
        {
            var tvs = cls._typeVariables();
            if (!tvs.isEmpty())
            {
                names = new java.util.HashSet<>();
                for (int i = 0; i < tvs.size(); i++)
                {
                    Object tv = tvs.getBoxed(i);
                    if (tv instanceof VariableExpression ve && ve._name() != null)
                    {
                        names.add(ve._name());
                    }
                }
            }
        }
        return names;
    }

    private static org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.type.generics.GenericType getClassifierGenericType(Object target)
    {
        if (target instanceof org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.type.Any any)
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
