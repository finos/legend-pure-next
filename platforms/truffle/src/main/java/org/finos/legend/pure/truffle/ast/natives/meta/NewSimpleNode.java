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

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.NodeInfo;
import meta.pure.metamodel.multiplicity.Multiplicity;
import meta.pure.metamodel.type.Any;
import meta.pure.metamodel.type.generics.GenericType;
import meta.pure.metamodel.type.generics.GenericTypeValue;
import meta.pure.metamodel.valuespecification.GenericTypeAndMultiplicityHolder;
import org.finos.legend.pure.execution.natives.meta.MetaNatives;
import org.finos.legend.pure.m3.module.MetadataAccess;
import org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._GenericType;
import org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._PackageableElement;
import org.finos.legend.pure.truffle.ast.PureNode;
import org.finos.legend.pure.truffle.runtime.StandaloneEvaluatorHolder;

/**
 * {@code new(GenericTypeAndMultiplicityHolder[1]) : T[1]}.
 * Constructs an instance with no property assignments.
 */
@NodeInfo(shortName = "newSimple")
public final class NewSimpleNode extends PureNode
{
    @Child
    private PureNode child;

    private final GenericType genericType;
    private final Multiplicity multiplicity;

    public NewSimpleNode(PureNode child, GenericType genericType, Multiplicity multiplicity)
    {
        this.child = child;
        this.genericType = genericType;
        this.multiplicity = multiplicity;
    }

    @Override
    public Object executeGeneric(VirtualFrame frame)
    {
        Object result = child.executeGeneric(frame);
        return doNew(result);
    }

    @TruffleBoundary
    private static Object doNew(Object result)
    {
        MetadataAccess resolver = StandaloneEvaluatorHolder.current().resolver();

        if (!(result instanceof GenericTypeAndMultiplicityHolder gtmh))
        {
            throw new RuntimeException("new(GenericTypeAndMultiplicityHolder[1]) requires a GenericTypeAndMultiplicityHolder argument, got: "
                    + (result == null ? "null" : result.getClass().getSimpleName()));
        }

        String classPath = "Unknown";
        if (gtmh._genericType() != null
                && _GenericType.typeArguments(gtmh._genericType()) != null
                && _GenericType.typeArguments(gtmh._genericType()).notEmpty())
        {
            GenericType heldGT = _GenericType.typeArguments(gtmh._genericType()).getFirst();
            if (_GenericType.type(heldGT) instanceof meta.pure.metamodel.PackageableElement pe)
            {
                classPath = _PackageableElement.path(pe);
                if (classPath.isEmpty())
                {
                    classPath = pe._name() != null ? pe._name() : "Unknown";
                }
            }
        }

        Object instance = MetaNatives.createInstance(classPath, gtmh);

        if (gtmh._genericType() != null
                && _GenericType.typeArguments(gtmh._genericType()) != null
                && _GenericType.typeArguments(gtmh._genericType()).notEmpty())
        {
            GenericType cgt = _GenericType.typeArguments(gtmh._genericType()).getFirst();
            if (instance instanceof Any any)
            {
                any._classifierGenericType((GenericTypeValue) cgt);
            }

            meta.pure.metamodel.type.Type targetType = _GenericType.type(cgt);
            if (targetType instanceof meta.pure.metamodel.extension.ElementWithConstraints)
            {
                MetaNatives.validateConstraints(targetType, cgt, instance, null, resolver);
            }
        }

        return instance;
    }
}
