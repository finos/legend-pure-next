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
 * {@code copy(T[1]) : T[1]}.
 * Shallow copy with no property overrides.
 */
@NodeInfo(shortName = "copySimple")
public final class CopySimpleNode extends PureNode
{

    private static final int SLOT_CLASSIFIER_GENERIC_TYPE = org.finos.legend.pure.truffle.runtime.dynobj.PureClassRegistry.globalSlot("classifierGenericType");
    private static final int SLOT_OWNER = org.finos.legend.pure.truffle.runtime.dynobj.PureClassRegistry.globalSlot("owner");
    private static final int SLOT_TYPE = org.finos.legend.pure.truffle.runtime.dynobj.PureClassRegistry.globalSlot("type");
    private static final int SLOT_TYPE_ARGUMENTS = org.finos.legend.pure.truffle.runtime.dynobj.PureClassRegistry.globalSlot("typeArguments");
    @Child
    private PureNode child;

    public CopySimpleNode(PureNode child)
    {
        this.child = child;
    }

    @Override
    public Object executeGeneric(VirtualFrame frame)
    {
        Object result = child.executeGeneric(frame);
        return doCopy(result, getResolver());
    }

    private static Object doCopy(Object original, org.finos.legend.pure.truffle.runtime.TruffleMetadataAccess resolver)
    {
        if (original == null)
        {
            throw new RuntimeException("Cannot copy: null");
        }
        // Post-flip: every user-visible Pure value is a PureDynamicObject.
        // The typed `Any._copy()` fast path is dead now that FB-decode no
        // longer hands back typed XImpls — transient XImpls inside decoder
        // lambdas don't escape `readProperty`.
        Object copy = pdoCopy(original);
        // Fix self-referencing CGT (e.g., Class<x> where x == original) —
        // _copy() preserves the CGT reference to the source object, but
        // for self-referential cases we need to rewire it to the copy.
        Object cgt = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.readBySlot(original, SLOT_CLASSIFIER_GENERIC_TYPE);
        if (cgt != null && hasSelfReference(cgt, original))
        {
            cgt = deepCopyCgt(cgt, original, copy, resolver);
            org.finos.legend.pure.truffle.runtime.dynobj.PureObj.write(copy, "classifierGenericType", cgt);
        }
        // Platform-level canonical anchor: preserve canonical GenericType_<TypeName>
        // UDPGT-PE references through copy. Symmetric to new() canonical anchoring.
        if (cgt != null)
        {
            org.finos.legend.pure.truffle.runtime.dynobj.PureObj.write(copy, "classifierGenericType",
                    NewWithKeysNode.preferCanonicalAnchor(cgt, resolver));
        }
        return copy;
    }

    /**
     * Package-visible PDO copy used by sibling natives (CopyWithKeysNode's
     * setDeepProperty needs to deep-copy intermediate PDO targets along a
     * dotted path so mutations don't leak into the original).
     */
    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    static Object pdoCopyPublic(Object original) { return pdoCopy(original); }

    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    private static Object pdoCopy(Object original)
    {
        org.finos.legend.pure.truffle.runtime.dynobj.PureDynamicObject src =
                (org.finos.legend.pure.truffle.runtime.dynobj.PureDynamicObject) original;
        // Construct a fresh copy against the same per-class PureClassInfo
        // (slot table is shared) and copy the source's slots directly.
        // The copy starts with whatever values the source has — including
        // LAZY for unmaterialised slots, so the copy will decode-on-demand
        // from src.fb the same way the source would.
        org.finos.legend.pure.truffle.runtime.dynobj.PureDynamicObject copy =
                new org.finos.legend.pure.truffle.runtime.dynobj.PureDynamicObject(
                        src.classInfo, src.fb, src.resolver, src.parent);
        System.arraycopy(src.slots, 0, copy.slots, 0, src.slots.length);
        return copy;
    }

    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    private static boolean hasSelfReference(Object gtv, Object original)
    {
        if (org.finos.legend.pure.truffle.runtime.dynobj.PureObj.readBySlot(gtv, SLOT_TYPE) == original) return true;
        Object taObj = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.readBySlot(gtv, SLOT_TYPE_ARGUMENTS);
        if (taObj instanceof org.finos.legend.pure.truffle.types.PureSequence typeArgs)
        {
            for (int i = 0; i < typeArgs.size(); i++)
            {
                Object ta = typeArgs.getBoxed(i);
                if (ta != null && hasSelfReference(ta, original)) return true;
            }
        }
        return false;
    }

    /**
     * Deep-copy a GenericTypeValue tree, replacing all references to {@code original}
     * with {@code copy}. Handles type pointers and TypeParameter owner pointers.
     */
    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    private static Object deepCopyCgt(Object gtv, Object original, Object copy,
                                                org.finos.legend.pure.truffle.runtime.TruffleMetadataAccess resolver)
    {
        if (gtv == null) return null;
        var result = org.finos.legend.pure.truffle.runtime.helper._GenericType.buildUserDefinedGenericType(
                null, resolver);
        // Copy type — substitute self-references. The typed `instanceof Type`
        // guard on `copy` was assertion-only (copy == _copy() of original which
        // is a Type), so widening to non-null is safe.
        Object type = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.readBySlot(gtv, SLOT_TYPE);
        if (type == original && copy != null)
        {
            org.finos.legend.pure.truffle.runtime.dynobj.PureObj.write(result, "type", copy);
        }
        else if (org.finos.legend.pure.truffle.runtime.dynobj.PureObj.pureTypeIs(type,
                "meta::pure::metamodel::type::generics::TypeParameter")
                && org.finos.legend.pure.truffle.runtime.dynobj.PureObj.readBySlot(type, SLOT_OWNER) == original)
        {
            // Copy the TypeParameter (always PDO post-flip), then override
            // owner to point at the new instance.
            Object tpCopy = pdoCopyPublic(type);
            org.finos.legend.pure.truffle.runtime.dynobj.PureObj.write(tpCopy, "owner", copy);
            org.finos.legend.pure.truffle.runtime.dynobj.PureObj.write(result, "type", tpCopy);
        }
        else
        {
            org.finos.legend.pure.truffle.runtime.dynobj.PureObj.write(result, "type", type);
        }
        // Deep-copy typeArguments recursively
        Object taObj = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.readBySlot(gtv, SLOT_TYPE_ARGUMENTS);
        if (taObj instanceof org.finos.legend.pure.truffle.types.PureSequence typeArgs && !typeArgs.isEmpty())
        {
            Object[] copied = new Object[typeArgs.size()];
            for (int i = 0; i < typeArgs.size(); i++)
            {
                Object ta = typeArgs.getBoxed(i);
                // deepCopyCgt now accepts Object — handles PDO and typed
                // GenericTypeValue uniformly via PureObj.read.
                if (ta != null && org.finos.legend.pure.truffle.runtime.dynobj.PureObj.isType(ta,
                        "meta::pure::metamodel::type::generics::GenericTypeValue",
                        org.finos.legend.pure.truffle.PureLanguage.get(null).resolver()))
                {
                    copied[i] = deepCopyCgt(ta, original, copy, resolver);
                }
                else
                {
                    copied[i] = ta;
                }
            }
            org.finos.legend.pure.truffle.runtime.dynobj.PureObj.write(result, "typeArguments",
                    new org.finos.legend.pure.truffle.types.ObjectSequence(copied));
        }
        return result;
    }
}
