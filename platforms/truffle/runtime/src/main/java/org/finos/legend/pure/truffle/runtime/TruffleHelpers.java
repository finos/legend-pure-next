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

package org.finos.legend.pure.truffle.runtime;

import org.finos.legend.pure.truffle.runtime.TruffleMetadataAccess;
import org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.PackageableElement;
import org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.type.Type;
import org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.type.generics.GenericType;
import org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.type.generics.GenericTypeValue;
import org.finos.legend.pure.truffle.types.PureSequence;

import java.util.ArrayList;
import java.util.List;

/**
 * Truffle-specific helpers that replace the bootstrap compiler helpers
 * ({@code _GenericType}, {@code _PackageableElement}, {@code _Type}).
 * These work with truffle-namespaced types and {@code PureSequence}.
 */
public final class TruffleHelpers
{
    private TruffleHelpers()
    {
    }

    // =========================================================================
    // _GenericType replacements
    // =========================================================================

    /**
     * Extract the raw {@link Type} from a {@link GenericType}.
     * Equivalent to {@code org.finos.legend.pure.truffle.runtime.helper._GenericType.type(gt)}.
     */
    public static Type genericTypeToType(Object gt)
    {
        if (gt instanceof GenericTypeValue gtv)
        {
            Object t = gtv._type();
            return t instanceof Type type ? type : null;
        }
        if (gt instanceof GenericType g)
        {
            // GenericType base interface has no _type() — only GenericTypeValue does.
            return null;
        }
        return null;
    }

    /**
     * Get the type arguments of a {@link GenericType} as a {@link PureSequence}.
     * Equivalent to {@code org.finos.legend.pure.truffle.runtime.helper._GenericType.typeArguments(gt)}.
     */
    public static PureSequence typeArguments(Object gt)
    {
        if (gt instanceof GenericTypeValue gtv)
        {
            Object ta = gtv._typeArguments();
            return ta instanceof PureSequence seq ? seq : null;
        }
        return null;
    }

    // =========================================================================
    // _PackageableElement replacements
    // =========================================================================

    /**
     * Get the fully-qualified {@code ::}-separated path of a {@link PackageableElement}.
     * Equivalent to {@code org.finos.legend.pure.truffle.runtime.helper._PackageableElement.path(pe)}.
     */
    public static String path(PackageableElement pe)
    {
        if (pe == null)
        {
            return null;
        }
        String name = pe._name();
        Object pkg = pe._package();
        if (pkg == null || !(pkg instanceof PackageableElement parent))
        {
            return name;
        }
        String parentPath = path(parent);
        if (parentPath == null || parentPath.isEmpty() || "Root".equals(parentPath))
        {
            return name;
        }
        return parentPath + "::" + name;
    }

    // =========================================================================
    // _Type replacements
    // =========================================================================

    /**
     * C3 linearization of a type.
     * Equivalent to {@code org.finos.legend.pure.truffle.runtime.helper._Type.linearize(type, model)}.
     */
    public static List<Type> linearize(Type type, TruffleMetadataAccess resolver)
    {
        List<Type> result = new ArrayList<>();
        linearizeRecursive(type, result);
        return result;
    }

    private static void linearizeRecursive(Type type, List<Type> result)
    {
        if (type == null || result.contains(type))
        {
            return;
        }
        result.add(type);
        if (type._generalizations() instanceof PureSequence gens)
        {
            for (Object gen : gens.toBoxedArray())
            {
                if (gen instanceof org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.relationship.Generalization g)
                {
                    Type superType = genericTypeToType(g._general());
                    linearizeRecursive(superType, result);
                }
            }
        }
    }

    /**
     * Check if {@code sub} is a subtype of {@code sup}.
     * Equivalent to {@code org.finos.legend.pure.truffle.runtime.helper._Type.subtypeOf(sub, sup, model)}.
     */
    public static boolean subtypeOf(Type sub, Type sup, TruffleMetadataAccess resolver)
    {
        if (sub == sup)
        {
            return true;
        }
        if (sub == null || sup == null)
        {
            return false;
        }
        List<Type> lin = linearize(sub, resolver);
        return lin.contains(sup);
    }

    /**
     * Find the least common type (LUB) of a list of types.
     * Equivalent to {@code org.finos.legend.pure.truffle.runtime.helper._Type.findCommonType(types, contravariant, model)}.
     */
    public static Type findCommonType(List<Type> types, boolean contravariant, TruffleMetadataAccess resolver)
    {
        if (types == null || types.isEmpty())
        {
            return null;
        }
        if (types.size() == 1)
        {
            return types.get(0);
        }
        // Find the first type in the first linearization that's in ALL linearizations
        List<Type> firstLin = linearize(types.get(0), resolver);
        for (Type candidate : firstLin)
        {
            boolean inAll = true;
            for (int i = 1; i < types.size(); i++)
            {
                List<Type> otherLin = linearize(types.get(i), resolver);
                if (!otherLin.contains(candidate))
                {
                    inAll = false;
                    break;
                }
            }
            if (inAll)
            {
                return candidate;
            }
        }
        return null;
    }

    /**
     * Check if a type is the top type (Any).
     */
    public static boolean isTopType(Type type)
    {
        if (type instanceof PackageableElement pe)
        {
            return "Any".equals(pe._name());
        }
        return false;
    }

    /**
     * Build a UserDefinedGenericType for a given type.
     * Equivalent to {@code org.finos.legend.pure.truffle.runtime.helper._GenericType.buildUserDefinedGenericType(type, resolver)}.
     */
    public static org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.type.generics.UserDefinedGenericTypeImpl
    buildUserDefinedGenericType(Type type, TruffleMetadataAccess resolver)
    {
        var gt = new org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.type.generics.UserDefinedGenericTypeImpl();
        gt._type(type);
        return gt;
    }
}
