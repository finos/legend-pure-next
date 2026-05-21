package org.finos.legend.pure.truffle.runtime.helper;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import org.finos.legend.pure.truffle.runtime.TruffleMetadataAccess;
import org.finos.legend.pure.truffle.runtime.dynobj.PureObj;

public final class _PackageableElement
{
    private _PackageableElement() {}

    /**
     * Identity-keyed cache for PEs whose path the resolver doesn't know
     * (i.e. PEs created during compilation, not loaded from a PDB). Once
     * a PE has a path it's invariant — paths are derived from the
     * package chain which is set at construction. JFR identified
     * {@code packagePath} (StringBuilder.insert chain) at ~5% of self-
     * compile CPU before this cache.
     */
    // Synchronized IdentityHashMap. An earlier copy-on-write variant pinned
    // reads to a volatile snapshot for lock-free cache hits — a win on the
    // self-compile bench (fixed PDO set, many repeat reads) but pathological
    // for the IDE F9 cycle: each round inserts thousands of fresh
    // in-memory-compile PDOs that never hit the cache, every put copied
    // the full map, and the cache itself accumulated across F9s. Result was
    // quadratic in cache size — observed: F9#1=368ms, F9#2=1591ms,
    // F9#3=3092ms, F9#4=8381ms for the same element. The synchronized
    // wrapper costs ~1% extra warm CPU on read paths; that's far cheaper
    // than the quadratic blow-up.
    private static final java.util.Map<Object, String> PATH_CACHE =
            java.util.Collections.synchronizedMap(new java.util.IdentityHashMap<>());

    // @TruffleBoundary — the fallback walks the package chain via
    // StringBuilder.insert(0, ...), which Graal's PE follows into
    // String.length / substring / Preconditions / Locale / Formatter.
    // Path resolution is not on the tightest inner loop and is cheap
    // past a boundary; the resolver fast-path returns immediately.
    @TruffleBoundary
    public static String path(Object pe, TruffleMetadataAccess resolver)
    {
        if (pe == null)
        {
            return null;
        }
        // TempCompilerPointer carries its canonical path directly in the `.path`
        // slot; its inherited `.name`/`.package` slots are intentionally unset
        // (pointers are opaque). Short-circuit to that slot before consulting
        // the resolver or walking the package chain — otherwise the walk reads
        // null name/package and returns "" or NPEs.
        String ptrPath = pointerPath(pe);
        if (ptrPath != null) return ptrPath;
        // Fast path: resolver knows the canonical path from the PDB index
        if (resolver != null)
        {
            String stored = resolver.pathOf(pe);
            if (stored != null)
            {
                return stored;
            }
        }
        String cached = PATH_CACHE.get(pe);
        if (cached != null)
        {
            return cached;
        }
        // Fallback: walk the package chain
        String result = computePath(pe);
        if (result != null && !result.isEmpty())
        {
            putPathCache(pe, result);
        }
        return result;
    }

    private static final int SLOT_POINTER_PATH =
            org.finos.legend.pure.truffle.runtime.dynobj.PureClassRegistry.globalSlot("path");

    private static final String POINTER_TYPE_PREFIX = "meta::pure::metamodel::pointer::";

    /**
     * If {@code v} is a {@code TempCompilerPointer} subtype, return its
     * {@code .path} slot value; otherwise return {@code null}.
     *
     * <p>Used by {@link #path} and {@link #derefPackagePath} to short-circuit
     * the package-chain walk when the value is a pointer — pointer's
     * {@code .path} carries the canonical path directly.</p>
     */
    public static String pointerPath(Object v)
    {
        if (v == null) return null;
        String typePath = PureObj.pureTypeOf(v);
        if (typePath == null || !typePath.startsWith(POINTER_TYPE_PREFIX)) return null;
        Object slotPath = PureObj.readBySlot(v, SLOT_POINTER_PATH);
        // Empty path is a legal pointer value — {@code toPackagePointer}
        // produces it for parentless Packages (the root). Return the empty
        // string so callers reach the resolver; treating it as "no path" here
        // would leave a {@code PackagePointer(path='')} unresolved.
        return slotPath instanceof String s ? s : null;
    }

    private static void putPathCache(Object pe, String result)
    {
        PATH_CACHE.putIfAbsent(pe, result);
    }

    private static final String PACKAGE_PURE_PATH = "meta::pure::metamodel::Package";

    private static final int SLOT_PACKAGE =
            org.finos.legend.pure.truffle.runtime.dynobj.PureClassRegistry.globalSlot("package");
    private static final int SLOT_NAME =
            org.finos.legend.pure.truffle.runtime.dynobj.PureClassRegistry.globalSlot("name");

    private static Object readPackage(Object obj)
    {
        return obj instanceof org.finos.legend.pure.truffle.runtime.dynobj.PureDynamicObject pdo
                ? pdo.readSlot(SLOT_PACKAGE)
                : org.finos.legend.pure.truffle.runtime.dynobj.PureObj.readBySlot(obj, SLOT_PACKAGE);
    }

    private static Object readName(Object obj)
    {
        return obj instanceof org.finos.legend.pure.truffle.runtime.dynobj.PureDynamicObject pdo
                ? pdo.readSlot(SLOT_NAME)
                : org.finos.legend.pure.truffle.runtime.dynobj.PureObj.readBySlot(obj, SLOT_NAME);
    }

    private static String computePath(Object pe)
    {
        Object pkg = readPackage(pe);
        String name = (String) readName(pe);
        if (pkg == null || name == null)
        {
            return name != null ? name : "";
        }
        if (name.isEmpty())
        {
            return "";
        }
        // Package field may itself be a {@link PackagePointer} (compile-pure
        // wraps Class.package so the parent ref doesn't go stale across
        // pass-2 ^$pkg(children=...) rebuilds). Its {@code path} slot carries
        // the parent's full path directly — short-circuit the chain walk.
        String pkgPath = derefPackagePath(pkg);
        return pkgPath.isEmpty() ? name : pkgPath + "::" + name;
    }

    private static String derefPackagePath(Object pkg)
    {
        String ptrPath = pointerPath(pkg);
        return ptrPath != null ? ptrPath : packagePath(pkg);
    }

    private static String packagePath(Object pkg)
    {
        StringBuilder sb = new StringBuilder();
        Object current = pkg;
        int depth = 0;
        while (current != null
                && org.finos.legend.pure.truffle.runtime.dynobj.PureObj.pureTypeIs(current, PACKAGE_PURE_PATH)
                && depth < 15)
        {
            Object parent = readPackage(current);
            String name = (String) readName(current);
            if (parent == null || name == null || name.isEmpty())
            {
                break;
            }
            if (parent == current)
            {
                break;
            }
            if (sb.length() > 0)
            {
                sb.insert(0, "::");
            }
            sb.insert(0, name);
            current = parent;
            depth++;
        }
        return sb.toString();
    }
}
