package org.finos.legend.pure.truffle.runtime.helper;

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
        // Fallback: walk the package chain
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
