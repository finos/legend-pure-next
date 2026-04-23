package org.finos.legend.pure.truffle.runtime.helper;

import org.finos.legend.pure.truffle.runtime.TruffleMetadataAccess;
import org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.PackageableElement;
import org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.type.Type;
import org.finos.legend.pure.truffle.types.PureSequence;

import java.util.ArrayList;
import java.util.List;

public final class _Type
{
    private _Type() {}

    public static List<Type> linearize(Type type, TruffleMetadataAccess resolver)
    {
        List<Type> result = new ArrayList<>();
        linearizeRecursive(type, result);
        return result;
    }

    private static void linearizeRecursive(Type type, List<Type> result)
    {
        if (type == null || containsType(result, type))
        {
            return;
        }
        result.add(type);
        Object gens = type._generalizations();
        if (gens instanceof PureSequence seq)
        {
            for (Object gen : seq.toBoxedArray())
            {
                if (gen instanceof org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.relationship.Generalization g)
                {
                    Type superType = _GenericType.type(g._general());
                    linearizeRecursive(superType, result);
                }
            }
        }
    }

    /**
     * Check whether a type list already contains a given type, using
     * path-based comparison rather than identity, because FlatBuffer
     * wrapper objects for the same PDB element are not identity-equal.
     */
    private static boolean containsType(List<Type> list, Type type)
    {
        for (Type t : list)
        {
            if (sameType(t, type))
            {
                return true;
            }
        }
        return false;
    }

    /**
     * Path-based type equality.  FlatBuffer wrappers for the same PDB
     * element are distinct Java objects, so {@code ==} and the default
     * {@code equals} fail.  Comparing the full element path is safe and
     * correct for all packageable types used in the metamodel.
     */
    private static boolean sameType(Type a, Type b)
    {
        if (a == b)
        {
            return true;
        }
        if (a instanceof PackageableElement peA && b instanceof PackageableElement peB)
        {
            String pathA = _PackageableElement.path(peA);
            String pathB = _PackageableElement.path(peB);
            return pathA != null && pathA.equals(pathB);
        }
        return false;
    }

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
        // Any is the top type — everything is a subtype of Any.
        if (isTopType(sup))
        {
            return true;
        }
        // Path-based identity (FlatBuffer wrappers differ for same element)
        if (sameType(sub, sup))
        {
            return true;
        }
        for (Type ancestor : linearize(sub, resolver))
        {
            if (sameType(ancestor, sup))
            {
                return true;
            }
        }
        return false;
    }

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
        List<Type> firstLin = linearize(types.get(0), resolver);
        for (Type candidate : firstLin)
        {
            boolean inAll = true;
            for (int i = 1; i < types.size(); i++)
            {
                if (!linearize(types.get(i), resolver).contains(candidate))
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

    public static boolean isTopType(Type type)
    {
        return type instanceof PackageableElement pe && "Any".equals(pe._name());
    }

    public static String print(Type type)
    {
        return print(type, null);
    }

    public static String print(Type type, TruffleMetadataAccess resolver)
    {
        if (type == null)
        {
            return "null";
        }
        if (type instanceof PackageableElement pe)
        {
            String path = _PackageableElement.path(pe, resolver);
            if (path != null && !path.isEmpty())
            {
                return path;
            }
            String name = pe._name();
            if (name != null)
            {
                return name;
            }
        }
        return type.getClass().getSimpleName();
    }
}
