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
     * Resolve a typed PointerRef using its kind discriminator and path segments.
     */
    public static Object resolvePointerRef(org.finos.legend.pure.m3.module.pdbModule.fbs.PointerRef ref, TruffleMetadataAccess resolver)
    {
        if (ref == null || ref.pathLength() == 0)
        {
            return null;
        }
        return switch (ref.kind())
        {
            case 0 -> // Element
            {
                String p = ref.path(0);
                Object r = resolver.getElement(p);
                if (r == null) System.err.println("[PTR-FAIL] Element kind=0 path=" + p);
                yield r;
            }
            case 1 -> // Property
                    ref.pathLength() > 1 ? resolveProperty(ref.path(0), ref.path(1), resolver) : null;
            case 2 -> // QualifiedProperty
                    ref.pathLength() > 1 ? resolveQualifiedProperty(ref.path(0), ref.path(1), resolver) : null;
            case 3 -> // Stereotype
                    ref.pathLength() > 1 ? resolveStereotype(ref.path(0), ref.path(1), resolver) : null;
            case 4 -> // Tag
                    ref.pathLength() > 1 ? resolveTag(ref.path(0), ref.path(1), resolver) : null;
            default -> null;
        };
    }

    private static Object resolveProperty(String ownerPath, String propName, TruffleMetadataAccess resolver)
    {
        Object owner = resolver.getElement(ownerPath);
        if (owner != null)
        {
            Object result = searchPropertyList(owner, "properties", propName);
            if (result == null)
            {
                result = searchPropertyList(owner, "propertiesFromAssociations", propName);
            }
            return result;
        }
        return null;
    }

    private static Object resolveQualifiedProperty(String ownerPath, String qpName, TruffleMetadataAccess resolver)
    {
        Object owner = resolver.getElement(ownerPath);
        if (owner != null)
        {
            return searchPropertyList(owner, "qualifiedProperties", qpName);
        }
        return null;
    }

    private static Object resolveStereotype(String profilePath, String stereoName, TruffleMetadataAccess resolver)
    {
        Object owner = resolver.getElement(profilePath);
        if (owner != null)
        {
            return searchListByValue(owner, "p_stereotypes", stereoName);
        }
        return null;
    }

    private static Object resolveTag(String profilePath, String tagName, TruffleMetadataAccess resolver)
    {
        Object owner = resolver.getElement(profilePath);
        if (owner != null)
        {
            return searchListByValue(owner, "p_tags", tagName);
        }
        return null;
    }

    /** Search a list property for an element whose _value() matches the target. */
    private static Object searchListByValue(Object owner, String listName, String targetValue)
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
                    java.lang.reflect.Method valueGetter = item.getClass().getMethod("_value");
                    String value = (String) valueGetter.invoke(item);
                    if (targetValue.equals(value))
                    {
                        return item;
                    }
                }
            }
        }
        catch (Exception ignored) {}
        return null;
    }

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

        Object owner = resolver.getElement(ownerPath);
        if (owner == null)
        {
            return null;
        }


        // Search by full mangled signature first (e.g. "res_String_1_"),
        // then fall back to simple name (e.g. "res") for unambiguous matches.
        Object result = searchPropertyList(owner, "qualifiedProperties", memberName);
        if (result != null) return result;
        result = searchPropertyList(owner, "properties", memberName);
        if (result != null) return result;
        result = searchPropertyList(owner, "propertiesFromAssociations", memberName);
        return result;
    }

    private static Object searchPropertyList(Object owner, String listName, String memberName)
    {
        try
        {
            java.lang.reflect.Method getter = owner.getClass().getMethod("_" + listName);
            Object list = getter.invoke(owner);
            if (list instanceof PureSequence seq)
            {
                // Extract simple name from the mangled memberName
                // e.g. "res_String_1_" → "res", "fullName_Boolean_1_" → "fullName"
                String simpleName = memberName;
                int underIdx = memberName.indexOf('_');
                if (underIdx > 0)
                {
                    simpleName = memberName.substring(0, underIdx);
                }

                java.util.List<Object> candidates = new java.util.ArrayList<>();
                for (int i = 0; i < seq.size(); i++)
                {
                    Object item = seq.getBoxed(i);
                    java.lang.reflect.Method nameGetter = item.getClass().getMethod("_name");
                    String name = (String) nameGetter.invoke(item);
                    if (memberName.equals(name))
                    {
                        return item; // exact match on simple name
                    }
                    if (simpleName.equals(name))
                    {
                        candidates.add(item);
                    }
                }
                if (candidates.size() == 1)
                {
                    return candidates.get(0);
                }
                if (candidates.size() > 1)
                {
                    // Disambiguate overloaded QPs by matching parameter count
                    // from the mangled signature. Count params by splitting at "__"
                    // (double underscore separates params from return type).
                    int expectedParamCount = countParamsFromSignature(memberName, simpleName);
                    for (Object candidate : candidates)
                    {
                        try
                        {
                            java.lang.reflect.Method paramsGetter = candidate.getClass().getMethod("_parameters");
                            Object params = paramsGetter.invoke(candidate);
                            if (params instanceof PureSequence ps)
                            {
                                // QP params include 'this', but the signature doesn't
                                int declaredParams = ps.size() - 1;
                                if (declaredParams == expectedParamCount)
                                {
                                    return candidate;
                                }
                            }
                        }
                        catch (Exception ignored2) {}
                    }
                    // If no param-count match, return first candidate
                    return candidates.get(0);
                }
            }
        }
        catch (Exception ignored)
        {
        }
        return null;
    }

    /**
     * Count the number of parameters encoded in a mangled function signature.
     * Format: "name_Type1_mult1__Type2_mult2_..._ReturnType_returnMult_"
     * Double underscore "__" separates params from return type.
     * No-arg function: "name__ReturnType_mult_" → 0 params.
     */
    private static int countParamsFromSignature(String signature, String simpleName)
    {
        // Remove the function name prefix
        String suffix = signature.substring(simpleName.length());
        // Split at "__" (double underscore)
        int dblUnder = suffix.indexOf("__");
        if (dblUnder < 0) return 0;
        String paramPart = suffix.substring(0, dblUnder);
        if (paramPart.isEmpty() || paramPart.equals("_")) return 0;
        // Each param is "_Type_mult" — count pairs separated by "_"
        // Remove leading underscore
        if (paramPart.startsWith("_")) paramPart = paramPart.substring(1);
        if (paramPart.isEmpty()) return 0;
        // Count param groups: each is "Type_mult"
        String[] parts = paramPart.split("_");
        return parts.length / 2; // type + multiplicity = 2 parts per param
    }
}
