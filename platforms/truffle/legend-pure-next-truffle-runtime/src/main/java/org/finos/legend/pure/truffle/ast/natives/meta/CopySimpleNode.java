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
        // Typed _copy() on Any — codegen-emitted, replaces what used to be
        // an O(N×M) reflective getter/setter walk per copy. JFR identified
        // the reflection path as ~75% of self-compile CPU.
        Object copy = anyOrig._copy();
        // Fix self-referencing CGT (e.g., Class<x> where x == original) —
        // _copy() preserves the CGT reference to the source object, but
        // for self-referential cases we need to rewire it to the copy.
        GenericTypeValue cgt = anyOrig._classifierGenericType();
        if (copy instanceof Any anyC && cgt != null && hasSelfReference(cgt, original))
        {
            anyC._classifierGenericType(deepCopyCgt(cgt, original, copy, resolver));
        }
        return copy;
    }

    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    private static String printGtv(GenericTypeValue gtv, Object original, Object copy, int depth)
    {
        if (gtv == null) return "null";
        String indent = "  ".repeat(depth);
        StringBuilder sb = new StringBuilder();
        var type = gtv._type();
        String typeName = type == null ? "null"
                : type == original ? "ORIGINAL@" + System.identityHashCode(original)
                : type == copy ? "COPY@" + System.identityHashCode(copy)
                : type.getClass().getName() + "@" + System.identityHashCode(type);
        sb.append("GT(type=").append(typeName);
        var ta = gtv._typeArguments();
        if (ta != null && !ta.isEmpty())
        {
            sb.append(", typeArgs=[");
            for (int i = 0; i < ta.size(); i++)
            {
                if (i > 0) sb.append(", ");
                Object elem = ta.getBoxed(i);
                if (elem instanceof GenericTypeValue inner)
                {
                    sb.append("\n").append(indent).append("  ").append(printGtv(inner, original, copy, depth + 1));
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
    private static boolean hasSelfReference(GenericTypeValue gtv, Object original)
    {
        if (gtv._type() == original) return true;
        var typeArgs = gtv._typeArguments();
        if (typeArgs != null)
        {
            for (int i = 0; i < typeArgs.size(); i++)
            {
                Object ta = typeArgs.getBoxed(i);
                if (ta instanceof GenericTypeValue inner && hasSelfReference(inner, original)) return true;
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
        org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.type.Type type = gtv._type();
        if (type == original && copy instanceof org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.type.Type copyType)
        {
            result._type(copyType);
        }
        else if (type instanceof org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.type.generics.TypeParameter tp && tp._owner() == original)
        {
            var tpCopy = tp._copy();
            if (copy instanceof org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.type.generics.TypeAndMultiplicityParametersOwner tmpo)
            {
                tpCopy._owner(tmpo);
            }
            result._type(tpCopy);
        }
        else
        {
            result._type(type);
        }
        // Deep-copy typeArguments recursively
        var typeArgs = gtv._typeArguments();
        if (typeArgs != null && !typeArgs.isEmpty())
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
            result._typeArguments(new org.finos.legend.pure.truffle.types.ObjectSequence(copied));
        }
        return result;
    }
}
