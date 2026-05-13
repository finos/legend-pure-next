package org.finos.legend.pure.truffle.runtime.helper;

import org.finos.legend.pure.truffle.runtime.TruffleMetadataAccess;
import org.finos.legend.pure.truffle.runtime.dynobj.PureClassRegistry;
import org.finos.legend.pure.truffle.runtime.dynobj.PureDynamicObject;
import org.finos.legend.pure.truffle.runtime.dynobj.PureObj;
import org.finos.legend.pure.truffle.types.PureSequence;

public final class _GenericType
{

    private static final int SLOT_GENERIC_TYPE = org.finos.legend.pure.truffle.runtime.dynobj.PureClassRegistry.globalSlot("genericType");
    private static final int SLOT_NAME = org.finos.legend.pure.truffle.runtime.dynobj.PureClassRegistry.globalSlot("name");
    private static final int SLOT_PARAMETERS = org.finos.legend.pure.truffle.runtime.dynobj.PureClassRegistry.globalSlot("parameters");
    private static final int SLOT_RETURN_TYPE = org.finos.legend.pure.truffle.runtime.dynobj.PureClassRegistry.globalSlot("returnType");
    private _GenericType() {}

    private static final String GENERIC_TYPE_VALUE_PATH = "meta::pure::metamodel::type::generics::GenericTypeValue";
    private static final String FUNCTION_TYPE_PATH = "meta::pure::metamodel::type::FunctionType";
    private static final String VARIABLE_EXPRESSION_PATH =
            "meta::pure::metamodel::valuespecification::VariableExpression";

    // Pre-resolved global slot indices for the properties this helper reads
    // hot on the type-inference path. Lets the PDO fast path bypass the
    // per-class slotByName HashMap.get and read directly via slot index.
    private static final int SLOT_TYPE = PureClassRegistry.globalSlot("type");
    private static final int SLOT_TYPE_ARGUMENTS = PureClassRegistry.globalSlot("typeArguments");

    public static Object type(Object gt)
    {
        if (gt instanceof PureDynamicObject pdo)
        {
            return pdo.readSlot(SLOT_TYPE);
        }
        return PureObj.readBySlot(gt, SLOT_TYPE);
    }

    public static PureSequence typeArguments(Object gt)
    {
        Object ta = gt instanceof PureDynamicObject pdo
                ? pdo.readSlot(SLOT_TYPE_ARGUMENTS)
                : PureObj.readBySlot(gt, SLOT_TYPE_ARGUMENTS);
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
            Object n = PureObj.readBySlot(gt, SLOT_NAME);
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
            Object paramsObj = PureObj.readBySlot(rawType, SLOT_PARAMETERS);
            if (paramsObj instanceof PureSequence params)
            {
                for (int i = 0; i < params.size(); i++)
                {
                    if (i > 0) sb.append(", ");
                    Object p = params.getBoxed(i);
                    if (PureObj.pureTypeIs(p, VARIABLE_EXPRESSION_PATH))
                    {
                        sb.append(print(PureObj.readBySlot(p, SLOT_GENERIC_TYPE), resolver));
                    }
                    else
                    {
                        sb.append("?");
                    }
                }
            }
            sb.append("->");
            sb.append(print(PureObj.readBySlot(rawType, SLOT_RETURN_TYPE), resolver));
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
                Object n = PureObj.readBySlot(rawType, SLOT_NAME);
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
