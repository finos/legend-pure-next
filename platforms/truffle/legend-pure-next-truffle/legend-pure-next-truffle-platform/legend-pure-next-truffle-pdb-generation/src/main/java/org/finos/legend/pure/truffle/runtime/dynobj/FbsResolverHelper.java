package org.finos.legend.pure.truffle.runtime.dynobj;

import org.finos.legend.pure.truffle.runtime.PropertyAccessor;
import org.finos.legend.pure.truffle.runtime.TruffleMetadataAccess;
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
                if (r == null && p.contains("."))
                {
                    // Enum value references and nested members come through
                    // here — `meta::pure::functions::date::DurationUnit.HOURS`
                    // isn't stored as a top-level archive element; fall back
                    // to navigating the owner type's properties/values.
                    r = resolveNestedElement(p, resolver);
                }
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

    /** Search a list property for an element whose value property matches the target. */
    private static Object searchListByValue(Object owner, String listName, String targetValue)
    {
        // Use PropertyAccessor + PureDynamicObject access so this works for
        // both PureDynamicObject (post-flip) and legacy XPDBHelper (which
        // implements PropertyAccessor). Reflection-on-typed-getter wouldn't
        // find _p_stereotypes / _value on PureDynamicObject because those
        // typed getters only live on the per-class XPDBHelper.
        Object list = readProperty(owner, listName);
        if (list instanceof PureSequence seq)
        {
            for (int i = 0; i < seq.size(); i++)
            {
                Object item = seq.getBoxed(i);
                Object value = readProperty(item, "value");
                if (targetValue.equals(value))
                {
                    return item;
                }
            }
        }
        return null;
    }

    private static Object readProperty(Object obj, String name)
    {
        if (obj instanceof PureDynamicObject pdo)
        {
            Object v = pdo.readProperty(name);
            return v == PropertyAccessor.ABSENT ? null : v;
        }
        if (obj instanceof PropertyAccessor accessor)
        {
            Object v = accessor.readProperty(name);
            return v == PropertyAccessor.ABSENT ? null : v;
        }
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
        if (result != null) return result;
        // Enumeration values: stored in the `values` list, not `properties`.
        // E.g. `meta::pure::functions::date::DurationUnit.HOURS` resolves to
        // the HOURS enum constant on the DurationUnit Enumeration.
        return searchListByName(owner, "values", memberName);
    }

    /** Search a list property for an element whose `name` matches the target. */
    private static Object searchListByName(Object owner, String listName, String targetName)
    {
        Object list = readProperty(owner, listName);
        if (list instanceof PureSequence seq)
        {
            for (int i = 0; i < seq.size(); i++)
            {
                Object item = seq.getBoxed(i);
                Object name = readProperty(item, "name");
                if (targetName.equals(name))
                {
                    return item;
                }
            }
        }
        return null;
    }

    private static Object searchPropertyList(Object owner, String listName, String memberName)
    {
        // Use readProperty so this works for both PureDynamicObject (post-flip)
        // and legacy XPDBHelper (PropertyAccessor). Reflection-on-typed-getter
        // (`getClass().getMethod("_properties")`) fails on PureDynamicObject
        // because the typed `_X()` getters only live on the per-class XPDBHelper.
        Object list = readProperty(owner, listName);
        if (!(list instanceof PureSequence seq))
        {
            return null;
        }
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
            Object nameObj = readProperty(item, "name");
            if (!(nameObj instanceof String name))
            {
                continue;
            }
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
            // from the mangled signature.
            int expectedParamCount = countParamsFromSignature(memberName, simpleName);
            for (Object candidate : candidates)
            {
                Object params = readProperty(candidate, "parameters");
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
            // If no param-count match, return first candidate
            return candidates.get(0);
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
