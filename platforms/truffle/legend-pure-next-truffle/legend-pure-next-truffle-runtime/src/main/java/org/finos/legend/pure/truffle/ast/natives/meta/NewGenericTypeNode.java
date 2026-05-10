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

import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.NodeInfo;
import org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.multiplicity.Multiplicity;
import org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.type.Any;
import org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.type.generics.GenericType;
import org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.type.generics.GenericTypeValue;
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
        return doNewGenericType(result, getResolver());
    }

    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    private static Object doNewGenericType(Object result,
            org.finos.legend.pure.truffle.runtime.TruffleMetadataAccess resolver)
    {
        if (result == null
                || !org.finos.legend.pure.truffle.runtime.dynobj.PureObj.isType(result,
                        "meta::pure::metamodel::type::generics::GenericTypeValue", resolver))
        {
            throw new RuntimeException("new(GenericType[1]) requires a GenericType argument");
        }

        org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.type.Type rawType =
                org.finos.legend.pure.truffle.runtime.helper._GenericType.type(result);
        String classPath = "Unknown";
        if (rawType != null)
        {
            classPath = org.finos.legend.pure.truffle.runtime.helper._PackageableElement.path(rawType);
            if (classPath == null || classPath.isEmpty())
            {
                Object n = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.read(rawType, "name");
                classPath = n instanceof String s && !s.isEmpty() ? s : "Unknown";
            }
        }

        if ("meta::pure::metamodel::type::Class".equals(classPath))
        {
            if (org.finos.legend.pure.truffle.runtime.helper._GenericType.typeArguments(result) == null
                    || org.finos.legend.pure.truffle.runtime.helper._GenericType.typeArguments(result).isEmpty())
            {
                throw new RuntimeException("Cannot instantiate Class<Class<T>> because the typeArgs are not set for the typeParam");
            }
        }

        Object instance = org.finos.legend.pure.truffle.runtime.TruffleInstanceFactory.createInstance(classPath, resolver);
        if (instance != null)
        {
            // Platform-level canonical anchor: when the input GT has no type/mult args,
            // prefer the canonical GenericType_<TypeName> UDPGT from core.pdb. Mirrors
            // bootstrap MetaNatives.preferCanonicalAnchor and Java's `new XxxImpl(model)`
            // ctor anchoring. Without this, `new(buildUserDefinedGenericType(SomeType))`
            // leaves classifier as a fresh inline UDGT, while Java emits canonical UDPGT.
            // result was just isType-validated as GenericTypeValue above; the
            // cast is safe as long as legacy FBWs still implement the typed
            // interface (they do during migration). Drops out when
            // preferCanonicalAnchorPublic is widened to Object in a later pass.
            org.finos.legend.pure.truffle.runtime.dynobj.PureObj.write(instance, "classifierGenericType",
                    NewWithKeysNode.preferCanonicalAnchorPublic(
                            (org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.type.generics.GenericTypeValue) result,
                            resolver));
        }

        return instance;
    }
}
