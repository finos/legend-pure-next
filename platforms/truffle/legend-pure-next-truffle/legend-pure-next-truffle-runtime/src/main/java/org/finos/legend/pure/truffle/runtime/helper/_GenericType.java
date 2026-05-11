package org.finos.legend.pure.truffle.runtime.helper;

import org.finos.legend.pure.truffle.runtime.TruffleMetadataAccess;
import org.finos.legend.pure.truffle.runtime.dynobj.PureObj;
import org.finos.legend.pure.truffle.types.PureSequence;

public final class _GenericType
{
    private _GenericType() {}

    private static final String GENERIC_TYPE_VALUE_PATH = "meta::pure::metamodel::type::generics::GenericTypeValue";
    private static final String FUNCTION_TYPE_PATH = "meta::pure::metamodel::type::FunctionType";
    private static final String VARIABLE_EXPRESSION_PATH =
            "meta::pure::metamodel::valuespecification::VariableExpression";

    public static Object type(Object gt)
    {
        // Only GenericTypeValue subtypes carry a _type slot; the GenericType
        // base interface doesn't. We can't subtype-check against
        // GenericTypeValue without a resolver here, but readers tolerate a
        // missing slot — PureObj.read returns null when the property is
        // absent. Treat null-Object Result as "no type".
        return PureObj.read(gt, "type");
    }

    public static PureSequence typeArguments(Object gt)
    {
        Object ta = PureObj.read(gt, "typeArguments");
        return ta instanceof PureSequence seq ? seq : null;
    }

    public static Object buildUserDefinedGenericType(Object type, TruffleMetadataAccess resolver)
    {
        Object gt = org.finos.legend.pure.truffle.runtime.TruffleInstanceFactory.createInstance(
                "meta::pure::metamodel::type::generics::UserDefinedGenericType", resolver);
        if (type != null)
        {
            PureObj.write(gt, "type", type);
        }
        // Anchor at the canonical GenericType_UserDefinedGenericType (UDPGT)
        // element from core.pdb, mirroring bootstrap's _GenericType.buildUserDefinedGenericType.
        // Without canonical anchoring the classifier chain bottoms at a fresh
        // self-loop UDGT instead of the canonical UDPGT anchor — divergent
        // from Java's compiler.pdb encoding.
        if (resolver != null)
        {
            Object canonical = resolver.getElement("meta::pure::metamodel::type::generics::optimization::GenericType_UserDefinedGenericType");
            if (canonical != null)
            {
                PureObj.write(gt, "classifierGenericType", canonical);
            }
        }
        return gt;
    }

    /**
     * Human-readable representation of a GenericType for debugging.
     * E.g. {@code Class<MyClass>}, {@code String}, {@code {String[1]->Boolean[1]}}.
     */
    public static String print(Object gt)
    {
        return print(gt, null);
    }

    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    public static String print(Object gt, TruffleMetadataAccess resolver)
    {
        if (gt == null)
        {
            return "null";
        }
        boolean isGtv = PureObj.isType(gt, GENERIC_TYPE_VALUE_PATH, resolver);
        if (!isGtv)
        {
            // Bare Pure type rather than a GenericType wrapper — print as
            // [Type:path]. _PackageableElement.path is widened to Object
            // and falls through gracefully if `gt` doesn't carry a name.
            String path = _PackageableElement.path(gt, resolver);
            if (path != null && !path.isEmpty())
            {
                return "[Type:" + path + "]";
            }
            Object n = PureObj.read(gt, "name");
            if (n instanceof String s && !s.isEmpty())
            {
                return "[Type:" + s + "]";
            }
            return gt.getClass().getName();
        }

        StringBuilder sb = new StringBuilder();

        // Raw type name
        Object rawType = type(gt);
        if (rawType != null && PureObj.pureTypeIs(rawType, FUNCTION_TYPE_PATH))
        {
            sb.append("{");
            Object paramsObj = PureObj.read(rawType, "parameters");
            if (paramsObj instanceof PureSequence params)
            {
                for (int i = 0; i < params.size(); i++)
                {
                    if (i > 0) sb.append(", ");
                    Object p = params.getBoxed(i);
                    if (PureObj.pureTypeIs(p, VARIABLE_EXPRESSION_PATH))
                    {
                        sb.append(print(PureObj.read(p, "genericType"), resolver));
                    }
                    else
                    {
                        sb.append("?");
                    }
                }
            }
            sb.append("->");
            sb.append(print(PureObj.read(rawType, "returnType"), resolver));
            sb.append("}");
        }
        else if (rawType != null)
        {
            // Default: any other Type (Class, Enumeration, PrimitiveType, …)
            // is a PackageableElement at the Pure level — render via path.
            String path = _PackageableElement.path(rawType, resolver);
            if (path != null && !path.isEmpty())
            {
                sb.append(path);
            }
            else
            {
                Object n = PureObj.read(rawType, "name");
                sb.append(n instanceof String s && !s.isEmpty() ? s : "?");
            }
        }
        else
        {
            sb.append("?");
        }

        // Type arguments
        PureSequence args = typeArguments(gt);
        if (args != null && args.size() > 0)
        {
            sb.append("<");
            for (int i = 0; i < args.size(); i++)
            {
                if (i > 0) sb.append(", ");
                sb.append(print(args.getBoxed(i), resolver));
            }
            sb.append(">");
        }

        return sb.toString();
    }
}
