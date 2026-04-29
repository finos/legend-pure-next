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
        if (type == null || result.contains(type))
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
        if (isTopType(sup))
        {
            return true;
        }
        for (Type ancestor : linearize(sub, resolver))
        {
            if (ancestor == sup)
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

    private static boolean isTopType(Type type)
    {
        return type instanceof PackageableElement pe && "Any".equals(pe._name());
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
