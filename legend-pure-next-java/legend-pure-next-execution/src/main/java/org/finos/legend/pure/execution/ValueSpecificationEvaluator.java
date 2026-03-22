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

import meta.pure.metamodel.PackageableElement;
import meta.pure.metamodel.SourceInformation;
import meta.pure.metamodel.function.FunctionDefinition;
import meta.pure.metamodel.function.NativeFunction;
import meta.pure.metamodel.function.PackageableFunction;
import meta.pure.metamodel.valuespecification.AtomicValue;
import meta.pure.metamodel.valuespecification.Collection;
import meta.pure.metamodel.valuespecification.FunctionExpression;
import meta.pure.metamodel.valuespecification.GenericTypeAndMultiplicityHolder;
import meta.pure.metamodel.valuespecification.ValueSpecification;
import meta.pure.metamodel.valuespecification.VariableExpression;

import org.eclipse.collections.api.list.MutableList;
import org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._GenericType;

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
    private final Deque<String> callStack = new ArrayDeque<>();
    private final Deque<Map<String, ValueSpecification>> varStack = new ArrayDeque<>();

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
     * Evaluate a value specification using the current variable scope.
     *
     * @param vs the value specification to evaluate
     * @return the result as a ValueSpecification
     */
    public ValueSpecification evaluate(ValueSpecification vs)
    {
        return switch (vs)
        {
            case AtomicValue av -> av;
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
            // Lambda functions in parameter position — pass through as-is
            case FunctionDefinition fd -> (ValueSpecification) fd;
            // GenericTypeAndMultiplicityHolder (and subtypes) carry type annotations — pass through as-is
            case GenericTypeAndMultiplicityHolder gmh -> (ValueSpecification) gmh;
            case FunctionExpression fe -> evaluateFunctionExpression(fe);
            default -> throw new RuntimeException(
                    "Unsupported ValueSpecification type: " + vs.getClass().getSimpleName());
        };
    }

    private ValueSpecification evaluateFunctionExpression(FunctionExpression fe)
    {
        String sourceFrame = formatSourceFrame(fe);
        callStack.push(sourceFrame);
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
                    yield natives.execute(signature, evaluateArgs(fe), this, fe);
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

            // If the resolved QP's param count doesn't match the arg count,
            // search the owner class for the correct overload by name + param count
            if (qp._parameters() != null && qp._parameters().size() != args.size())
            {
                Object target2 = _E_ValueSpecification.unwrap(args.get(0));
                meta.pure.metamodel.type.Type ownerType2 = null;
                if (target2 instanceof DynamicInstance di2 && di2.getClassifierGenericType() != null)
                {
                    ownerType2 = _GenericType.type(di2.getClassifierGenericType());
                }
                if (ownerType2 instanceof meta.pure.metamodel.type.Class cls2 && cls2._qualifiedProperties() != null)
                {
                    String qpName = qp._name();
                    for (var candidateQP : cls2._qualifiedProperties())
                    {
                        if (qpName.equals(candidateQP._name())
                                && candidateQP._parameters() != null
                                && candidateQP._parameters().size() == args.size())
                        {
                            qp = candidateQP;
                            break;
                        }
                    }
                }
            }

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
        Object target = _E_ValueSpecification.unwrap(targetVs);

        // Access the property on the target
        Object result = accessProperty(target, targetVs, propertyName);
        return _E_ValueSpecification.wrap(result, fe._genericType(), fe._multiplicity());
    }

    /**
     * Access a property on a target object by name.
     */
    private Object accessProperty(Object target, ValueSpecification targetVs, String propertyName)
    {
        // DynamicInstance (from 'new' function) — property access via map
        if (target instanceof DynamicInstance di)
        {
            return di.get(propertyName);
        }

        // If the target is a ValueSpecification, try to access its properties
        if (target instanceof meta.pure.metamodel.type.generics.GenericTypeValue gt)
        {
            return switch (propertyName)
            {
                case "type" -> gt._type();
                case "typeArguments" -> gt._typeArguments();
                default -> throw new RuntimeException("Unknown property '" + propertyName + "' on GenericType");
            };
        }
        if (target instanceof meta.pure.metamodel.multiplicity.ConcreteMultiplicity cm)
        {
            return switch (propertyName)
            {
                case "lowerBound" -> cm._lowerBound();
                case "upperBound" -> cm._upperBound();
                default -> throw new RuntimeException("Unknown property '" + propertyName + "' on Multiplicity");
            };
        }
        // AbstractProperty meta-properties (before FunctionDefinition, since QualifiedProperty extends both)
        if (target instanceof meta.pure.metamodel.function.property.AbstractProperty ap)
        {
            return switch (propertyName)
            {
                case "name" -> ap._name();
                case "genericType" -> ap._genericType();
                case "multiplicity" -> ap._multiplicity();
                case "owner" -> ap._owner();
                default -> throw new RuntimeException("Unknown property '" + propertyName + "' on AbstractProperty");
            };
        }

        if (target instanceof meta.pure.metamodel.function.FunctionDefinition fd)
        {
            return switch (propertyName)
            {
                case "expressionSequence" -> fd._expressionSequence();
                case "parameters" -> fd._parameters();
                case "name" -> (fd instanceof meta.pure.metamodel.function.PackageableFunction pf) ? pf._name() : null;
                case "classifierGenericType" -> fd._classifierGenericType();
                case "package" -> (fd instanceof meta.pure.metamodel.PackageableElement pe2) ? pe2._package() : null;
                case "elementOverride" -> null; // Not commonly used at runtime
                default -> null; // Return null for unresolved meta-level properties
            };
        }

        // Class meta-properties (for meta-reflection)
        if (target instanceof meta.pure.metamodel.type.Class cls)
        {
            return switch (propertyName)
            {
                case "properties" -> cls._properties();
                case "qualifiedProperties" -> cls._qualifiedProperties();
                case "propertiesFromAssociations" -> cls._propertiesFromAssociations();
                case "generalizations" -> cls._generalizations();
                case "name" -> cls._name();
                case "classifierGenericType" -> cls._classifierGenericType();
                case "package" -> cls._package();
                default -> throw new RuntimeException("Unknown property '" + propertyName + "' on Class");
            };
        }

        // Enumeration meta-properties
        if (target instanceof meta.pure.metamodel.type.Enumeration en)
        {
            return switch (propertyName)
            {
                case "name" -> en._name();
                case "package" -> en._package();
                case "classifierGenericType" -> en._classifierGenericType();
                default ->
                {
                    // Look up enum value property by name and return its defaultValue
                    if (en._properties() != null)
                    {
                        var prop = en._properties().detect(p -> propertyName.equals(p._name()));
                        if (prop != null && prop._defaultValue() != null && prop._defaultValue()._expressionSequence() != null)
                        {
                            meta.pure.metamodel.valuespecification.ValueSpecification vs = prop._defaultValue()._expressionSequence().getFirst();
                            if (vs instanceof meta.pure.metamodel.valuespecification.AtomicValue av)
                            {
                                yield av._value();
                            }
                        }
                    }
                    throw new RuntimeException("Unknown property '" + propertyName + "' on Enumeration");
                }
            };
        }

        // Generalization meta-properties
        if (target instanceof meta.pure.metamodel.relationship.Generalization gen)
        {
            return switch (propertyName)
            {
                case "general" -> gen._general();
                default -> throw new RuntimeException("Unknown property '" + propertyName + "' on " + target.getClass().getSimpleName());
            };
        }

        // PackageableElement generic properties
        if (target instanceof PackageableElement pe)
        {
            return switch (propertyName)
            {
                case "name" -> pe._name();
                case "package" -> pe._package();
                case "classifierGenericType" -> pe._classifierGenericType();
                default -> throw new RuntimeException("Unknown property '" + propertyName + "' on " + pe.getClass().getSimpleName());
            };
        }

        // Generic property access on the ValueSpecification itself
        return switch (propertyName)
        {
            case "genericType" -> targetVs._genericType();
            case "multiplicity" -> targetVs._multiplicity();
            case "name" ->
            {
                throw new RuntimeException("Cannot access 'name' on " + (target == null ? "null" : target.getClass().getSimpleName()));
            }
            default -> throw new RuntimeException("Unknown property '" + propertyName + "' on " + (target == null ? "null" : target.getClass().getSimpleName()));
        };
    }

    /**
     * Evaluate a FunctionDefinition (user-defined or lambda) by binding
     * its parameters to the provided arguments and evaluating its
     * expression sequence.
     */
    public ValueSpecification evaluateFunctionDefinition(FunctionDefinition fd, List<ValueSpecification> args)
    {
        if (fd == null)
        {
            throw new RuntimeException("Cannot evaluate null FunctionDefinition");
        }

        // Build variable scope from parameter bindings — inherits parent scope for closures
        Map<String, ValueSpecification> childVars = new HashMap<>(varStack.peek());
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
                childVars.put(paramName, _E_ValueSpecification.wrap(_E_ValueSpecification.unwrap(arg), gt, mul));
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
     * Execute a function value (obtained at runtime) with the given arguments.
     * Dispatches on the runtime type of the function:
     * <ul>
     *   <li>{@link FunctionDefinition} — evaluates expression sequence with bound parameters</li>
     *   <li>{@link meta.pure.metamodel.function.property.AbstractProperty} — accesses property on first arg</li>
     * </ul>
     */
    public ValueSpecification executeFunction(Object fnValue, List<ValueSpecification> args)
    {
        return switch (fnValue)
        {
            case FunctionDefinition fd -> evaluateFunctionDefinition(fd, args);
            case meta.pure.metamodel.function.property.AbstractProperty prop ->
            {
                if (args.isEmpty())
                {
                    throw new RuntimeException("Property '" + prop._name() + "' requires a target object");
                }
                Object target = _E_ValueSpecification.unwrap(args.get(0));
                Object result = accessProperty(target, args.get(0), prop._name());
                yield _E_ValueSpecification.wrap(result, prop._genericType(), prop._multiplicity());
            }
            case null -> throw new RuntimeException("Cannot execute null function");
            default -> throw new RuntimeException(
                    "Cannot execute function of type: " + fnValue.getClass().getSimpleName());
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
        int depth = 0;
        for (String frame : callStack)
        {
            sb.append("\n    ").append(depth == 0 ? "at " : "at ").append(frame);
            depth++;
        }
        return sb.toString();
    }
}
