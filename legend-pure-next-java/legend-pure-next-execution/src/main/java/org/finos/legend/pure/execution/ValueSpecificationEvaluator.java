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

package org.finos.legend.pure.execution;

import meta.pure.metamodel.SourceInformation;
import meta.pure.metamodel.function.FunctionDefinition;
import meta.pure.metamodel.function.LambdaFunction;
import meta.pure.metamodel.function.NativeFunction;
import meta.pure.metamodel.function.PackageableFunction;
import meta.pure.metamodel.type.generics.GenericType;
import meta.pure.metamodel.valuespecification.AtomicValue;
import meta.pure.metamodel.valuespecification.Collection;
import meta.pure.metamodel.valuespecification.FunctionExpression;
import meta.pure.metamodel.valuespecification.GenericTypeAndMultiplicityHolder;
import meta.pure.metamodel.valuespecification.ValueSpecification;
import meta.pure.metamodel.valuespecification.VariableExpression;
import org.eclipse.collections.api.list.MutableList;
import org.finos.legend.pure.execution.natives.collection.CollectionNatives;
import org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._GenericType;
import org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._Type;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Recursive tree-walking interpreter for compiled Pure expressions.
 *
 * <p>Evaluates a {@link ValueSpecification} tree by dispatching on
 * the runtime type of each node. All intermediate values remain
 * {@link ValueSpecification} instances (typically {@link AtomicValue})
 * preserving Pure type information throughout evaluation.</p>
 */
public class ValueSpecificationEvaluator
{
    private final NativeRepository natives;
    private final Deque<FunctionExpression> callStack = new ArrayDeque<>();
    private final Deque<Map<String, ValueSpecification>> varStack = new ArrayDeque<>();
    private final Deque<Object> constructionStack = new ArrayDeque<>();

    public ValueSpecificationEvaluator(NativeRepository natives)
    {
        this.natives = natives;
        varStack.push(new HashMap<>());
    }

    /**
     * Return the current (top-of-stack) variable scope.
     * Used by native functions that need to read or modify variable bindings.
     */
    public Map<String, ValueSpecification> currentScope()
    {
        return varStack.peek();
    }

    /**
     * Push a new variable scope (copying the current one) onto the stack.
     * The new scope inherits all bindings from the current scope.
     * Must be paired with {@link #popScope()}.
     */
    public void pushScope()
    {
        varStack.push(new HashMap<>(varStack.peek()));
    }

    /**
     * Pop the top variable scope from the stack, restoring the previous scope.
     */
    public void popScope()
    {
        varStack.pop();
    }

    /**
     * Push an instance onto the construction stack.
     * Used by {@code new} and {@code copy} natives to track the instance hierarchy
     * during construction, enabling parent-reference ({@code ~}) resolution.
     */
    public void pushConstruction(Object instance)
    {
        constructionStack.push(instance);
    }

    /**
     * Pop the top instance from the construction stack.
     */
    public void popConstruction()
    {
        constructionStack.pop();
    }

    /**
     * Peek at the construction stack at the given depth.
     * Depth 0 = top of stack (self), depth 1 = parent, etc.
     *
     * @return the instance at the given depth, or null if out of bounds
     */
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
        return null;
    }

    /**
     * Evaluate a value specification using the current variable scope.
     *
     * @param vs the value specification to evaluate
     * @return the result as a ValueSpecification
     */
    public ValueSpecification evaluate(ValueSpecification vs)
    {
        return switch (vs)
        {
            case AtomicValue av -> captureLambdaClosureIfNeeded(av);
            case VariableExpression ve ->
            {
                String name = ve._name();
                ValueSpecification rv = varStack.peek().get(name);
                if (rv == null)
                {
                    throw new RuntimeException("Unknown variable: " + name);
                }
                yield rv;
            }
            case Collection col ->
            {
                MutableList<ValueSpecification> evaluatedValues = org.eclipse.collections.api.factory.Lists.mutable.empty();
                for (ValueSpecification v : col._values())
                {
                    evaluatedValues.add(evaluate(v));
                }
                yield new meta.pure.metamodel.valuespecification.CollectionImpl()
                        ._values(evaluatedValues)
                        ._genericType(vs._genericType())
                        ._multiplicity(vs._multiplicity());
            }
            // GenericTypeAndMultiplicityHolder (and subtypes) carry type annotations — pass through as-is
            case GenericTypeAndMultiplicityHolder gmh -> (ValueSpecification) gmh;
            case FunctionExpression fe -> evaluateFunctionExpression(fe);
            default -> throw new RuntimeException(
                    "Unsupported ValueSpecification type: " + vs.getClass().getSimpleName());
        };
    }

    /**
     * If the AtomicValue wraps a {@link LambdaFunction} with open variables,
     * capture the current (definition-site) scope bindings for those variables
     * into a {@link Closure}, and return a new AtomicValue wrapping the Closure.
     *
     * <p>This ensures the lambda carries its own captured bindings and does not
     * accidentally pick up identically-named variables from a later call site.</p>
     *
     * @param av the atomic value to inspect
     * @return the original av (if not a lambda), or a new av wrapping a Closure
     */
    private AtomicValue captureLambdaClosureIfNeeded(AtomicValue av)
    {
        Object value = av._value();
        if (!(value instanceof LambdaFunction lambda))
        {
            return av;
        }

        MutableList<VariableExpression> openVars = lambda._openVariables();
        if (openVars == null || openVars.isEmpty())
        {
            return av;
        }

        // Snapshot only the open-variable bindings from the current (definition-site) scope
        Map<String, ValueSpecification> currentScope = varStack.peek();
        Map<String, ValueSpecification> captured = new HashMap<>();
        for (VariableExpression ov : openVars)
        {
            String name = ov._name();
            ValueSpecification binding = currentScope.get(name);
            if (binding != null)
            {
                captured.put(name, binding);
            }
        }

        Closure closure = new Closure(lambda, captured);
        return (AtomicValue) ((AtomicValue) av._copy())
                ._value(closure)
                ._genericType(av._genericType());
    }

    private ValueSpecification evaluateFunctionExpression(FunctionExpression fe)
    {
        callStack.push(fe);
        try
        {
            meta.pure.metamodel.function.Function func = fe._func();
            return switch (func)
            {
                case meta.pure.metamodel.function.property.AbstractProperty prop ->
                        evaluatePropertyFunc(fe, prop);

                case NativeFunction nf ->
                {
                    String signature = nf._name();
                    if (natives.isLazy(signature))
                    {
                        yield natives.executeLazy(signature, this, fe);
                    }
                    yield natives.execute(signature, evaluateArgs(fe), this, fe._genericType(), fe._multiplicity());
                }

                case FunctionDefinition fd ->
                {
                    pushScope();
                    try
                    {
                        yield evaluateFunctionDefinition(fd, evaluateArgs(fe));
                    }
                    finally
                    {
                        popScope();
                    }
                }

                default -> throw new RuntimeException("Unsupported function: "
                        + ((PackageableFunction) func)._name()
                        + " (type: " + func.getClass().getSimpleName() + ")");
            };
        }
        catch (RuntimeException e)
        {
            String msg = e.getMessage();
            if (msg != null && msg.contains("\nPure stack trace:"))
            {
                throw e;
            }
            throw new RuntimeException((msg == null ? "" : msg) + getCallStackTrace(), e);
        }
        finally
        {
            callStack.pop();
        }
    }

    private List<ValueSpecification> evaluateArgs(FunctionExpression fe)
    {
        MutableList<ValueSpecification> paramSpecs = fe._parametersValues();
        List<ValueSpecification> args = new ArrayList<>(paramSpecs.size());
        for (ValueSpecification param : paramSpecs)
        {
            args.add(evaluate(param));
        }
        return args;
    }

    /**
     * Evaluate a property access function expression.
     * The first parameter is the target object, the property is accessed by name.
     */
    private ValueSpecification evaluatePropertyFunc(FunctionExpression fe,
                                                     meta.pure.metamodel.function.property.AbstractProperty prop)
    {
        // QualifiedProperty extends both AbstractProperty and FunctionDefinition —
        // evaluate its expression sequence with $this bound to the target, plus extra args
        if (prop instanceof meta.pure.metamodel.function.property.QualifiedProperty qp)
        {
            MutableList<ValueSpecification> paramSpecs = fe._parametersValues();
            List<ValueSpecification> args = new ArrayList<>(paramSpecs.size());
            for (ValueSpecification param : paramSpecs)
            {
                args.add(evaluate(param));
            }

            // Bind type variable values from the target's classifierGenericType
            Object target = _E_ValueSpecification.unwrap(args.get(0));
            if (target instanceof DynamicInstance di && di.getClassifierGenericType() != null)
            {
                meta.pure.metamodel.type.generics.GenericType cgt = di.getClassifierGenericType();
                if (cgt instanceof meta.pure.metamodel.type.generics.GenericTypeValue gtv
                        && gtv._typeVariableValues() != null && gtv._typeVariableValues().notEmpty())
                {
                    meta.pure.metamodel.type.Type ownerType = _GenericType.type(cgt);
                    MutableList<VariableExpression> typeVars = null;
                    if (ownerType instanceof meta.pure.metamodel.type.Class cls)
                    {
                        typeVars = cls._typeVariables();
                    }
                    else if (ownerType instanceof meta.pure.metamodel.type.PrimitiveType pt)
                    {
                        typeVars = pt._typeVariables();
                    }
                    if (typeVars != null)
                    {
                        MutableList<ValueSpecification> typeVarVals = gtv._typeVariableValues();
                        int count = Math.min(typeVars.size(), typeVarVals.size());
                        for (int i = 0; i < count; i++)
                        {
                            varStack.peek().put(typeVars.get(i)._name(), typeVarVals.get(i));
                        }
                    }
                }
            }

            // Dynamic dispatch: use C3 linearization to find the most-specific
            // qualified property override based on the target's actual runtime type.
            qp = resolveQualifiedPropertyDispatch(qp, args);

            return evaluateFunctionDefinition(qp, args);
        }

        String propertyName = prop._name();
        MutableList<ValueSpecification> paramSpecs = fe._parametersValues();
        if (paramSpecs.isEmpty())
        {
            throw new RuntimeException("Property access '" + propertyName + "' has no target object");
        }

        // Evaluate the target object (first parameter)
        ValueSpecification targetVs = evaluate(paramSpecs.get(0));

        return accessProperty(targetVs, propertyName, fe._genericType(), fe._multiplicity());
    }

    /**
     * Resolve which QualifiedProperty to invoke using C3 linearization.
     *
     * <p>Walks the C3 method resolution order (MRO) of the target's actual
     * runtime type from most-specific to least-specific. The first class in
     * the MRO that declares a qualified property with the same name and
     * compatible parameter count is selected. This gives virtual dispatch
     * semantics: subclass overrides of qualified properties are called even
     * when the call site was compiled against a superclass type.</p>
     *
     * @param staticQP the statically-resolved qualified property from the call site
     * @param args     the already-evaluated arguments (first element is the target)
     * @return the most-specific matching QP, or {@code staticQP} if no override is found
     */
    private meta.pure.metamodel.function.property.QualifiedProperty resolveQualifiedPropertyDispatch(
            meta.pure.metamodel.function.property.QualifiedProperty staticQP,
            List<ValueSpecification> args)
    {
        if (args.isEmpty())
        {
            return staticQP;
        }

        Object target = _E_ValueSpecification.unwrap(args.get(0));
        if (!(target instanceof DynamicInstance di) || di.getClassifierGenericType() == null)
        {
            return staticQP;
        }

        meta.pure.metamodel.type.Type runtimeType = _GenericType.type(di.getClassifierGenericType());
        if (runtimeType == null)
        {
            return staticQP;
        }

        String qpName = staticQP._name();
        int argCount = args.size();

        // Walk the C3 linearization from most-specific to least-specific
        MutableList<meta.pure.metamodel.type.Type> mro = _Type.linearize(runtimeType, this.natives.resolver());
        for (meta.pure.metamodel.type.Type type : mro)
        {
            if (type instanceof meta.pure.metamodel.type.Class cls && cls._qualifiedProperties() != null)
            {
                for (var candidateQP : cls._qualifiedProperties())
                {
                    if (qpName.equals(candidateQP._name())
                            && candidateQP._parameters() != null
                            && candidateQP._parameters().size() == argCount)
                    {
                        return candidateQP;
                    }
                }
            }
        }

        return staticQP;
    }

    private static final Object PROPERTY_NOT_FOUND = new Object();
    private static final Map<Class<?>, Map<String, Method>> METHOD_CACHE = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Access a property on a target object by name.
     * Returns the result as a {@link ValueSpecification}, wrapping raw values as needed.
     */
    private ValueSpecification accessProperty(ValueSpecification targetVs, String propertyName,
                                              GenericType genericType,
                                              meta.pure.metamodel.multiplicity.Multiplicity multiplicity)
    {
        Object target = _E_ValueSpecification.unwrap(targetVs);

        // DynamicInstance (from 'new' function) — property access via map
        if (target instanceof DynamicInstance di)
        {
            Object propertyValueRaw = "classifierGenericType".equals(propertyName) ? di.getClassifierGenericType() : di.get(propertyName);
            return wrapPropertyResult(propertyValueRaw, genericType, multiplicity);
        }

        // For metamodel objects, use reflection to find _<propertyName>() accessor
        Object effectiveTarget = target != null ? target : targetVs;
        Object result = reflectivePropertyGet(effectiveTarget, propertyName);

        // Fallback: try the ValueSpecification wrapper itself (for genericType, multiplicity)
        if (result == PROPERTY_NOT_FOUND && effectiveTarget != targetVs)
        {
            result = reflectivePropertyGet(targetVs, propertyName);
        }

        // Special fallback for Enumeration — look up enum value by property defaultValue
        if (result == PROPERTY_NOT_FOUND && effectiveTarget instanceof meta.pure.metamodel.type.Enumeration en)
        {
            if (en._properties() != null)
            {
                var prop = en._properties().detect(p -> propertyName.equals(p._name()));
                if (prop != null && prop._defaultValue() != null && prop._defaultValue()._expressionSequence() != null)
                {
                    ValueSpecification vs = prop._defaultValue()._expressionSequence().getFirst();
                    if (vs instanceof AtomicValue av)
                    {
                        result = av._value();
                    }
                }
            }
        }

        if (result == PROPERTY_NOT_FOUND)
        {
            throw new RuntimeException("Unknown property '" + propertyName + "' on "
                    + (effectiveTarget == null ? "null" : effectiveTarget.getClass().getSimpleName()));
        }

        return wrapPropertyResult(result, genericType, multiplicity);
    }

    /**
     * Use reflection to invoke {@code _<propertyName>()} on the target.
     * Returns {@link #PROPERTY_NOT_FOUND} if no such method exists.
     */
    private static Object reflectivePropertyGet(Object target, String propertyName)
    {
        if (target == null)
        {
            throw new RuntimeException("Cannot access property '" + propertyName + "' because target is null");
        }
        Method method = lookupMethod(target.getClass(), propertyName);
        if (method == null)
        {
            return PROPERTY_NOT_FOUND;
        }
        try
        {
            return method.invoke(target);
        }
        catch (InvocationTargetException e)
        {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException re)
            {
                throw re;
            }
            throw new RuntimeException("Error accessing property '" + propertyName + "'", cause);
        }
        catch (IllegalAccessException e)
        {
            throw new RuntimeException("Cannot access property '" + propertyName + "'", e);
        }
    }

    private static Method lookupMethod(Class<?> cls, String propertyName)
    {
        Map<String, Method> classCache = METHOD_CACHE.computeIfAbsent(cls, c -> new java.util.concurrent.ConcurrentHashMap<>());
        return classCache.computeIfAbsent(propertyName, name ->
        {
            try
            {
                return cls.getMethod("_" + name);
            }
            catch (NoSuchMethodException e)
            {
                return null;
            }
        });
    }

    /**
     * Wrap a raw property result into a {@link ValueSpecification}.
     * Handles null, {@link ValueSpecification}, {@link java.util.List}, and scalar values.
     */
    private ValueSpecification wrapPropertyResult(Object result,
                                                  GenericType genericType,
                                                  meta.pure.metamodel.multiplicity.Multiplicity multiplicity)
    {
        if (result instanceof ValueSpecification vs)
        {
            return vs;
        }
        if (result instanceof java.util.List<?> resultList)
        {
            List<ValueSpecification> vsItems = new ArrayList<>(resultList.size());
            for (Object item : resultList)
            {
                vsItems.add(_E_ValueSpecification.wrap(item, genericType, null, this.natives.resolver()));
            }
            return CollectionNatives.makeCollection(vsItems, this.natives.resolver());
        }
        return _E_ValueSpecification.wrap(result, genericType, multiplicity, this.natives.resolver());
    }

    /**
     * Evaluate a FunctionDefinition (user-defined or lambda) by binding
     * its parameters to the provided arguments and evaluating its
     * expression sequence.
     *
     * <p>If the {@code fd} is a {@link Closure}, its captured definition-site
     * scope is used as the base scope instead of the current call-site scope.
     * This ensures proper lexical scoping for lambdas with open variables.</p>
     */
    public ValueSpecification evaluateFunctionDefinition(FunctionDefinition fd, List<ValueSpecification> args)
    {
        if (fd == null)
        {
            throw new RuntimeException("Cannot evaluate null FunctionDefinition");
        }

        // Use the closure's captured scope if available; otherwise inherit the call-site scope.
        Map<String, ValueSpecification> baseScope = (fd instanceof Closure closure)
                ? closure.capturedScope()
                : varStack.peek();
        Map<String, ValueSpecification> childVars = new HashMap<>(baseScope);
        MutableList<VariableExpression> params = fd._parameters();
        if (params != null)
        {
            for (int i = 0; i < Math.min(params.size(), args.size()); i++)
            {
                VariableExpression param = params.get(i);
                ValueSpecification arg = args.get(i);
                String paramName = param._name();
                // Preserve the argument's specific genericType (e.g., Integer for literal 1)
                // instead of widening to the parameter's declared type (e.g., Any)
                meta.pure.metamodel.type.generics.GenericType gt =
                        (arg._genericType() != null) ? arg._genericType() : param._genericType();
                meta.pure.metamodel.multiplicity.Multiplicity mul =
                        (arg._multiplicity() != null) ? arg._multiplicity() : param._multiplicity();
                // Bind the parameter: preserve the arg's own type info where available.
                // For Collection args, use them directly (their per-element VS types are already correct).
                // For AtomicValue args, restamp with the resolved gt/mul without unwrapping.
                ValueSpecification boundArg;
                if (arg instanceof meta.pure.metamodel.valuespecification.Collection)
                {
                    boundArg = arg;
                }
                else if (arg._genericType() == gt && arg._multiplicity() == mul)
                {
                    boundArg = arg;
                }
                else
                {
                    boundArg = _E_ValueSpecification.wrap(_E_ValueSpecification.unwrap(arg), gt, mul, this.natives.resolver());
                }
                childVars.put(paramName, boundArg);
            }
        }

        // Push child scope and evaluate expression sequence
        varStack.push(childVars);
        ValueSpecification result = null;
        try
        {
            for (ValueSpecification expr : fd._expressionSequence())
            {
                result = evaluate(expr);
            }
        }
        finally
        {
            varStack.pop();
        }
        return result;
    }

    /**
     * Execute a function value passed as a {@link ValueSpecification} with the given arguments.
     * The function VS is unwrapped once, here, and dispatched on its concrete type.
     * Callers never need to unwrap the function themselves.
     * <ul>
     *   <li>{@link FunctionDefinition} — evaluates expression sequence with bound parameters</li>
     *   <li>{@link NativeFunction} — delegates to the native registry</li>
     *   <li>{@link meta.pure.metamodel.function.property.AbstractProperty} — accesses property on first arg</li>
     * </ul>
     */
    public ValueSpecification executeFunction(ValueSpecification fnVS, List<ValueSpecification> args)
    {
        return switch (_E_ValueSpecification.unwrap(fnVS))
        {
            case FunctionDefinition fd -> evaluateFunctionDefinition(fd, args);
            case NativeFunction nf ->
            {
                String signature = nf._name();
                yield natives.execute(signature, args, this, nf._returnGenericType(), nf._returnMultiplicity());
            }
            case meta.pure.metamodel.function.property.AbstractProperty prop ->
            {
                if (args.isEmpty())
                {
                    throw new RuntimeException("Property '" + prop._name() + "' requires a target object");
                }
                yield accessProperty(args.get(0), prop._name(), prop._genericType(), prop._multiplicity());
            }
            case null -> throw new RuntimeException("Cannot execute null function");
            default -> throw new RuntimeException(
                    "Cannot execute function of type: " + _E_ValueSpecification.unwrap(fnVS).getClass().getSimpleName());
        };
    }

    /**
     * Format a source frame string from a FunctionExpression's source information.
     */
    private static String formatSourceFrame(FunctionExpression fe)
    {
        String functionName = fe._functionName() != null ? fe._functionName() : "?";
        SourceInformation si = fe._sourceInformation();
        if (si != null)
        {
            String sourceId = si._sourceId() != null ? si._sourceId() : "";
            return functionName + " (" + sourceId + ":" + si._startLine() + "c" + si._startColumn() + ")";
        }
        return functionName;
    }

    /**
     * Return the current Pure call stack as a formatted string.
     */
    public String getCallStackTrace()
    {
        if (callStack.isEmpty())
        {
            return "";
        }
        StringBuilder sb = new StringBuilder("\nPure stack trace:");
        for (FunctionExpression frame : callStack)
        {
            sb.append("\n    at ").append(formatSourceFrame(frame));
        }
        return sb.toString();
    }
}
