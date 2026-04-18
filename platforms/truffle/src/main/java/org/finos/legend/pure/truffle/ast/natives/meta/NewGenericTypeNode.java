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
import org.finos.legend.pure.execution.natives.meta.MetaNatives;
import org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._GenericType;
import org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._PackageableElement;
import org.finos.legend.pure.truffle.ast.PureNode;

/**
 * {@code new(GenericType[1]) : Any[1]}.
 * Creates an instance with the given GenericType as classifierGenericType.
 */
@NodeInfo(shortName = "newGenericType")
public final class NewGenericTypeNode extends PureNode
{
    @Child
    private PureNode child;

    private final GenericType genericType;
    private final Multiplicity multiplicity;

    public NewGenericTypeNode(PureNode child, GenericType genericType, Multiplicity multiplicity)
    {
        this.child = child;
        this.genericType = genericType;
        this.multiplicity = multiplicity;
    }

    @Override
    public Object executeGeneric(VirtualFrame frame)
    {
        Object result = child.executeGeneric(frame);
        return doNewGenericType(result);
    }

    @TruffleBoundary
    private static Object doNewGenericType(Object result)
    {
        if (!(result instanceof GenericTypeValue gt))
        {
            throw new RuntimeException("new(GenericType[1]) requires a GenericType argument");
        }

        meta.pure.metamodel.type.Type rawType = _GenericType.type(gt);
        String classPath = "Unknown";
        if (rawType instanceof meta.pure.metamodel.PackageableElement pe)
        {
            classPath = _PackageableElement.path(pe);
            if (classPath.isEmpty())
            {
                classPath = pe._name() != null ? pe._name() : "Unknown";
            }
        }

        if ("meta::pure::metamodel::type::Class".equals(classPath))
        {
            if (_GenericType.typeArguments(gt) == null || _GenericType.typeArguments(gt).isEmpty())
            {
                throw new RuntimeException("Cannot instantiate Class<Class<T>> because the typeArgs are not set for the typeParam");
            }
        }

        Object instance = MetaNatives.createInstanceByPath(classPath);
        if (instance instanceof Any any)
        {
            any._classifierGenericType(gt);
        }

        return instance;
    }
}
