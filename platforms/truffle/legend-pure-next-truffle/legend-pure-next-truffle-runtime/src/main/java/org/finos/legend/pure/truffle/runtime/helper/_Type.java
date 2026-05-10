package org.finos.legend.pure.truffle.runtime.helper;

import org.finos.legend.pure.truffle.runtime.TruffleMetadataAccess;
import org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.type.Type;
import org.finos.legend.pure.truffle.runtime.dynobj.PureObj;

import java.util.List;

public final class _Type
{
    private _Type() {}

    @SuppressWarnings("unchecked")
    public static List<Type> linearize(Type type, TruffleMetadataAccess resolver)
    {
        // Memoised on the resolver — identity-keyed, computed once per Type.
        // Cast: the runtime TypeCache impl always stores List<Type>; the
        // List<?> contract on TruffleTypeCache is purely so the interface
        // can live in codegen (where the generated Type class isn't yet on
        // the classpath at first-pass compile).
        return (List<Type>) resolver.typeCache().linearization(type);
    }

    @SuppressWarnings("unchecked")
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
        if (isTopType(sup, resolver))
        {
            return true;
        }
        // O(1) identity-keyed lookup against the pre-built ancestors set —
        // replaces a linear scan over the linearization List that JFR
        // identified as the dominant subtypeOf hot path (~7% of warm CPU
        // on the metamodel_factories.pure self-host).
        return ((java.util.Set<Type>) resolver.typeCache().ancestors(sub)).contains(sup);
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

    /**
     * Identity-compare against the cached canonical "Any" type instead of
     * walking string equality every call. JFR identified ~19 samples on
     * the {@code "Any".equals(pe._name())} path before this caching.
     * The volatile field is initialised once on first call; subsequent
     * calls hit the cached reference directly.
     */
    private static volatile Type anyTypeRef;

    private static boolean isTopType(Type type, TruffleMetadataAccess resolver)
    {
        if (type == null) return false;
        Type any = anyTypeRef;
        if (any != null)
        {
            return type == any;
        }
        // First call: resolve and cache. Volatile write publishes the
        // reference for subsequent unsynchronised reads.
        if (resolver != null)
        {
            Object resolved = resolver.getElement("meta::pure::metamodel::type::Any");
            if (resolved instanceof Type t)
            {
                anyTypeRef = t;
                return type == t;
            }
        }
        // Fallback: string compare (only hits before "Any" is in the resolver).
        // Type extends PackageableElement, so the instanceof guard is implicit.
        return "Any".equals(PureObj.read(type, "name"));
    }

    public static String print(Type type, TruffleMetadataAccess resolver)
    {
        if (type == null)
        {
            return "null";
        }
        // Type extends PackageableElement; path() handles the lookup uniformly.
        String path = _PackageableElement.path(type, resolver);
        if (path != null && !path.isEmpty())
        {
            return path;
        }
        Object name = PureObj.read(type, "name");
        if (name instanceof String s && !s.isEmpty())
        {
            return s;
        }
        return type.getClass().getName();
    }
}
