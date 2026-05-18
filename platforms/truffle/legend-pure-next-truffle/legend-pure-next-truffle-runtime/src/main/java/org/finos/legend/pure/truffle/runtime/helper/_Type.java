package org.finos.legend.pure.truffle.runtime.helper;

import org.finos.legend.pure.truffle.runtime.TruffleMetadataAccess;
import org.finos.legend.pure.truffle.runtime.dynobj.PureObj;

import java.util.List;

public final class _Type
{

    private static final int SLOT_NAME = org.finos.legend.pure.truffle.runtime.dynobj.PureClassRegistry.globalSlot("name");
    private _Type() {}

    @SuppressWarnings("unchecked")
    public static List<Object> linearize(Object type, TruffleMetadataAccess resolver)
    {
        // Memoised on the resolver — identity-keyed, computed once per Type.
        // Cast: the runtime TypeCache impl always stores List<Object>; the
        // List<?> contract on TruffleTypeCache is purely so the interface
        // can live in codegen (where the generated Type class isn't yet on
        // the classpath at first-pass compile).
        return (List<Object>) resolver.typeCache().linearization(type);
    }

    @SuppressWarnings("unchecked")
    public static boolean subtypeOf(Object sub, Object sup, TruffleMetadataAccess resolver)
    {
        if (sub == sup)
        {
            return true;
        }
        if (sub == null || sup == null)
        {
            return false;
        }
        // Pointer-aware: the compiler wraps cross-element type refs as
        // TempCompilerPointer (ClassPointer / PrimitiveTypePointer / etc.).
        // Pointers carry only `.path`; identity-based ops (ancestors set,
        // TypeCache) are keyed on live class instances. Resolve via the
        // registry before consulting the cache.
        sub = derefIfPointer(sub, resolver);
        sup = derefIfPointer(sup, resolver);
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
        return ((java.util.Set<Object>) resolver.typeCache().ancestors(sub)).contains(sup);
    }

    private static Object derefIfPointer(Object type, TruffleMetadataAccess resolver)
    {
        if (type == null || resolver == null) return type;
        // Don't use PureObj.isType here — it calls back into subtypeOf,
        // causing infinite recursion. Detect pointer class by its pure path
        // prefix instead (TempCompilerPointer subclasses all live under
        // `meta::pure::metamodel::pointer::`).
        String typePath = PureObj.pureTypeOf(type);
        if (typePath == null || !typePath.startsWith("meta::pure::metamodel::pointer::"))
        {
            return type;
        }
        Object pathVal = PureObj.readBySlot(type,
                org.finos.legend.pure.truffle.runtime.dynobj.PureClassRegistry.globalSlot("path"));
        if (!(pathVal instanceof String path) || path.isEmpty()) return type;
        Object el = resolver.getElement(path);
        return el != null ? el : type;
    }

    public static Object findCommonType(List<?> types, boolean contravariant, TruffleMetadataAccess resolver)
    {
        if (types == null || types.isEmpty())
        {
            return null;
        }
        if (types.size() == 1)
        {
            return types.get(0);
        }
        List<Object> firstLin = linearize(types.get(0), resolver);
        for (Object candidate : firstLin)
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
    private static volatile Object anyTypeRef;

    private static boolean isTopType(Object type, TruffleMetadataAccess resolver)
    {
        if (type == null) return false;
        Object any = anyTypeRef;
        if (any != null)
        {
            return type == any;
        }
        // First call: resolve and cache. Volatile write publishes the
        // reference for subsequent unsynchronised reads.
        if (resolver != null)
        {
            Object resolved = resolver.getElement("meta::pure::metamodel::type::Any");
            if (resolved != null)
            {
                anyTypeRef = resolved;
                return type == resolved;
            }
        }
        // Fallback: string compare (only hits before "Any" is in the resolver).
        // Type extends PackageableElement, so the instanceof guard is implicit.
        return "Any".equals(PureObj.readBySlot(type, SLOT_NAME));
    }

}
