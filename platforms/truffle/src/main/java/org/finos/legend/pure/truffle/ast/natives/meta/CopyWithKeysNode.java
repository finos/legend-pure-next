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
import meta.pure.metamodel.PackageableElement;
import meta.pure.metamodel.type.Any;
import meta.pure.metamodel.type.generics.GenericTypeValue;
import meta.pure.metamodel.valuespecification.FunctionExpression;
import meta.pure.functions.lang.KeyExpressionImpl;
import org.finos.legend.pure.execution.natives.meta.MetaNatives;
import org.finos.legend.pure.m3.module.MetadataAccess;
import org.finos.legend.pure.truffle.ast.PureNode;
import org.finos.legend.pure.truffle.ast.natives.collection.CollectionHelper;
import org.finos.legend.pure.truffle.runtime.StandaloneEvaluatorHolder;

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
        return invoke();
    }

    private Object invoke()
    {
        org.finos.legend.pure.truffle.StandaloneEvaluator eval = StandaloneEvaluatorHolder.current();
        MetadataAccess resolver = eval.resolver();

        // Step 1: Evaluate the source object (first arg)
        Object original = eval.astBuilder().lower(fe._parametersValues().get(0)).executeGeneric(eval.currentFrame());

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
            classPath = MetaNatives.resolveClassPathFromCGT(cgt);
        }

        // Step 2: Create the copy
        Object copy = MetaNatives.createInstanceByPath(classPath);
        MetaNatives.shallowCopyProperties(original, copy, cgt, resolver);
        GenericTypeValue copyCgt = MetaNatives.fixSelfReferentialCGT(cgt, original, copy, resolver);
        if (copy instanceof Any anyC && copyCgt != null)
        {
            anyC._classifierGenericType(copyCgt);
        }

        // Step 3: Push onto construction stack, then evaluate key expressions
        eval.pushConstruction(copy);
        try
        {
            Object keyExprsResult = eval.astBuilder().lower(fe._parametersValues().get(1)).executeGeneric(eval.currentFrame());

            // Step 4: Process key expressions — each is a KeyExpressionImpl with {name, expression}
            int sz = CollectionHelper.size(keyExprsResult);
            java.util.List<java.util.Map.Entry<String, Object>> keyValues = new java.util.ArrayList<>();
            for (int i = 0; i < sz; i++)
            {
                Object ke = CollectionHelper.at(keyExprsResult, i);
                if (ke instanceof KeyExpressionImpl keImpl)
                {
                    String propName = keImpl._name();
                    Object propValue = keImpl._expression();
                    boolean isAdd = keImpl._add() != null && keImpl._add();
                    if (isAdd)
                    {
                        // += operator: append new value(s) to existing property value.
                        // Must preserve original types (no AtomicValue unwrapping).
                        Object existing = eval.accessProperty(copy, propName);
                        java.util.List<Object> merged = new java.util.ArrayList<>();
                        addToMergedList(merged, existing);
                        addToMergedList(merged, propValue);
                        propValue = org.eclipse.collections.api.factory.Lists.mutable.withAll(merged);
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
                    keyValues.add(java.util.Map.entry(propName, propValue));
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
                    if (topValue != null && !(topValue instanceof org.finos.legend.pure.truffle.types.PureSequence _ps1 && _ps1.isEmpty()))
                    {
                        keyValues.add(java.util.Map.entry(topProp, topValue));
                    }
                }
            }
            NewWithKeysNode.setReverseAssociationPointers(copy, classPath, keyValues, eval);

            return copy;
        }
        finally
        {
            eval.popConstruction();
        }
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
            if (child == null || child instanceof org.finos.legend.pure.truffle.types.PureSequence _ps2 && _ps2.isEmpty())
            {
                return; // Sub-object doesn't exist — nothing to set
            }
            // Copy the sub-object so we don't mutate the original
            if (child instanceof meta.pure.metamodel.type.Any any)
            {
                Object childCopy = any._copy();
                if (childCopy instanceof meta.pure.metamodel.type.Any anyCopy)
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
     * Add elements from {@code value} to {@code target} list, preserving
     * original types (no AtomicValue unwrapping). Handles empty PureSequence
     * (skip), non-empty PureSequence (flatten), MutableList (flatten), and
     * scalar (add as-is).
     */
    private static void addToMergedList(java.util.List<Object> target, Object value)
    {
        if (value == null || value instanceof org.finos.legend.pure.truffle.types.PureSequence _ps3 && _ps3.isEmpty())
        {
            return;
        }
        if (value instanceof org.finos.legend.pure.truffle.types.PureSequence ps)
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
}
