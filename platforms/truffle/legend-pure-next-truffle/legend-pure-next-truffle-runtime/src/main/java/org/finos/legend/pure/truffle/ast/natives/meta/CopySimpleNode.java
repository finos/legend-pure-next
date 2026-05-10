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
        return doCopy(result, getResolver());
    }

    private static Object doCopy(Object original, org.finos.legend.pure.truffle.runtime.TruffleMetadataAccess resolver)
    {
        if (!(original instanceof Any anyOrig))
        {
            throw new RuntimeException("Cannot copy: " + (original == null ? "null" : original.getClass().getName()));
        }
        // Typed _copy() on Any — covariant return per generated subclass
        // produces an XImpl with all properties materialised. To be replaced
        // when the typed Any interface is dropped (step 3 of Goal 1) with a
        // PureDynamicObject.pureCopy() equivalent.
        Object copy = anyOrig._copy();
        // Fix self-referencing CGT (e.g., Class<x> where x == original) —
        // _copy() preserves the CGT reference to the source object, but
        // for self-referential cases we need to rewire it to the copy.
        Object cgtObj = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.read(original, "classifierGenericType");
        GenericTypeValue cgt = (GenericTypeValue) cgtObj;
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
    private static GenericTypeValue deepCopyCgt(GenericTypeValue gtv, Object original, Object copy,
                                                org.finos.legend.pure.truffle.runtime.TruffleMetadataAccess resolver)
    {
        if (gtv == null) return null;
        var result = org.finos.legend.pure.truffle.runtime.helper._GenericType.buildUserDefinedGenericType(
                null, resolver);
        // Copy type — substitute self-references
        Object type = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.read(gtv, "type");
        if (type == original && copy instanceof org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.type.Type copyType)
        {
            org.finos.legend.pure.truffle.runtime.dynobj.PureObj.write(result, "type", copyType);
        }
        else if (org.finos.legend.pure.truffle.runtime.dynobj.PureObj.pureTypeIs(type,
                "meta::pure::metamodel::type::generics::TypeParameter")
                && org.finos.legend.pure.truffle.runtime.dynobj.PureObj.read(type, "owner") == original
                && type instanceof org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.type.generics.TypeParameter tp)
        {
            var tpCopy = tp._copy();
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
                if (ta instanceof GenericTypeValue innerGtv)
                {
                    copied[i] = deepCopyCgt(innerGtv, original, copy, resolver);
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
