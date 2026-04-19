package org.finos.legend.pure.truffle.runtime.helper;

import org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.PackageableElement;

public final class _PackageableElement
{
    private _PackageableElement() {}

    public static String path(PackageableElement pe)
    {
        if (pe == null)
        {
            return null;
        }
        Object pkg = pe._package();
        String name = pe._name();
        if (pkg == null || name == null)
        {
            return name != null ? name : "";
        }
        String pkgPath = packagePath(pkg);
        return pkgPath.isEmpty() ? name : pkgPath + "::" + name;
    }

    private static String packagePath(Object pkg)
    {
        if (!(pkg instanceof org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.Package p))
        {
            return "";
        }
        Object parentPkg = p._package();
        String name = p._name();
        if (parentPkg == null || name == null)
        {
            return "";
        }
        String parentPath = packagePath(parentPkg);
        return parentPath.isEmpty() ? name : parentPath + "::" + name;
    }
}
