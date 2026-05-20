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
import org.finos.legend.pure.truffle.ast.PureNode;
import org.finos.legend.pure.truffle.types.PureSequence;

/**
 * {@code new(GenericTypeAndMultiplicityHolder[1]) : T[1]}.
 * Constructs an instance with no property assignments.
 */
@NodeInfo(shortName = "newSimple")
public final class NewSimpleNode extends PureNode
{

    private static final int SLOT_GENERIC_TYPE = org.finos.legend.pure.truffle.runtime.dynobj.PureClassRegistry.globalSlot("genericType");
    private static final int SLOT_NAME = org.finos.legend.pure.truffle.runtime.dynobj.PureClassRegistry.globalSlot("name");
    @Child
    private PureNode child;

    public NewSimpleNode(PureNode child)
    {
        this.child = child;
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
        Object holderGT = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.readBySlot(result, SLOT_GENERIC_TYPE);
        PureSequence typeArgs = holderGT != null
                ? org.finos.legend.pure.truffle.runtime.helper._GenericType.typeArguments(holderGT)
                : null;
        if (typeArgs != null && typeArgs.size() > 0)
        {
            Object heldGTObj = typeArgs.getBoxed(0);
            Object heldType = org.finos.legend.pure.truffle.runtime.helper._GenericType.type(heldGTObj);
            if (heldType != null)
            {
                classPath = org.finos.legend.pure.truffle.runtime.helper._PackageableElement.path(heldType, resolver);
                if (classPath == null || classPath.isEmpty())
                {
                    Object n = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.readBySlot(heldType, SLOT_NAME);
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
            // The instanceof GenericTypeValue guard wouldn't match a
            // PureDynamicObject post-loader-flip; preferCanonicalAnchor
            // is widened to Object and tolerates any Pure GT representation.
            if (cgtObj != null)
            {
                // Platform-level canonical anchor: when ^Type(...) has no type/mult args,
                // prefer canonical GenericType_<TypeName> UDPGT from core.pdb.
                org.finos.legend.pure.truffle.runtime.dynobj.PureObj.write(instance, "classifierGenericType",
                        NewWithKeysNode.preferCanonicalAnchor(cgtObj, resolver));
            }
        }

        return instance;
    }
}
