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

/**
 * {@code new(GenericType[1]) : Any[1]}.
 * Creates an instance with the given GenericType as classifierGenericType.
 */
@NodeInfo(shortName = "newGenericType")
public final class NewGenericTypeNode extends PureNode
{

    private static final int SLOT_NAME = org.finos.legend.pure.truffle.runtime.dynobj.PureClassRegistry.globalSlot("name");
    @Child
    private PureNode child;

    public NewGenericTypeNode(PureNode child)
    {
        this.child = child;
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
            String pt = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.pureTypeOf(result);
            throw new RuntimeException("new(GenericType[1]) requires a GenericType argument; got pureType=" + pt + " class=" + (result == null ? "null" : result.getClass().getName()));
        }

        Object rawType = org.finos.legend.pure.truffle.runtime.helper._GenericType.type(result);
        String classPath = "Unknown";
        if (rawType != null)
        {
            classPath = org.finos.legend.pure.truffle.runtime.helper._PackageableElement.path(rawType, resolver);
            if (classPath == null || classPath.isEmpty())
            {
                Object n = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.readBySlot(rawType, SLOT_NAME);
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

        // Resolve a class to use for the SHAPE of the instance. If the rawType
        // isn't registered with the resolver (e.g. an in-progress
        // newEnumeration()'s runtime Enumeration), fall back to walking its
        // generalizations to find a registered ancestor — that ancestor's
        // classInfo has the property slots we need. CGT below still points
        // at the rawType requested by the caller.
        String shapeClassPath = classPath;
        if (rawType != null && resolver.getElement(classPath) == null)
        {
            String resolved = resolveShapeFromGeneralizations(rawType, resolver);
            if (resolved != null)
            {
                shapeClassPath = resolved;
            }
        }
        Object instance = org.finos.legend.pure.truffle.runtime.TruffleInstanceFactory.createInstance(shapeClassPath, resolver);
        if (instance != null)
        {
            // Platform-level canonical anchor: when the input GT has no type/mult args,
            // prefer the canonical GenericType_<TypeName> UDPGT from core.pdb. Mirrors
            // bootstrap MetaNatives.preferCanonicalAnchor and Java's `new XxxImpl(model)`
            // ctor anchoring. Without this, `new(buildUserDefinedGenericType(SomeType))`
            // leaves classifier as a fresh inline UDGT, while Java emits canonical UDPGT.
            org.finos.legend.pure.truffle.runtime.dynobj.PureObj.write(instance, "classifierGenericType",
                    NewWithKeysNode.preferCanonicalAnchor(result, resolver));
        }

        return instance;
    }

    /**
     * Walk {@code rawType}'s generalizations until we find one whose path is
     * registered in {@link org.finos.legend.pure.truffle.runtime.dynobj.PureClassRegistry}.
     * Used when the caller passes a runtime-constructed type (e.g. a Pure-level
     * newEnumeration's in-progress Enumeration) — Truffle has no classInfo for
     * such a type, so we need an ancestor's shape.
     */
    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    private static String resolveShapeFromGeneralizations(Object rawType,
            org.finos.legend.pure.truffle.runtime.TruffleMetadataAccess resolver)
    {
        Object gens = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.read(rawType, "generalizations");
        if (gens instanceof org.finos.legend.pure.truffle.types.PureSequence seq)
        {
            for (int i = 0; i < seq.size(); i++)
            {
                Object gen = seq.getBoxed(i);
                if (gen == null) continue;
                Object general = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.read(gen, "general");
                Object generalType = general != null
                        ? org.finos.legend.pure.truffle.runtime.helper._GenericType.type(general)
                        : null;
                if (generalType != null)
                {
                    String ancestorPath = org.finos.legend.pure.truffle.runtime.helper._PackageableElement.path(generalType, resolver);
                    if (ancestorPath != null && !ancestorPath.isEmpty()
                            && resolver.getElement(ancestorPath) != null)
                    {
                        return ancestorPath;
                    }
                    // Recurse into the ancestor's generalizations
                    String recursed = resolveShapeFromGeneralizations(generalType, resolver);
                    if (recursed != null)
                    {
                        return recursed;
                    }
                }
            }
        }
        return null;
    }
}
