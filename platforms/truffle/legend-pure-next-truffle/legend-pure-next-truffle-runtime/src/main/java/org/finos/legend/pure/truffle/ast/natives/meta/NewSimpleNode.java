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
import org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.valuespecification.GenericTypeAndMultiplicityHolder;
import org.finos.legend.pure.truffle.ast.PureNode;
import org.finos.legend.pure.truffle.types.PureSequence;

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
        return doNew(result, getResolver());
    }

    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    private static Object doNew(Object result,
            org.finos.legend.pure.truffle.runtime.TruffleMetadataAccess resolver)
    {
        if (result == null
                || !org.finos.legend.pure.truffle.runtime.dynobj.PureObj.isType(result,
                        "meta::pure::metamodel::valuespecification::GenericTypeAndMultiplicityHolder", resolver))
        {
            throw new RuntimeException("new(GenericTypeAndMultiplicityHolder[1]) requires a GenericTypeAndMultiplicityHolder argument, got: "
                    + (result == null ? "null" : result.getClass().getName()));
        }

        String classPath = "Unknown";
        Object holderGT = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.read(result, "genericType");
        PureSequence typeArgs = holderGT != null
                ? org.finos.legend.pure.truffle.runtime.helper._GenericType.typeArguments(holderGT)
                : null;
        if (typeArgs != null && typeArgs.size() > 0)
        {
            Object heldGTObj = typeArgs.getBoxed(0);
            org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.type.Type heldType =
                    org.finos.legend.pure.truffle.runtime.helper._GenericType.type(heldGTObj);
            if (heldType != null)
            {
                classPath = org.finos.legend.pure.truffle.runtime.helper._PackageableElement.path(heldType);
                if (classPath == null || classPath.isEmpty())
                {
                    Object n = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.read(heldType, "name");
                    classPath = n instanceof String s && !s.isEmpty() ? s : "Unknown";
                }
            }
        }

        // Create instance via TruffleInstanceFactory (handles Java keyword escaping)
        Object instance = org.finos.legend.pure.truffle.runtime.TruffleInstanceFactory.createInstance(classPath, resolver);
        if (instance == null)
        {
            throw new RuntimeException("Cannot instantiate " + classPath);
        }

        if (typeArgs != null && typeArgs.size() > 0)
        {
            Object cgtObj = typeArgs.getBoxed(0);
            if (cgtObj instanceof GenericTypeValue gtv)
            {
                // Platform-level canonical anchor: when ^Type(...) has no type/mult args,
                // prefer canonical GenericType_<TypeName> UDPGT from core.pdb.
                org.finos.legend.pure.truffle.runtime.dynobj.PureObj.write(instance, "classifierGenericType",
                        NewWithKeysNode.preferCanonicalAnchorPublic(gtv, resolver));
            }
        }

        return instance;
    }
}
