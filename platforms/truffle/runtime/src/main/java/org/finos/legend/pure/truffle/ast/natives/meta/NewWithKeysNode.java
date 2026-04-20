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

        // Set classifier generic type from the type holder's first type argument
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
            else if (instance instanceof Any any && !"Unknown".equals(classPath))
            {
                // Build CGT from the resolved class path when no type arguments
                Object typeElement = eval.resolver().getElement(classPath);
                if (typeElement instanceof org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.type.Type t)
                {
                    var cgt = new org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.type.generics.UserDefinedGenericTypeImpl();
                    cgt._type(t);
                    any._classifierGenericType(cgt);
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
                    org.finos.legend.pure.truffle.types.PureSequence exprSeq = keImpl._expression();
                    Object propValue;
                    if (exprSeq == null || exprSeq.isEmpty())
                    {
                        propValue = org.finos.legend.pure.truffle.types.PureSequence.EMPTY;
                    }
                    else if (exprSeq.size() == 1)
                    {
                        propValue = exprSeq.getBoxed(0);
                    }
                    else
                    {
                        propValue = exprSeq;
                    }
                    eval.accessProperty(instance, propName, propValue);
                    keyValues.add(java.util.Map.entry(propName, propValue));
                }
            }

            // Set reverse association pointers (bidirectional binding)
            setReverseAssociationPointers(instance, classPath, keyValues, eval);

            // Validate constraints after all properties are set
            if (instance instanceof org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.type.Any any)
            {
                var cgt = any._classifierGenericType();
                if (cgt != null)
                {
                    var type = org.finos.legend.pure.truffle.runtime.helper._GenericType.type(cgt);
                    if (type != null)
                    {
                        CastNode.validateConstraints(type, cgt, instance, eval.resolver());
                    }
                }
            }

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
        for (java.util.Map.Entry<String, Object> kv : keyValues)
        {
            String propName = kv.getKey();
            Object propValue = kv.getValue();
            if (propValue == null || (propValue instanceof org.finos.legend.pure.truffle.types.PureSequence ps2 && ps2.isEmpty()))
            {
                continue;
            }

            // Find the reverse property name by scanning associations
            String reversePropName = findReverseAssociationProperty(propName, classPath, eval.resolver());
            if (reversePropName != null)
            {
                appendToProperty(propValue, reversePropName, instance, eval);
            }
        }
    }

    /**
     * Find the reverse association property for a given property on a class.
     * Scans all Association elements from the resolver to find the one that
     * owns the property, then returns the OTHER property's name.
     */
    static String findReverseAssociationProperty(String propName, String classPath,
                                                          org.finos.legend.pure.truffle.runtime.TruffleMetadataAccess resolver)
    {
        for (String path : resolver.elementPaths())
        {
            Object element = resolver.getElement(path);
            if (!(element instanceof org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.relationship.Association assoc))
            {
                continue;
            }
            PureSequence props = assoc._properties();
            if (props == null || props.size() != 2)
            {
                continue;
            }
            // Check if one of the association's properties matches propName
            Property matchProp = null;
            Property otherProp = null;
            Object p0 = props.getBoxed(0);
            Object p1 = props.getBoxed(1);
            if (p0 instanceof Property prop0 && p1 instanceof Property prop1)
            {
                if (propName.equals(prop0._name()))
                {
                    matchProp = prop0;
                    otherProp = prop1;
                }
                else if (propName.equals(prop1._name()))
                {
                    matchProp = prop1;
                    otherProp = prop0;
                }
            }
            if (matchProp != null && otherProp != null)
            {
                // Verify that the OTHER property's target type matches the class being constructed.
                // otherProp points TO classPath. matchProp points AWAY from classPath.
                // We check that otherProp's genericType resolves to a type whose path matches classPath.
                if (otherProp._genericType() != null)
                {
                    org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.type.Type targetType =
                            org.finos.legend.pure.truffle.runtime.helper._GenericType.type(otherProp._genericType());
                    if (targetType instanceof org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.PackageableElement pe)
                    {
                        String targetPath = org.finos.legend.pure.truffle.runtime.helper._PackageableElement.path(pe);
                        if (classPath.equals(targetPath))
                        {
                            return otherProp._name();
                        }
                        // Check subtype: classPath may be a subtype of targetPath
                        Object classElement = resolver.getElement(classPath);
                        if (classElement instanceof org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.type.Type classType)
                        {
                            var mro = org.finos.legend.pure.truffle.runtime.helper._Type.linearize(classType, resolver);
                            for (var ancestor : mro)
                            {
                                if (ancestor instanceof org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.PackageableElement ape
                                        && targetPath.equals(org.finos.legend.pure.truffle.runtime.helper._PackageableElement.path(ape)))
                                {
                                    return otherProp._name();
                                }
                            }
                        }
                    }
                }
                // If type check fails, still return if no better match found later
            }
        }
        return null;
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
            boolean isEmpty = current == null
                    || (current instanceof org.finos.legend.pure.truffle.types.PureSequence ps3 && ps3.isEmpty())
                    || (current instanceof PureSequence seq && seq.isEmpty());
            if (isEmpty)
            {
                eval.accessProperty(target, propName, value);
            }
            else if (current instanceof PureSequence seq)
            {
                // Append to existing PureSequence
                Object[] items = new Object[seq.size() + 1];
                for (int j = 0; j < seq.size(); j++)
                {
                    items[j] = seq.getBoxed(j);
                }
                items[seq.size()] = value;
                eval.accessProperty(target, propName, new org.finos.legend.pure.truffle.types.ObjectSequence(items));
            }
            else if (current instanceof org.eclipse.collections.api.list.MutableList<?> list)
            {
                @SuppressWarnings("unchecked")
                org.eclipse.collections.api.list.MutableList<Object> mlist =
                        (org.eclipse.collections.api.list.MutableList<Object>) list;
                mlist.add(value);
            }
            else
            {
                eval.accessProperty(target, propName,
                        org.eclipse.collections.api.factory.Lists.mutable.with(current, value));
            }
        }
        catch (Exception ignored)
        {
        }
    }

    /**
     * Unwrap VS one level for the execution stack:
     * AtomicValue → raw value, Collection → PureSequence of unwrapped values.
     */
    private static Object unwrapVS(Object value)
    {
        if (value instanceof org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.valuespecification.AtomicValue av && av._value() != null)
        {
            return av._value();
        }
        if (value instanceof org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.valuespecification.Collection col)
        {
            var vals = col._values();
            if (vals == null || vals.isEmpty()) return org.finos.legend.pure.truffle.types.PureSequence.EMPTY;
            Object[] unwrapped = new Object[vals.size()];
            for (int i = 0; i < vals.size(); i++)
            {
                Object elem = vals.getBoxed(i);
                if (elem instanceof org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.valuespecification.AtomicValue av && av._value() != null)
                {
                    unwrapped[i] = av._value();
                }
                else
                {
                    unwrapped[i] = elem;
                }
            }
            return new org.finos.legend.pure.truffle.types.ObjectSequence(unwrapped);
        }
        return value;
    }
}
