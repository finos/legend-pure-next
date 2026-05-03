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
import org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.PackageableElement;
import org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.SimplePropertyOwner;
import org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.function.property.Property;
import org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.type.Any;
import org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.type.generics.GenericTypeValue;
import org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.valuespecification.GenericTypeAndMultiplicityHolder;
import org.finos.legend.pure.truffle.ast.PureNode;
import org.finos.legend.pure.truffle.ast.RawLambdaCallNode;
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

    @Child
    private PureNode typeHolderNode;

    @Children
    private org.finos.legend.pure.truffle.ast.PropertyAssignNode[] assignments;

    /**
     * Original {@code KeyExpression[*]} arg PureNode. Drives the dynamic
     * path — used when the parser couldn't decompose {@code assignments[]}
     * at parse time. Two examples:
     *   - {@code [^KeyExpression(name=…, expression=…)]} (literal form, the
     *     KeyExpressions are runtime instances)
     *   - {@code $keyVar} (variable holding a list built earlier)
     * The static path (assignments[]) is the 99% case; the dynamic path
     * lets the remaining cases work.
     */
    @Child
    private PureNode keysNode;

    @Child
    private org.finos.legend.pure.truffle.ast.PropertyWriteNode dynamicKeyWriter = new org.finos.legend.pure.truffle.ast.PropertyWriteNode();

    @Child
    private RawLambdaCallNode constraintCallNode = new RawLambdaCallNode();

    @Child
    private org.finos.legend.pure.truffle.ast.PropertyReadNode appendReader = new org.finos.legend.pure.truffle.ast.PropertyReadNode();

    @Child
    private org.finos.legend.pure.truffle.ast.PropertyWriteNode appendWriter = new org.finos.legend.pure.truffle.ast.PropertyWriteNode();

    public NewWithKeysNode(String signature, PureNode typeHolderNode, org.finos.legend.pure.truffle.ast.PropertyAssignNode[] assignments)
    {
        this(signature, typeHolderNode, assignments, null);
    }

    public NewWithKeysNode(String signature, PureNode typeHolderNode, org.finos.legend.pure.truffle.ast.PropertyAssignNode[] assignments, PureNode keysNode)
    {
        this.signature = signature;
        this.typeHolderNode = typeHolderNode;
        this.assignments = assignments;
        this.keysNode = keysNode;
    }

    @Override
    @com.oracle.truffle.api.nodes.ExplodeLoop
    public Object executeGeneric(VirtualFrame frame)
    {
        return invoke(frame);
    }

    @com.oracle.truffle.api.nodes.ExplodeLoop
    private Object invoke(VirtualFrame frame)
    {
        // Delegate to the evaluator which manages the construction
        // stack. The FE's parametersValues contain: [0]=type holder, [1]=key exprs.
        // We evaluate them lazily through the evaluator.
        org.finos.legend.pure.truffle.PureContext eval = getContext();
        Object typeHolder = typeHolderNode.executeGeneric(frame);

        // Extract class path from type holder
        String classPath = "Unknown";
        if (typeHolder instanceof GenericTypeAndMultiplicityHolder gtmh
                && gtmh._genericType() != null)
        {
            Object gt = gtmh._genericType();
            PureSequence typeArgs = _GenericType.typeArguments(gt);
            if (typeArgs != null && typeArgs.size() > 0)
            {
                classPath = resolveClassPathFromTypeArg(typeArgs.getBoxed(0), eval.resolver());
            }
            if ("Unknown".equals(classPath))
            {
                // Try direct type from the generic type
                var type = _GenericType.type(gt);
                if (type instanceof org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.PackageableElement pe)
                {
                    String path = org.finos.legend.pure.truffle.runtime.helper._PackageableElement.path(pe, eval.resolver());
                    if (path != null && !path.isEmpty())
                    {
                        classPath = path;
                    }
                }
                if ("Unknown".equals(classPath))
                {
                    throw new RuntimeException("[NEW] Cannot resolve class path: gt=" + gt.getClass().getName()
                            + " typeArgs=" + (typeArgs != null ? typeArgs.size() : "null")
                            + " type=" + (_GenericType.type(gt) != null ? _GenericType.type(gt).getClass().getName() : "null"));
                }
            }
        }

        // Create instance
        Object instance = org.finos.legend.pure.truffle.runtime.TruffleInstanceFactory.createInstance(classPath, getResolver());

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
                    any._classifierGenericType(org.finos.legend.pure.truffle.runtime.helper._GenericType.buildUserDefinedGenericType(t, eval.resolver()));
                }
            }
        }

        // Verify CGT was set — null CGT causes downstream match failures
        if (instance instanceof Any anyCheck && anyCheck._classifierGenericType() == null)
        {
            throw new RuntimeException("[NEW] classifierGenericType is NULL after creation of " + classPath
                    + " (instance=" + instance.getClass().getName()
                    + ", typeHolder=" + (typeHolder != null ? typeHolder.getClass().getName() : "null")
                    + ", gt=" + (typeHolder instanceof GenericTypeAndMultiplicityHolder gtmh2 ? _GenericType.print(gtmh2._genericType(), eval.resolver()) : "n/a")
                    + ")");
        }

        // classifierGenericType is system-managed — derived from the type
        // holder above. Catching attempts to set it through the literal
        // ^Foo(classifierGenericType=...) syntax keeps a single source of
        // truth and matches the spec's testCantSetClassifierGenericType.
        for (int i = 0; i < assignments.length; i++)
        {
            if ("classifierGenericType".equals(assignments[i].propertyName()))
            {
                throw new org.finos.legend.pure.truffle.ast.PureException(
                        "Cannot set 'classifierGenericType' directly. This field is system-managed and derived from the instantiation. Use meta::pure::functions::lang::new(GenericType[1]) to create instances with a specific classifierGenericType.",
                        this);
            }
        }

        // Push onto construction stack for parentReference() access across call boundaries
        var ctx = org.finos.legend.pure.truffle.PureLanguage.get(this);
        ctx.pushConstruction(instance);
        try
        {
            java.util.List<java.util.Map.Entry<String, Object>> keyValues = new java.util.ArrayList<>();
            for (int i = 0; i < assignments.length; i++)
            {
                Object propValue = assignments[i].execute(frame, instance);
                if (propValue != null)
                {
                    keyValues.add(java.util.Map.entry(assignments[i].propertyName(), propValue));
                }
            }

            // Dynamic path: when the parser couldn't decompose KeyExpressions
            // at parse time (literal-form `^KeyExpression(name=…, expression=…)`
            // or a variable-bound list), evaluate the keys arg now and walk
            // the resulting KeyExpression instances to set properties.
            if (assignments.length == 0 && keysNode != null)
            {
                applyDynamicKeyExpressions(frame, instance, keyValues);
            }

            // Set reverse association pointers (bidirectional binding)
            setReverseAssociationPointers(instance, classPath, keyValues, eval, appendReader, appendWriter);

            // Validate constraints after all properties are set
            if (instance instanceof org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.type.Any any)
            {
                var cgt = any._classifierGenericType();
                if (cgt != null)
                {
                    var type = org.finos.legend.pure.truffle.runtime.helper._GenericType.type(cgt);
                    if (type != null)
                    {
                        CastNode.validateConstraints(type, cgt, instance, eval.resolver(), constraintCallNode);
                    }
                }
            }

            return instance;
        }
        finally
        {
            ctx.popConstruction();
        }
    }

    /**
     * Dynamic path: evaluate the keys arg and walk the resulting
     * {@code KeyExpression} instances, setting each one's named property
     * on the instance.
     *
     * <p>Each {@code KeyExpression} carries:
     *   - {@code _name()} — target property name on the new instance
     *   - {@code _expression()} — value to assign (already evaluated when
     *     the KeyExpression was constructed via {@code ^KeyExpression(…)})
     *   - {@code _add()} — true means append-to-list semantics
     * </p>
     */
    private void applyDynamicKeyExpressions(VirtualFrame frame, Object instance,
                                            java.util.List<java.util.Map.Entry<String, Object>> keyValues)
    {
        Object keys = keysNode.executeGeneric(frame);
        int len = org.finos.legend.pure.truffle.ast.natives.collection.CollectionHelper.size(keys);
        for (int i = 0; i < len; i++)
        {
            Object item = org.finos.legend.pure.truffle.ast.natives.collection.CollectionHelper.at(keys, i);
            if (!(item instanceof org.finos.legend.pure.truffle.pdb.meta.pure.functions.lang.KeyExpression ke))
            {
                throw new RuntimeException(
                        "new(...) expected KeyExpression in keys list at index " + i
                                + ", got " + (item == null ? "null" : item.getClass().getName()));
            }
            String propName = ke._name();
            if (propName == null)
            {
                throw new RuntimeException("KeyExpression at index " + i + " has no name");
            }
            Object value = unwrapSingleton(ke._expression());
            dynamicKeyWriter.execute(instance, propName, value);
            if (value != null)
            {
                keyValues.add(java.util.Map.entry(propName, value));
            }
        }
    }

    /**
     * Unwrap a single-element {@link PureSequence} to its element. Many
     * setters expect the unwrapped value (e.g. a {@code String}, not a
     * one-element sequence containing a String). Multi-element sequences
     * pass through as-is — the receiving setter will accept the list.
     */
    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    private static Object unwrapSingleton(Object value)
    {
        if (value instanceof PureSequence ps)
        {
            if (ps.size() == 1)
            {
                return ps.getBoxed(0);
            }
        }
        return value;
    }

    /**
     * Resolve a class path from a type argument (Object from PureSequence.getBoxed).
     * Uses truffle-namespaced helpers.
     */
    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    private static String resolveClassPathFromTypeArg(Object typeArg,
                                                       org.finos.legend.pure.truffle.runtime.TruffleMetadataAccess resolver)
    {
        org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.type.Type rawType = _GenericType.type(typeArg);
        if (rawType instanceof PackageableElement pe)
        {
            String path = _PackageableElement.path(pe, resolver);
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
                                              org.finos.legend.pure.truffle.PureContext eval,
                                              org.finos.legend.pure.truffle.ast.PropertyReadNode reader,
                                              org.finos.legend.pure.truffle.ast.PropertyWriteNode writer)
    {
        if (keyValues.isEmpty())
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

            String reversePropName = findReverseAssociationProperty(propName, classPath, eval.resolver());
            if (reversePropName != null)
            {
                appendToProperty(propValue, reversePropName, instance, reader, writer);
            }
        }
    }

    /**
     * Reverse association index: maps propertyName → list of (otherPropName, targetClassPath) pairs.
     * Built lazily on first access, then O(1) lookup per property.
     */
    private static volatile java.util.Map<String, java.util.List<String[]>> reverseAssocIndex;

    static String findReverseAssociationProperty(String propName, String classPath,
                                                          org.finos.legend.pure.truffle.runtime.TruffleMetadataAccess resolver)
    {
        java.util.Map<String, java.util.List<String[]>> index = reverseAssocIndex;
        if (index == null)
        {
            index = buildReverseAssocIndex(resolver);
            reverseAssocIndex = index;
        }

        java.util.List<String[]> candidates = index.get(propName);
        if (candidates == null)
        {
            return null;
        }

        for (String[] pair : candidates)
        {
            String otherPropName = pair[0];
            String targetPath = pair[1];
            if (classPath.equals(targetPath))
            {
                return otherPropName;
            }
            // Check subtype
            Object classElement = resolver.getElement(classPath);
            if (classElement instanceof org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.type.Type classType)
            {
                var mro = org.finos.legend.pure.truffle.runtime.helper._Type.linearize(classType, resolver);
                for (var ancestor : mro)
                {
                    if (ancestor instanceof org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.PackageableElement ape
                            && targetPath.equals(org.finos.legend.pure.truffle.runtime.helper._PackageableElement.path(ape, resolver)))
                    {
                        return otherPropName;
                    }
                }
            }
        }
        return null;
    }

    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    private static java.util.Map<String, java.util.List<String[]>> buildReverseAssocIndex(
            org.finos.legend.pure.truffle.runtime.TruffleMetadataAccess resolver)
    {
        java.util.Map<String, java.util.List<String[]>> index = new java.util.HashMap<>();
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
            Object p0 = props.getBoxed(0);
            Object p1 = props.getBoxed(1);
            if (p0 instanceof Property prop0 && p1 instanceof Property prop1)
            {
                addAssocEntry(index, prop0, prop1, resolver);
                addAssocEntry(index, prop1, prop0, resolver);
            }
        }
        return index;
    }

    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    private static void addAssocEntry(java.util.Map<String, java.util.List<String[]>> index,
                                       Property matchProp, Property otherProp,
                                       org.finos.legend.pure.truffle.runtime.TruffleMetadataAccess resolver)
    {
        String propName = matchProp._name();
        if (propName == null || otherProp._genericType() == null) return;
        org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.type.Type targetType =
                org.finos.legend.pure.truffle.runtime.helper._GenericType.type(otherProp._genericType());
        if (targetType instanceof org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.PackageableElement pe)
        {
            String targetPath = org.finos.legend.pure.truffle.runtime.helper._PackageableElement.path(pe, resolver);
            if (targetPath != null)
            {
                index.computeIfAbsent(propName, k -> new java.util.ArrayList<>(2))
                        .add(new String[]{otherProp._name(), targetPath});
            }
        }
    }

    // @TruffleBoundary — walks target.getClass().getMethods() to detect
    // multi-valued setter shape. Class.getMethodsRecursive PE was inlining
    // 990 deep. No frame here, so the boundary is safe.
    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    static void appendToProperty(Object target, String propName, Object value,
                                          org.finos.legend.pure.truffle.ast.PropertyReadNode reader,
                                          org.finos.legend.pure.truffle.ast.PropertyWriteNode writer)
    {
        if (target instanceof java.util.List<?> targets)
        {
            for (Object t : targets)
            {
                appendToProperty(t, propName, value, reader, writer);
            }
            return;
        }
        if (target instanceof PureSequence seq)
        {
            for (int i = 0; i < seq.size(); i++)
            {
                appendToProperty(seq.getBoxed(i), propName, value, reader, writer);
            }
            return;
        }
        // Determine if the property is multi-valued by checking the setter parameter type
        try
        {
            boolean isMulti = false;
            String setterName = "_" + propName;
            for (java.lang.reflect.Method m : target.getClass().getMethods())
            {
                if (m.getName().equals(setterName) && m.getParameterCount() == 1)
                {
                    Class<?> paramType = m.getParameterTypes()[0];
                    if (org.finos.legend.pure.truffle.types.PureSequence.class.isAssignableFrom(paramType)
                            || org.eclipse.collections.api.RichIterable.class.isAssignableFrom(paramType)
                            || java.util.Collection.class.isAssignableFrom(paramType))
                    {
                        isMulti = true;
                    }
                    break;
                }
            }

            if (!isMulti)
            {
                // [0..1] or [1] property — replace
                writer.execute(target, propName, value);
            }
            else
            {
                // [*] or [1..*] property — append to existing
                Object current = reader.execute(target, propName);
                boolean isEmpty = current == null
                        || (current instanceof org.finos.legend.pure.truffle.types.PureSequence ps3 && ps3.isEmpty())
                        || (current instanceof PureSequence seq && seq.isEmpty());
                if (isEmpty)
                {
                    writer.execute(target, propName, value);
                }
                else if (current instanceof PureSequence seq)
                {
                    Object[] items = new Object[seq.size() + 1];
                    for (int j = 0; j < seq.size(); j++)
                    {
                        items[j] = seq.getBoxed(j);
                    }
                    items[seq.size()] = value;
                    writer.execute(target, propName, new org.finos.legend.pure.truffle.types.ObjectSequence(items));
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
                    writer.execute(target, propName,
                            new org.finos.legend.pure.truffle.types.ObjectSequence(new Object[]{current, value}));
                }
            }
        }
        catch (Exception e)
        {
            throw new RuntimeException("Failed to set reverse association property '" + propName + "' on " + target.getClass().getName(), e);
        }
    }

    /**
     * Unwrap VS one level for the execution stack:
     * AtomicValue → raw value, Collection → PureSequence of unwrapped values.
     */
    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
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
