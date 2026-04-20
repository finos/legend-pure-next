package org.finos.legend.pure.truffle.ast.natives.meta;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.NodeInfo;
import org.finos.legend.pure.truffle.ast.PureNode;
import org.finos.legend.pure.truffle.types.ObjectSequence;
import org.finos.legend.pure.truffle.types.PureSequence;

/**
 * {@code newClass(TypeParameter[*], MultiplicityParameter[*]) : Class<Any>[1]}
 * Creates a new Class at runtime with a self-referencing classifierGenericType.
 */
@NodeInfo(shortName = "newClass")
public final class NewClassNode extends PureNode
{
    @Child
    private PureNode typeParamsArg;

    @Child
    private PureNode multParamsArg;

    public NewClassNode(PureNode typeParamsArg, PureNode multParamsArg)
    {
        this.typeParamsArg = typeParamsArg;
        this.multParamsArg = multParamsArg;
    }

    @Override
    public Object executeGeneric(VirtualFrame frame)
    {
        Object typeParams = typeParamsArg.executeGeneric(frame);
        Object multParams = multParamsArg.executeGeneric(frame);
        return doNewClass(typeParams, multParams);
    }

    @TruffleBoundary
    private static Object doNewClass(Object typeParams, Object multParams)
    {
        var cls = new org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.type.ClassImpl();

        // Set type parameters and multiplicity parameters
        if (typeParams instanceof PureSequence ps)
        {
            cls._typeParameters(ps);
        }
        if (multParams instanceof PureSequence ps)
        {
            cls._multiplicityParameters(ps);
        }

        // Build self-referencing CGT: Class<cls>
        // The CGT is a GenericTypeValue whose type is Class and whose
        // typeArguments[0] is a GenericType pointing back to cls.
        var selfGT = new org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.type.generics.UserDefinedGenericTypeImpl();
        selfGT._type(cls);

        var resolver = org.finos.legend.pure.truffle.runtime.StandaloneEvaluatorHolder.current().resolver();
        Object classType = resolver.getElement("meta::pure::metamodel::type::Class");
        var cgt = new org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.type.generics.UserDefinedGenericTypeImpl();
        if (classType instanceof org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.type.Type t)
        {
            cgt._type(t);
        }
        cgt._typeArguments(new ObjectSequence(new Object[]{selfGT}));

        cls._classifierGenericType(cgt);

        return cls;
    }
}
