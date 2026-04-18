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

    @TruffleBoundary
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
                    eval.accessProperty(copy, propName, propValue);
                    keyValues.add(java.util.Map.entry(propName, propValue));
                }
            }

            // Set reverse association pointers (bidirectional binding)
            NewWithKeysNode.setReverseAssociationPointers(copy, classPath, keyValues, eval);

            return copy;
        }
        finally
        {
            eval.popConstruction();
        }
    }
}
