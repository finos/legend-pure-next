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
import org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.type.Any;
import org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.type.Type;
import org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.type.generics.GenericTypeValue;
import org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.valuespecification.FunctionExpression;
import org.finos.legend.pure.truffle.pdb.meta.pure.functions.lang.KeyExpression;
import org.finos.legend.pure.truffle.ast.PureNode;
import org.finos.legend.pure.truffle.ast.natives.collection.CollectionHelper;
import org.finos.legend.pure.truffle.StandaloneEvaluator;
import org.finos.legend.pure.truffle.runtime.helper._GenericType;
import org.finos.legend.pure.truffle.runtime.helper._PackageableElement;
import org.finos.legend.pure.truffle.types.PureSequence;

/**
 * {@code copy(T[1], KeyExpression[*]) : T[1]}
 * -- lazy native: deep copy + construction stack + key overrides.
 * Delegates to StandaloneEvaluator for the complex copy logic.
 */
@NodeInfo(shortName = "copyWithKeys")
public final class CopyWithKeysNode extends PureNode
{
    @CompilationFinal
    private final String signature;

    @CompilationFinal
    private final FunctionExpression fe;

    public CopyWithKeysNode(String signature, FunctionExpression fe)
    {
        this.signature = signature;
        this.fe = fe;
    }

    @Override
    public Object executeGeneric(VirtualFrame frame)
    {
        return invoke(frame);
    }

    private Object invoke(VirtualFrame frame)
    {
        org.finos.legend.pure.truffle.StandaloneEvaluator eval = StandaloneEvaluator.INSTANCE;

        // Step 1: Evaluate the source object (first arg)
        // _parametersValues() returns PureSequence; getBoxed() returns Object
        Object original = eval.astBuilder().lower(fe._parametersValues().getBoxed(0)).executeGeneric(frame);

        String classPath;
        GenericTypeValue cgt;
        if (original instanceof PackageableElement pe)
        {
            cgt = pe._classifierGenericType();
            classPath = pe.getClass().getInterfaces()[0].getName().replace(".", "::");
        }
        else if (original instanceof Any any)
        {
            cgt = any._classifierGenericType();
            classPath = any.getClass().getInterfaces()[0].getName().replace(".", "::");
        }
        else
        {
            throw new RuntimeException("Cannot copy: " + (original == null ? "null" : original.getClass().getSimpleName()));
        }

        if ((classPath == null || classPath.isEmpty()) && cgt != null)
        {
            classPath = resolveClassPathFromCGT(cgt);
        }

        // Step 2: Create the copy
        Object copy = org.finos.legend.pure.truffle.runtime.TruffleInstanceFactory.createInstance(classPath);
        shallowCopyProperties(original, copy, cgt);
        GenericTypeValue copyCgt = fixSelfReferentialCGT(cgt, original, copy);
        if (copy instanceof Any anyC && copyCgt != null)
        {
            anyC._classifierGenericType(copyCgt);
        }
        // Fix TypeParameter/MultiplicityParameter owners to point to the copy
        fixTypeParameterOwners(copy);
        // Fix property owners and nested enum value CGTs to point to the copy
        fixPropertyOwners(original, copy);

        // Step 3: Push onto construction stack, then evaluate key expressions
        eval.pushConstruction(copy);
        try
        {
            Object keyExprsResult = eval.astBuilder().lower(fe._parametersValues().getBoxed(1)).executeGeneric(frame);

            // Step 4: Process key expressions — each is a KeyExpression with {name, expression}
            int sz = CollectionHelper.size(keyExprsResult);
            java.util.List<java.util.Map.Entry<String, Object>> keyValues = new java.util.ArrayList<>();
            for (int i = 0; i < sz; i++)
            {
                Object ke = CollectionHelper.at(keyExprsResult, i);
                if (ke instanceof KeyExpression keImpl)
                {
                    String propName = keImpl._name();
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
                    boolean isAdd = keImpl._add() != null && keImpl._add();
                    if (isAdd)
                    {
                        // += operator: append new value(s) to existing property value.
                        // Must preserve original types (no AtomicValue unwrapping).
                        Object existing = eval.accessProperty(copy, propName);
                        java.util.List<Object> merged = new java.util.ArrayList<>();
                        addToMergedList(merged, existing);
                        addToMergedList(merged, propValue);
                        propValue = new org.finos.legend.pure.truffle.types.ObjectSequence(merged.toArray());
                    }
                    if (propName.contains("."))
                    {
                        // Deep property path: navigate and copy sub-objects
                        setDeepProperty(copy, propName, propValue, eval);
                    }
                    else
                    {
                        eval.accessProperty(copy, propName, propValue);
                    }
                    if (propValue != null)
                    {
                        keyValues.add(java.util.Map.entry(propName, propValue));
                    }
                }
            }

            // Set reverse association pointers (bidirectional binding)
            // For deep property paths, add the top-level property to enable reverse binding
            java.util.Set<String> topLevelDeepProps = new java.util.LinkedHashSet<>();
            for (java.util.Map.Entry<String, Object> kv : keyValues)
            {
                if (kv.getKey().contains("."))
                {
                    topLevelDeepProps.add(kv.getKey().split("\\.")[0]);
                }
            }
            for (String topProp : topLevelDeepProps)
            {
                // Only add if not already in keyValues as a simple property
                boolean alreadyPresent = keyValues.stream().anyMatch(kv -> topProp.equals(kv.getKey()));
                if (!alreadyPresent)
                {
                    Object topValue = eval.accessProperty(copy, topProp);
                    if (topValue != null && !(topValue instanceof org.finos.legend.pure.truffle.types.PureSequence ps2 && ps2.isEmpty()))
                    {
                        keyValues.add(java.util.Map.entry(topProp, topValue));
                    }
                }
            }
            // Also include shallow-copied association properties that aren't in keyValues
            // Use Pure path (not truffle-namespaced) for association matching
            String pureClassPath = classPath.replace("org::finos::legend::pure::truffle::pdb::", "");
            java.util.Set<String> keyPropNames = new java.util.HashSet<>();
            for (var kv : keyValues) keyPropNames.add(kv.getKey());
            addCopiedAssociationProps(copy, pureClassPath, keyPropNames, keyValues, eval);

            NewWithKeysNode.setReverseAssociationPointers(copy, pureClassPath, keyValues, eval);

            // Also set reverse associations for sub-copies created by deep property paths
            for (String topProp : topLevelDeepProps)
            {
                Object subCopy = eval.accessProperty(copy, topProp);
                if (subCopy instanceof Any subAny && subAny._classifierGenericType() != null)
                {
                    var subType = org.finos.legend.pure.truffle.runtime.helper._GenericType.type(subAny._classifierGenericType());
                    if (subType instanceof org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.PackageableElement subPe)
                    {
                        String subPath = org.finos.legend.pure.truffle.runtime.helper._PackageableElement.path(subPe);
                        java.util.List<java.util.Map.Entry<String, Object>> subKvs = new java.util.ArrayList<>();
                        addCopiedAssociationProps(subCopy, subPath, new java.util.HashSet<>(), subKvs, eval);
                        NewWithKeysNode.setReverseAssociationPointers(subCopy, subPath, subKvs, eval);
                    }
                }
            }

            return copy;
        }
        finally
        {
            eval.popConstruction();
        }
    }

    /**
     * Resolve a class path from a truffle GenericTypeValue.
     */
    private static String resolveClassPathFromCGT(GenericTypeValue cgt)
    {
        Type rawType = _GenericType.type(cgt);
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
     * Shallow-copy all properties from source to target via reflection.
     * Copies all _xxx() getter values to the corresponding _xxx(value) setter.
     */
    private static void shallowCopyProperties(Object source, Object target, GenericTypeValue cgt)
    {
        // Use the truffle type to gather property names, then copy via reflection
        Type sourceType = cgt != null ? _GenericType.type(cgt) : null;
        if (sourceType == null)
        {
            // Fallback: try interface methods directly
            copyViaReflection(source, target);
            return;
        }
        // Use interface method scanning — PDB metadata may not include association properties
        copyViaReflection(source, target);
    }

    /**
     * Collect all property names from a truffle type and its supertypes.
     */
    private static java.util.List<String> collectAllPropertyNames(Type type)
    {
        java.util.List<String> names = new java.util.ArrayList<>();
        java.util.Set<String> seen = new java.util.LinkedHashSet<>();
        java.util.Set<String> visited = new java.util.HashSet<>();
        collectPropertyNamesRecursive(type, names, seen, visited);
        return names;
    }

    private static void collectPropertyNamesRecursive(Type type, java.util.List<String> names,
                                                       java.util.Set<String> seen,
                                                       java.util.Set<String> visited)
    {
        String typeId = (type instanceof PackageableElement pe)
                ? _PackageableElement.path(pe)
                : String.valueOf(System.identityHashCode(type));
        if (typeId == null || !visited.add(typeId))
        {
            return;
        }
        if (type instanceof org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.type.Class cls)
        {
            addPropertyNamesFromSeq(cls._properties(), names, seen);
            addPropertyNamesFromSeq(cls._propertiesFromAssociations(), names, seen);
        }
        Object gens = type._generalizations();
        if (gens instanceof PureSequence seq)
        {
            for (Object gen : seq.toBoxedArray())
            {
                if (gen instanceof org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.relationship.Generalization g)
                {
                    Type superType = _GenericType.type(g._general());
                    if (superType != null)
                    {
                        collectPropertyNamesRecursive(superType, names, seen, visited);
                    }
                }
            }
        }
    }

    private static void addPropertyNamesFromSeq(PureSequence seq, java.util.List<String> names, java.util.Set<String> seen)
    {
        if (seq == null)
        {
            return;
        }
        for (Object prop : seq.toBoxedArray())
        {
            if (prop instanceof org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.function.property.Property p
                    && p._name() != null && seen.add(p._name()))
            {
                names.add(p._name());
            }
        }
    }

    /**
     * Copy a single property value from source to target via reflection.
     */
    private static void copyPropertyDirect(Object source, Object target, String propName)
    {
        String methodName = "_" + propName;
        try
        {
            java.lang.reflect.Method getter = source.getClass().getMethod(methodName);
            Object value = getter.invoke(source);
            if (value == null)
            {
                return;
            }
            for (java.lang.reflect.Method setter : target.getClass().getMethods())
            {
                if (setter.getName().equals(methodName) && setter.getParameterCount() == 1)
                {
                    try
                    {
                        setter.invoke(target, value);
                        break;
                    }
                    catch (IllegalArgumentException ignored)
                    {
                    }
                }
            }
        }
        catch (ReflectiveOperationException ignored)
        {
        }
    }

    /**
     * Fallback: copy all _xxx() properties from source to target using interface methods.
     */
    private static void copyViaReflection(Object source, Object target)
    {
        Class<?>[] interfaces = source.getClass().getInterfaces();
        if (interfaces.length == 0)
        {
            return;
        }
        for (java.lang.reflect.Method getter : interfaces[0].getMethods())
        {
            String name = getter.getName();
            if (!name.startsWith("_") || getter.getParameterCount() != 0)
            {
                continue;
            }
            if ("_copy".equals(name) || "_class".equals(name))
            {
                continue;
            }
            try
            {
                Object value = getter.invoke(source);
                if (value == null)
                {
                    continue;
                }
                for (java.lang.reflect.Method setter : target.getClass().getMethods())
                {
                    if (setter.getName().equals(name) && setter.getParameterCount() == 1)
                    {
                        try
                        {
                            setter.invoke(target, value);
                            break;
                        }
                        catch (IllegalArgumentException ignored)
                        {
                        }
                    }
                }
            }
            catch (Exception e)
            {
                throw new RuntimeException("Failed during equality key property set in copy", e);
            }
        }
    }

    /**
     * Fix self-referential classifierGenericType during copy.
     * If the original's CGT has a typeArgument whose rawType IS the original instance itself
     * (e.g., Class&lt;self&gt;), create a new CGT with the typeArgument pointing to the copy.
     * Returns the updated CGT, or the original if no self-reference was found.
     */
    private static GenericTypeValue fixSelfReferentialCGT(GenericTypeValue cgt, Object original, Object copy)
    {
        if (cgt == null)
        {
            return null;
        }
        PureSequence typeArgs = _GenericType.typeArguments(cgt);
        if (typeArgs != null && typeArgs.size() > 0)
        {
            boolean hasSelfRef = false;
            for (Object arg : typeArgs.toBoxedArray())
            {
                if (arg instanceof GenericTypeValue argV)
                {
                    Type argType = _GenericType.type(argV);
                    if (argType != null && argType == original)
                    {
                        hasSelfRef = true;
                        break;
                    }
                }
            }
            if (hasSelfRef && copy instanceof Type copyType)
            {
                // Build a new CGT with updated self-references
                var _resolver = org.finos.legend.pure.truffle.StandaloneEvaluator.INSTANCE.resolver();
                var newCgt = _GenericType.buildUserDefinedGenericType(cgt._type(), _resolver);
                org.finos.legend.pure.truffle.types.ObjectSequence newArgs =
                        new org.finos.legend.pure.truffle.types.ObjectSequence(
                                java.util.Arrays.stream(typeArgs.toBoxedArray())
                                        .map(arg ->
                                        {
                                            if (arg instanceof GenericTypeValue argV)
                                            {
                                                Type argType = _GenericType.type(argV);
                                                if (argType != null && argType == original)
                                                {
                                                    var selfRef = _GenericType.buildUserDefinedGenericType(copyType, _resolver);
                                                    // Carry over inner type arguments (e.g., TypeParameter GTs)
                                                    PureSequence innerTA = _GenericType.typeArguments(argV);
                                                    if (innerTA != null && !innerTA.isEmpty())
                                                    {
                                                        selfRef._typeArguments(innerTA);
                                                    }
                                                    PureSequence innerMA = argV._multiplicityArguments();
                                                    if (innerMA != null && !innerMA.isEmpty())
                                                    {
                                                        selfRef._multiplicityArguments(innerMA);
                                                    }
                                                    return (Object) selfRef;
                                                }
                                            }
                                            return arg;
                                        })
                                        .toArray());
                newCgt._typeArguments(newArgs);
                PureSequence mulArgs = cgt._multiplicityArguments();
                if (mulArgs != null)
                {
                    newCgt._multiplicityArguments(mulArgs);
                }
                return newCgt;
            }
        }
        return cgt;
    }

    /**
     * Handle dotted property paths like "address.name" or "firm.employees".
     * Navigates to each sub-object, creating copies as needed, and sets the leaf property.
     */
    private static void setDeepProperty(Object root, String dottedPath, Object value,
                                         org.finos.legend.pure.truffle.StandaloneEvaluator eval)
    {
        String[] parts = dottedPath.split("\\.");
        Object current = root;
        // Navigate to the parent of the leaf, copying sub-objects along the way
        for (int i = 0; i < parts.length - 1; i++)
        {
            Object child = eval.accessProperty(current, parts[i]);
            if (child == null || (child instanceof org.finos.legend.pure.truffle.types.PureSequence ps3 && ps3.isEmpty()))
            {
                return; // Sub-object doesn't exist — nothing to set
            }
            // Copy the sub-object so we don't mutate the original
            if (child instanceof Any any)
            {
                Object childCopy = tryCopy(any);
                if (childCopy instanceof Any anyCopy)
                {
                    anyCopy._classifierGenericType(any._classifierGenericType());
                }
                eval.accessProperty(current, parts[i], childCopy);
                current = childCopy;
            }
            else
            {
                current = child;
            }
        }
        // Set the leaf property
        eval.accessProperty(current, parts[parts.length - 1], value);
    }

    /**
     * Try to invoke _copy() on an Any instance. Falls back to reflection-based copy.
     */
    private static Object tryCopy(Any any)
    {
        try
        {
            java.lang.reflect.Method copyMethod = any.getClass().getMethod("_copy");
            return copyMethod.invoke(any);
        }
        catch (Exception e)
        {
            // _copy() not available -- fallback
            String classPath = any.getClass().getInterfaces()[0].getName().replace(".", "::");
            Object copy = org.finos.legend.pure.truffle.runtime.TruffleInstanceFactory.createInstance(classPath);
            copyViaReflection(any, copy);
            return copy;
        }
    }

    /**
     * Add elements from {@code value} to {@code target} list, preserving
     * original types (no AtomicValue unwrapping). Handles empty sequence (skip),
     * PureSequence (flatten), MutableList (flatten), and scalar (add as-is).
     */
    private static void addToMergedList(java.util.List<Object> target, Object value)
    {
        if (value == null || (value instanceof org.finos.legend.pure.truffle.types.PureSequence ps4 && ps4.isEmpty()))
        {
            return;
        }
        if (value instanceof PureSequence ps)
        {
            for (int i = 0; i < ps.size(); i++)
            {
                target.add(ps.getBoxed(i));
            }
        }
        else if (value instanceof org.eclipse.collections.api.list.MutableList<?> ml)
        {
            target.addAll(ml);
        }
        else if (value instanceof java.util.List<?> list)
        {
            target.addAll(list);
        }
        else
        {
            target.add(value);
        }
    }

    /**
     * After shallow copy, find all association properties on the copy that have non-null values
     * and add them to keyValues for reverse pointer binding.
     */
    private static void addCopiedAssociationProps(Object copy, String classPath,
                                                   java.util.Set<String> existingKeys,
                                                   java.util.List<java.util.Map.Entry<String, Object>> keyValues,
                                                   org.finos.legend.pure.truffle.StandaloneEvaluator eval)
    {
        // Get all getter methods on the copy
        for (java.lang.reflect.Method m : copy.getClass().getMethods())
        {
            String name = m.getName();
            if (!name.startsWith("_") || m.getParameterCount() != 0 || name.equals("_copy")
                    || name.equals("_classifierGenericType") || name.equals("_sourceInformation")
                    || name.equals("_elementOverride") || name.equals("_class") || name.equals("_hashCode"))
            {
                continue;
            }
            String propName = name.substring(1); // strip "_"
            if (existingKeys.contains(propName))
            {
                continue;
            }
            // Check if this property has a reverse association
            String reversePropName = NewWithKeysNode.findReverseAssociationProperty(propName, classPath, eval.resolver());
            if (reversePropName == null)
            {
                continue;
            }
            try
            {
                Object propValue = m.invoke(copy);
                if (propValue != null && !(propValue instanceof org.finos.legend.pure.truffle.types.PureSequence ps5 && ps5.isEmpty())
                        && !(propValue instanceof PureSequence seq && seq.isEmpty()))
                {
                    keyValues.add(java.util.Map.entry(propName, propValue));
                }
            }
            catch (Exception e)
            {
                throw new RuntimeException("Failed to read property for key values in copy", e);
            }
        }
    }

    /**
     * After copying a SimplePropertyOwner, update property owners and nested
     * enum value CGTs to point to the copy instead of the original.
     */
    private static void fixPropertyOwners(Object original, Object copy)
    {
        if (!(copy instanceof org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.SimplePropertyOwner spo))
        {
            return;
        }
        PureSequence props = spo._properties();
        if (props == null)
        {
            return;
        }
        var copyGT = (copy instanceof org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.type.Type copyType)
                ? org.finos.legend.pure.truffle.runtime.helper._GenericType.buildUserDefinedGenericType(
                        copyType, org.finos.legend.pure.truffle.StandaloneEvaluator.INSTANCE.resolver())
                : null;

        for (int i = 0; i < props.size(); i++)
        {
            Object p = props.getBoxed(i);
            if (p instanceof org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.function.property.Property prop)
            {
                // Fix owner reference
                if (prop._owner() == original)
                {
                    prop._owner(spo);
                }
                // Fix enum value CGT inside defaultValue lambda
                if (copyGT != null && prop._defaultValue() != null
                        && prop._defaultValue()._expressionSequence() != null
                        && !prop._defaultValue()._expressionSequence().isEmpty())
                {
                    Object vs = prop._defaultValue()._expressionSequence().getBoxed(0);
                    if (vs instanceof org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.valuespecification.AtomicValue av
                            && av._value() instanceof org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.type.Any enumVal)
                    {
                        var enumCgt = enumVal._classifierGenericType();
                        if (enumCgt != null)
                        {
                            var enumCgtType = org.finos.legend.pure.truffle.runtime.helper._GenericType.type(enumCgt);
                            if (enumCgtType == original)
                            {
                                enumVal._classifierGenericType(copyGT);
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * After copying a TypeAndMultiplicityParametersOwner (e.g. Class, Function),
     * update the owner back-reference on its TypeParameters and MultiplicityParameters
     * to point to the copy instead of the original.
     */
    private static void fixTypeParameterOwners(Object copy)
    {
        if (!(copy instanceof org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.type.generics.TypeAndMultiplicityParametersOwner owner))
        {
            return;
        }
        PureSequence typeParams = owner._typeParameters();
        if (typeParams != null)
        {
            for (int i = 0; i < typeParams.size(); i++)
            {
                Object tp = typeParams.getBoxed(i);
                if (tp instanceof org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.type.generics.TypeParameter tpObj)
                {
                    tpObj._owner(owner);
                }
            }
        }
        PureSequence mulParams = owner._multiplicityParameters();
        if (mulParams != null)
        {
            for (int i = 0; i < mulParams.size(); i++)
            {
                Object mp = mulParams.getBoxed(i);
                if (mp instanceof org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.multiplicity.MultiplicityParameter mpObj)
                {
                    mpObj._owner(owner);
                }
            }
        }
    }
}
