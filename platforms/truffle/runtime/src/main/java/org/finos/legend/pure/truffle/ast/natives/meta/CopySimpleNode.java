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
import org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.PackageableElement;
import org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.multiplicity.Multiplicity;
import org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.type.Any;
import org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.type.generics.GenericType;
import org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.type.generics.GenericTypeValue;
import org.finos.legend.pure.truffle.ast.PureNode;

/**
 * {@code copy(T[1]) : T[1]}.
 * Shallow copy with no property overrides.
 */
@NodeInfo(shortName = "copySimple")
public final class CopySimpleNode extends PureNode
{
    @Child
    private PureNode child;

    private final GenericType genericType;
    private final Multiplicity multiplicity;

    public CopySimpleNode(PureNode child, GenericType genericType, Multiplicity multiplicity)
    {
        this.child = child;
        this.genericType = genericType;
        this.multiplicity = multiplicity;
    }

    @Override
    public Object executeGeneric(VirtualFrame frame)
    {
        Object result = child.executeGeneric(frame);
        return doCopy(result);
    }

    @TruffleBoundary
    private static Object doCopy(Object original)
    {
        GenericTypeValue cgt;
        String classPath;
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
            org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.type.Type rawType =
                    org.finos.legend.pure.truffle.runtime.helper._GenericType.type(cgt);
            if (rawType instanceof PackageableElement pe2)
            {
                String p = org.finos.legend.pure.truffle.runtime.helper._PackageableElement.path(pe2);
                if (p != null && !p.isEmpty())
                {
                    classPath = p;
                }
            }
        }

        // Create instance via TruffleInstanceFactory (handles prefix stripping)
        Object copy = org.finos.legend.pure.truffle.runtime.TruffleInstanceFactory.createInstance(classPath);
        if (copy instanceof org.finos.legend.pure.execution.DynamicInstance)
        {
            throw new RuntimeException("Cannot create copy of " + classPath);
        }

        // Shallow copy all _xxx() getter/setter pairs via reflection
        Class<?>[] interfaces = original.getClass().getInterfaces();
        if (interfaces.length > 0)
        {
            for (java.lang.reflect.Method getter : interfaces[0].getMethods())
            {
                String name = getter.getName();
                if (!name.startsWith("_") || getter.getParameterCount() != 0
                        || "_copy".equals(name) || "_class".equals(name) || "_hashCode".equals(name))
                {
                    continue;
                }
                try
                {
                    Object value = getter.invoke(original);
                    if (value == null)
                    {
                        continue;
                    }
                    for (java.lang.reflect.Method setter : copy.getClass().getMethods())
                    {
                        if (setter.getName().equals(name) && setter.getParameterCount() == 1
                                && setter.getParameterTypes()[0].isInstance(value))
                        {
                            setter.invoke(copy, value);
                            break;
                        }
                    }
                }
                catch (Exception ignored)
                {
                }
            }
        }

        // Set classifierGenericType on copy
        if (copy instanceof Any anyC && cgt != null)
        {
            anyC._classifierGenericType(cgt);
        }
        return copy;
    }
}
