package org.finos.legend.pure.truffle.runtime;

import org.finos.legend.pure.truffle.types.PureSequence;

/**
 * Helper for resolving nested PDB elements (Properties, QualifiedProperties)
 * that aren't top-level packageable elements. These are stored in ClassDef
 * and accessed via compound paths like "pkg::ClassName.propertyName".
 */
public final class FbsResolverHelper
{
    private FbsResolverHelper() {}

    /**
     * Resolve a nested element path by navigating to the owning class
     * and searching its properties/qualifiedProperties.
     */
    public static Object resolveNestedElement(String path, TruffleMetadataAccess resolver)
    {
        if (path == null || !path.contains("."))
        {
            return null;
        }
        int dotIdx = path.lastIndexOf('.');
        String ownerPath = path.substring(0, dotIdx);
        String memberName = path.substring(dotIdx + 1);

        // Strip the mangled signature suffix to get the simple name
        // e.g. "fullName_Boolean_1_" → "fullName"
        String simpleName = memberName;
        int underIdx = memberName.indexOf('_');
        if (underIdx > 0)
        {
            simpleName = memberName.substring(0, underIdx);
        }

        Object owner = resolver.getElement(ownerPath);
        if (owner == null)
        {
            return null;
        }

        // Search using reflection to avoid compile-time dependency on generated types
        Object result = searchPropertyList(owner, "qualifiedProperties", simpleName);
        if (result != null) return result;
        result = searchPropertyList(owner, "properties", simpleName);
        if (result != null) return result;
        result = searchPropertyList(owner, "propertiesFromAssociations", simpleName);
        return result;
    }

    private static Object searchPropertyList(Object owner, String listName, String targetName)
    {
        try
        {
            java.lang.reflect.Method getter = owner.getClass().getMethod("_" + listName);
            Object list = getter.invoke(owner);
            if (list instanceof PureSequence seq)
            {
                for (int i = 0; i < seq.size(); i++)
                {
                    Object item = seq.getBoxed(i);
                    java.lang.reflect.Method nameGetter = item.getClass().getMethod("_name");
                    Object name = nameGetter.invoke(item);
                    if (targetName.equals(name))
                    {
                        return item;
                    }
                }
            }
        }
        catch (Exception ignored)
        {
        }
        return null;
    }
}
