package org.finos.legend.pure.truffle.runtime.helper;

import org.finos.legend.pure.truffle.runtime.TruffleMetadataAccess;
import org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.type.Type;
import org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.type.generics.GenericType;
import org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.type.generics.GenericTypeValue;
import org.finos.legend.pure.truffle.types.PureSequence;

public final class _GenericType
{
    private _GenericType() {}

    public static Type type(Object gt)
    {
        if (gt instanceof GenericTypeValue gtv)
        {
            Object t = gtv._type();
            return t instanceof Type type ? type : null;
        }
        if (gt instanceof GenericType g)
        {
            // GenericType base interface has no _type() — only GenericTypeValue does.
            // If it's not a GenericTypeValue, we can't extract the type.
            return null;
        }
        return null;
    }

    public static PureSequence typeArguments(Object gt)
    {
        if (gt instanceof GenericTypeValue gtv)
        {
            Object ta = gtv._typeArguments();
            return ta instanceof PureSequence seq ? seq : null;
        }
        return null;
    }

    public static org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.type.generics.UserDefinedGenericTypeImpl
    buildUserDefinedGenericType(Type type, TruffleMetadataAccess resolver)
    {
        var gt = new org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.type.generics.UserDefinedGenericTypeImpl();
        gt._type(type);
        // Anchor at the canonical GenericType_UserDefinedGenericType (UDPGT)
        // element from core.pdb, mirroring bootstrap's _GenericType.buildUserDefinedGenericType.
        // Without canonical anchoring the classifier chain bottoms at a fresh
        // self-loop UDGT instead of the canonical UDPGT anchor — divergent
        // from Java's compiler.pdb encoding.
        if (resolver != null)
        {
            Object canonical = resolver.getElement("meta::pure::metamodel::type::generics::optimization::GenericType_UserDefinedGenericType");
            if (canonical instanceof org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.type.generics.GenericTypeValue canonicalGT)
            {
                gt._classifierGenericType(canonicalGT);
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
        if (gt instanceof org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.PackageableElement pe
                && !(gt instanceof GenericTypeValue))
        {
            String path = _PackageableElement.path(pe, resolver);
            return "[Type:" + (path != null ? path : (pe._name() != null ? pe._name() : gt.getClass().getName())) + "]";
        }
        if (!(gt instanceof GenericTypeValue gtv))
        {
            return gt.getClass().getName();
        }

        StringBuilder sb = new StringBuilder();

        // Raw type name
        Type rawType = type(gt);
        if (rawType instanceof org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.PackageableElement pe)
        {
            String path = _PackageableElement.path(pe, resolver);
            sb.append(path != null ? path : (pe._name() != null ? pe._name() : "?"));
        }
        else if (rawType instanceof org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.type.FunctionType ft)
        {
            sb.append("{");
            PureSequence params = ft._parameters();
            if (params != null)
            {
                for (int i = 0; i < params.size(); i++)
                {
                    if (i > 0) sb.append(", ");
                    Object p = params.getBoxed(i);
                    if (p instanceof org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.valuespecification.VariableExpression ve)
                    {
                        sb.append(print(ve._genericType(), resolver));
                    }
                    else
                    {
                        sb.append("?");
                    }
                }
            }
            sb.append("->");
            sb.append(print(ft._returnType(), resolver));
            sb.append("}");
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
