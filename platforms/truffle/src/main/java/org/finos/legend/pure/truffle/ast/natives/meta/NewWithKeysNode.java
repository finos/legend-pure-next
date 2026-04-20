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

package org.finos.legend.pure.truffle.ast.natives.meta;

import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.NodeInfo;
import meta.pure.metamodel.valuespecification.FunctionExpression;
import org.finos.legend.pure.truffle.ast.PureNode;
import org.finos.legend.pure.truffle.runtime.StandaloneEvaluatorHolder;

/**
 * {@code new(GenericTypeAndMultiplicityHolder[1], KeyExpression[*]) : T[1]}
 * -- lazy native: construction stack + key expression evaluation.
 * Delegates to StandaloneEvaluator for the complex construction logic.
 */
@NodeInfo(shortName = "newWithKeys")
public final class NewWithKeysNode extends PureNode
{
    @CompilationFinal
    private final String signature;

    @CompilationFinal
    private final FunctionExpression fe;

    public NewWithKeysNode(String signature, FunctionExpression fe)
    {
        this.signature = signature;
        this.fe = fe;
    }

    @Override
    public Object executeGeneric(VirtualFrame frame)
    {
        return invoke();
    }

    private Object invoke()
    {
        // Delegate to StandaloneEvaluator which manages the construction
        // stack. The FE's parametersValues contain: [0]=type holder, [1]=key exprs.
        // We evaluate them lazily through the evaluator.
        org.finos.legend.pure.truffle.StandaloneEvaluator eval =
                org.finos.legend.pure.truffle.runtime.StandaloneEvaluatorHolder.current();

        // Evaluate type holder (first param)
        Object typeHolder = eval.astBuilder().lower(fe._parametersValues().get(0))
                .executeGeneric(eval.currentFrame());

        // Extract class path from type holder
        String classPath = "Unknown";
        if (typeHolder instanceof meta.pure.metamodel.valuespecification.GenericTypeAndMultiplicityHolder gtmh
                && gtmh._genericType() != null)
        {
            var typeArgs = org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._GenericType
                    .typeArguments(gtmh._genericType());
            if (typeArgs != null && typeArgs.notEmpty())
            {
                classPath = org.finos.legend.pure.execution.natives.meta.MetaNatives
                        .resolveClassPathFromCGT(typeArgs.getFirst());
            }
        }

        // Create instance
        Object instance = org.finos.legend.pure.execution.natives.meta.MetaNatives
                .createInstanceByPath(classPath);

        // Set classifier generic type
        if (typeHolder instanceof meta.pure.metamodel.valuespecification.GenericTypeAndMultiplicityHolder gtmh
                && gtmh._genericType() != null)
        {
            var typeArgs = org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._GenericType
                    .typeArguments(gtmh._genericType());
            if (typeArgs != null && typeArgs.notEmpty())
            {
                if (instance instanceof meta.pure.metamodel.type.Any any)
                {
                    any._classifierGenericType(
                            (meta.pure.metamodel.type.generics.GenericTypeValue) typeArgs.getFirst());
                }
            }
        }

        // Push onto construction stack, evaluate key expressions, process them
        eval.pushConstruction(instance);
        try
        {
            // Evaluate key expressions (second param)
            Object keyExprsResult = eval.astBuilder().lower(fe._parametersValues().get(1))
                    .executeGeneric(eval.currentFrame());

            // Process key expressions — each is a KeyExpressionImpl with {name, expression}
            int sz = org.finos.legend.pure.truffle.ast.natives.collection.CollectionHelper.size(keyExprsResult);
            java.util.List<java.util.Map.Entry<String, Object>> keyValues = new java.util.ArrayList<>();
            for (int i = 0; i < sz; i++)
            {
                Object ke = org.finos.legend.pure.truffle.ast.natives.collection.CollectionHelper.at(keyExprsResult, i);
                if (ke instanceof meta.pure.functions.lang.KeyExpressionImpl keImpl)
                {
                    String propName = keImpl._name();
                    if ("classifierGenericType".equals(propName))
                    {
                        throw new RuntimeException("Cannot set 'classifierGenericType' directly. "
                                + "This field is system-managed and derived from the instantiation. "
                                + "Use meta::pure::functions::lang::new(GenericType[1]) to create instances "
                                + "with a specific classifierGenericType.");
                    }
                    Object propValue = keImpl._expression();
                    eval.accessProperty(instance, propName, propValue);
                    keyValues.add(java.util.Map.entry(propName, propValue));
                }
            }

            // Set reverse association pointers (bidirectional binding)
            setReverseAssociationPointers(instance, classPath, keyValues, eval);

            return instance;
        }
        finally
        {
            eval.popConstruction();
        }
    }

    /**
     * After constructing an instance, set reverse association pointers.
     * If person.firm = firmX, then firmX.employees should include person.
     */
    static void setReverseAssociationPointers(Object instance, String classPath,
                                              java.util.List<java.util.Map.Entry<String, Object>> keyValues,
                                              org.finos.legend.pure.truffle.StandaloneEvaluator eval)
    {
        if (keyValues.isEmpty())
        {
            return;
        }
        meta.pure.metamodel.PackageableElement classElement = eval.resolver().getElement(classPath);
        if (!(classElement instanceof meta.pure.metamodel.type.Class cls))
        {
            return;
        }
        // Collect association properties from class hierarchy
        org.eclipse.collections.api.list.MutableList<meta.pure.metamodel.function.property.Property> assocProps =
                cls._propertiesFromAssociations();
        if (assocProps == null || assocProps.isEmpty())
        {
            return;
        }

        for (java.util.Map.Entry<String, Object> kv : keyValues)
        {
            String propName = kv.getKey();
            Object propValue = kv.getValue();
            if (propValue == null || propValue instanceof org.finos.legend.pure.truffle.types.PureSequence _ps1 && _ps1.isEmpty())
            {
                continue;
            }

            // Find matching association property
            meta.pure.metamodel.function.property.Property assocProp =
                    assocProps.detect(p -> propName.equals(p._name()));
            if (assocProp == null)
            {
                continue;
            }

            // Find the reverse property on the association owner
            meta.pure.metamodel.SimplePropertyOwner owner = assocProp._owner();
            if (owner == null || owner._properties() == null)
            {
                continue;
            }
            for (meta.pure.metamodel.function.property.Property otherProp : owner._properties())
            {
                if (otherProp != null && !propName.equals(otherProp._name()))
                {
                    String reversePropName = otherProp._name();
                    // Set reverse pointer: propValue.reversePropName = instance
                    appendToProperty(propValue, reversePropName, instance, eval);
                    break;
                }
            }
        }
    }

    private static void appendToProperty(Object target, String propName, Object value,
                                          org.finos.legend.pure.truffle.StandaloneEvaluator eval)
    {
        if (target instanceof java.util.List<?> targets)
        {
            for (Object t : targets)
            {
                appendToProperty(t, propName, value, eval);
            }
            return;
        }
        if (target instanceof org.finos.legend.pure.truffle.types.PureSequence seq)
        {
            for (int i = 0; i < seq.size(); i++)
            {
                appendToProperty(seq.getBoxed(i), propName, value, eval);
            }
            return;
        }
        // Get current value of the property
        try
        {
            Object current = eval.accessProperty(target, propName);
            if (current == null || current instanceof org.finos.legend.pure.truffle.types.PureSequence _ps2 && _ps2.isEmpty())
            {
                // Single value — set directly
                eval.accessProperty(target, propName, value);
            }
            else if (current instanceof org.eclipse.collections.api.list.MutableList<?> list)
            {
                // Many-valued — add to existing list
                @SuppressWarnings("unchecked")
                org.eclipse.collections.api.list.MutableList<Object> mlist =
                        (org.eclipse.collections.api.list.MutableList<Object>) list;
                mlist.add(value);
            }
            else
            {
                // Convert to list
                eval.accessProperty(target, propName,
                        org.eclipse.collections.api.factory.Lists.mutable.with(current, value));
            }
        }
        catch (Exception ignored)
        {
            // Property not found — skip
        }
    }
}
