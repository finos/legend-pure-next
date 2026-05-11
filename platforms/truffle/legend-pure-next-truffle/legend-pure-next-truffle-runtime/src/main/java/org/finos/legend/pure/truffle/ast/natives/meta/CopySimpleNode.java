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
    @Child
    private PureNode child;

    private final Object genericType;
    private final Object multiplicity;

    public CopySimpleNode(PureNode child, Object genericType, Object multiplicity)
    {
        this.child = child;
        this.genericType = genericType;
        this.multiplicity = multiplicity;
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
        Object cgt = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.read(original, "classifierGenericType");
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
                    NewWithKeysNode.preferCanonicalAnchorPublic(cgt, resolver));
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
        // Construct against the *root* Shape for the Pure path (per-class
        // singleton in PureShapeRegistry) — DynamicObject's constructor
        // refuses a Shape that's already been specialized with instance
        // properties, which src.getShape() would be after prior `dol.put`s.
        // Carry the FB from src so the copy can lazy-decode the same
        // properties on demand (covers fields the source hasn't materialised
        // yet — without this they'd come back null on the copy and break
        // structural equality with the original).
        Object dt = src.getShape().getDynamicType();
        com.oracle.truffle.api.object.Shape rootShape = (dt instanceof String purePath)
                ? org.finos.legend.pure.truffle.runtime.dynobj.PureShapeRegistry.shapeFor(purePath)
                : src.getShape();
        org.finos.legend.pure.truffle.runtime.dynobj.PureDynamicObject copy =
                new org.finos.legend.pure.truffle.runtime.dynobj.PureDynamicObject(
                        rootShape, src.fb, src.resolver, src.parent);
        com.oracle.truffle.api.object.DynamicObjectLibrary dol =
                com.oracle.truffle.api.object.DynamicObjectLibrary.getUncached();
        // Carry over the source's already-materialised values so subsequent
        // reads on the copy hit the DOL fast path instead of re-decoding.
        Object[] keys = dol.getKeyArray(src);
        for (Object key : keys)
        {
            if (!(key instanceof String name)) continue;
            Object v = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.read(src, name);
            dol.put(copy, name, v);
        }
        return copy;
    }

    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    private static String printGtv(Object gtv, Object original, Object copy, int depth)
    {
        if (gtv == null) return "null";
        String indent = "  ".repeat(depth);
        StringBuilder sb = new StringBuilder();
        Object type = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.read(gtv, "type");
        String typeName = type == null ? "null"
                : type == original ? "ORIGINAL@" + System.identityHashCode(original)
                : type == copy ? "COPY@" + System.identityHashCode(copy)
                : type.getClass().getName() + "@" + System.identityHashCode(type);
        sb.append("GT(type=").append(typeName);
        Object taObj = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.read(gtv, "typeArguments");
        if (taObj instanceof org.finos.legend.pure.truffle.types.PureSequence ta && !ta.isEmpty())
        {
            sb.append(", typeArgs=[");
            for (int i = 0; i < ta.size(); i++)
            {
                if (i > 0) sb.append(", ");
                Object elem = ta.getBoxed(i);
                if (elem != null && org.finos.legend.pure.truffle.runtime.dynobj.PureObj.pureTypeOf(elem) != null)
                {
                    sb.append("\n").append(indent).append("  ").append(printGtv(elem, original, copy, depth + 1));
                }
                else
                {
                    sb.append(elem != null ? elem.getClass().getName() : "null");
                }
            }
            sb.append("]");
        }
        sb.append(")");
        return sb.toString();
    }

    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    private static boolean hasSelfReference(Object gtv, Object original)
    {
        if (org.finos.legend.pure.truffle.runtime.dynobj.PureObj.read(gtv, "type") == original) return true;
        Object taObj = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.read(gtv, "typeArguments");
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
        Object type = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.read(gtv, "type");
        if (type == original && copy != null)
        {
            org.finos.legend.pure.truffle.runtime.dynobj.PureObj.write(result, "type", copy);
        }
        else if (org.finos.legend.pure.truffle.runtime.dynobj.PureObj.pureTypeIs(type,
                "meta::pure::metamodel::type::generics::TypeParameter")
                && org.finos.legend.pure.truffle.runtime.dynobj.PureObj.read(type, "owner") == original)
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
        Object taObj = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.read(gtv, "typeArguments");
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
