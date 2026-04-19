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
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.NodeInfo;
import org.finos.legend.pure.execution.natives.meta.MetaNatives;
import org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.PackageableElement;
import org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.SimplePropertyOwner;
import org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.function.property.Property;
import org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.type.Any;
import org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.type.generics.GenericTypeValue;
import org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.valuespecification.FunctionExpression;
import org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.valuespecification.GenericTypeAndMultiplicityHolder;
import org.finos.legend.pure.truffle.pdb.meta.pure.functions.lang.KeyExpression;
import org.finos.legend.pure.truffle.ast.PureNode;
import org.finos.legend.pure.truffle.ast.natives.collection.CollectionHelper;
import org.finos.legend.pure.truffle.runtime.StandaloneEvaluatorHolder;
import org.finos.legend.pure.truffle.runtime.helper._GenericType;
import org.finos.legend.pure.truffle.runtime.helper._PackageableElement;
import org.finos.legend.pure.truffle.types.PureSequence;

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

    @TruffleBoundary
    private Object invoke()
    {
        // Delegate to StandaloneEvaluator which manages the construction
        // stack. The FE's parametersValues contain: [0]=type holder, [1]=key exprs.
        // We evaluate them lazily through the evaluator.
        org.finos.legend.pure.truffle.StandaloneEvaluator eval =
                StandaloneEvaluatorHolder.current();

        // Evaluate type holder (first param)
        // _parametersValues() returns PureSequence; getBoxed() returns Object
        Object typeHolder = eval.astBuilder().lower(fe._parametersValues().getBoxed(0))
                .executeGeneric(eval.currentFrame());

        // Extract class path from type holder
        String classPath = "Unknown";
        if (typeHolder instanceof GenericTypeAndMultiplicityHolder gtmh
                && gtmh._genericType() != null)
        {
            Object gt = gtmh._genericType();
            PureSequence typeArgs = _GenericType.typeArguments(gt);
            if (typeArgs != null && typeArgs.size() > 0)
            {
                classPath = resolveClassPathFromTypeArg(typeArgs.getBoxed(0));
            }
            if ("Unknown".equals(classPath))
            {
                // Try direct type from the generic type
                var type = _GenericType.type(gt);
                if (type instanceof org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.PackageableElement pe)
                {
                    String path = org.finos.legend.pure.truffle.runtime.helper._PackageableElement.path(pe);
                    if (path != null && !path.isEmpty())
                    {
                        classPath = path;
                    }
                }
                if ("Unknown".equals(classPath))
                {
                    System.err.println("[NEW] Unknown: gt=" + gt.getClass().getSimpleName()
                            + " typeArgs=" + (typeArgs != null ? typeArgs.size() : "null")
                            + " type=" + (_GenericType.type(gt) != null ? _GenericType.type(gt).getClass().getSimpleName() : "null"));
                }
            }
        }

        // Create instance
        Object instance = org.finos.legend.pure.truffle.runtime.TruffleInstanceFactory.createInstance(classPath);

        // Set classifier generic type
        if (typeHolder instanceof GenericTypeAndMultiplicityHolder gtmh
                && gtmh._genericType() != null)
        {
            PureSequence typeArgs = _GenericType.typeArguments(gtmh._genericType());
            if (typeArgs != null && typeArgs.size() > 0)
            {
                Object firstArg = typeArgs.getBoxed(0);
                if (instance instanceof Any any && firstArg instanceof GenericTypeValue gtv)
                {
                    any._classifierGenericType(gtv);
                }
            }
        }

        // Push onto construction stack, evaluate key expressions, process them
        eval.pushConstruction(instance);
        try
        {
            // Evaluate key expressions (second param)
            Object keyExprsResult = eval.astBuilder().lower(fe._parametersValues().getBoxed(1))
                    .executeGeneric(eval.currentFrame());

            // Process key expressions — each is a KeyExpression with {name, expression}
            int sz = CollectionHelper.size(keyExprsResult);
            java.util.List<java.util.Map.Entry<String, Object>> keyValues = new java.util.ArrayList<>();
            for (int i = 0; i < sz; i++)
            {
                Object ke = CollectionHelper.at(keyExprsResult, i);
                if (ke instanceof KeyExpression keImpl)
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
     * Resolve a class path from a type argument (Object from PureSequence.getBoxed).
     * Uses truffle-namespaced helpers.
     */
    private static String resolveClassPathFromTypeArg(Object typeArg)
    {
        org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.type.Type rawType = _GenericType.type(typeArg);
        if (rawType instanceof PackageableElement pe)
        {
            String path = _PackageableElement.path(pe);
            if (path != null && !path.isEmpty())
            {
                return path;
            }
        }
        return "Unknown";
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
        // resolver.getElement() returns a bootstrap type at compile time,
        // but at runtime via PdbMaterializer returns truffle-typed objects.
        Object classElement = eval.resolver().getElement(classPath);
        if (!(classElement instanceof org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.type.Class cls))
        {
            return;
        }
        // Collect association properties from the class
        PureSequence assocPropsSeq = cls._propertiesFromAssociations();
        if (assocPropsSeq == null || assocPropsSeq.isEmpty())
        {
            return;
        }

        for (java.util.Map.Entry<String, Object> kv : keyValues)
        {
            String propName = kv.getKey();
            Object propValue = kv.getValue();
            if (propValue == null || propValue instanceof org.finos.legend.pure.truffle.types.PureNull)
            {
                continue;
            }

            // Find matching association property
            Property assocProp = null;
            for (Object p : assocPropsSeq.toBoxedArray())
            {
                if (p instanceof Property prop && propName.equals(prop._name()))
                {
                    assocProp = prop;
                    break;
                }
            }
            if (assocProp == null)
            {
                continue;
            }

            // Find the reverse property on the association owner
            SimplePropertyOwner owner = assocProp._owner();
            if (owner == null || owner._properties() == null)
            {
                continue;
            }
            PureSequence ownerProps = owner._properties();
            for (Object otherPropObj : ownerProps.toBoxedArray())
            {
                if (otherPropObj instanceof Property otherProp && !propName.equals(otherProp._name()))
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
        if (target instanceof PureSequence seq)
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
            if (current == null || current instanceof org.finos.legend.pure.truffle.types.PureNull)
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
