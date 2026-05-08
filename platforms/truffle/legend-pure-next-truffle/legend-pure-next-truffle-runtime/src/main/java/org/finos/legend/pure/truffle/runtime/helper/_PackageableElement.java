package org.finos.legend.pure.truffle.runtime.helper;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.PackageableElement;
import org.finos.legend.pure.truffle.runtime.TruffleMetadataAccess;

public final class _PackageableElement
{
    private _PackageableElement() {}

    /**
     * Return the canonical Pure path for a PackageableElement.
     *
     * <p>Prefers the resolver's {@code pathOf()} reverse lookup (O(1) from
     * the PDB index) over walking the FlatBuffer package chain, which is
     * order-dependent and can produce inconsistent results.</p>
     */
    public static String path(PackageableElement pe)
    {
        return path(pe, null);
    }

    /**
     * Identity-keyed cache for PEs whose path the resolver doesn't know
     * (i.e. PEs created during compilation, not loaded from a PDB). Once
     * a PE has a path it's invariant — paths are derived from the
     * package chain which is set at construction. JFR identified
     * {@code packagePath} (StringBuilder.insert chain) at ~5% of self-
     * compile CPU before this cache.
     */
    // Volatile copy-on-write IdentityHashMap. Reads (cache hits — the hot
    // path) are unsynchronized via the volatile reference. Writes take a
    // monitor on PATH_CACHE_LOCK, copy the full map, and atomically
    // install the new snapshot. Same pattern as TypeCache.entries; the
    // synchronizedMap wrapper showed up at ~1% of warm CPU on this site
    // alone before this change because path() is on the hot path of every
    // type/element comparison and metaprogramming operation.
    private static volatile java.util.IdentityHashMap<PackageableElement, String> PATH_CACHE =
            new java.util.IdentityHashMap<>();
    private static final Object PATH_CACHE_LOCK = new Object();

    // @TruffleBoundary — the fallback walks the package chain via
    // StringBuilder.insert(0, ...), which Graal's PE follows into
    // String.length / substring / Preconditions / Locale / Formatter.
    // Path resolution is not on the tightest inner loop and is cheap
    // past a boundary; the resolver fast-path returns immediately.
    @TruffleBoundary
    public static String path(PackageableElement pe, TruffleMetadataAccess resolver)
    {
        if (pe == null)
        {
            return null;
        }
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

    private static void putPathCache(PackageableElement pe, String result)
    {
        synchronized (PATH_CACHE_LOCK)
        {
            if (PATH_CACHE.containsKey(pe))
            {
                return;
            }
            java.util.IdentityHashMap<PackageableElement, String> next = new java.util.IdentityHashMap<>(PATH_CACHE);
            next.put(pe, result);
            PATH_CACHE = next;
        }
    }

    private static String computePath(PackageableElement pe)
    {
        Object pkg = pe._package();
        String name = pe._name();
        if (pkg == null || name == null)
        {
            return name != null ? name : "";
        }
        if (name.isEmpty())
        {
            return "";
        }
        String pkgPath = packagePath(pkg);
        return pkgPath.isEmpty() ? name : pkgPath + "::" + name;
    }

    private static String packagePath(Object pkg)
    {
        StringBuilder sb = new StringBuilder();
        Object current = pkg;
        int depth = 0;
        while (current instanceof org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.Package p && depth < 15)
        {
            Object parent = p._package();
            String name = p._name();
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
