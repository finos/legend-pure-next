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
        return gt;
    }
}
