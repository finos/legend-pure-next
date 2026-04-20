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

        // Normalize to PureSequence (single values get wrapped)
        PureSequence tpSeq = toSequence(typeParams);
        PureSequence mpSeq = toSequence(multParams);

        // Set type parameters and multiplicity parameters
        cls._typeParameters(tpSeq);
        cls._multiplicityParameters(mpSeq);

        // Build self-referencing CGT: Class<cls>
        var selfGT = new org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.type.generics.UserDefinedGenericTypeImpl();
        selfGT._type(cls);
        // Add type parameter GTs as typeArguments on the self-GT
        if (!tpSeq.isEmpty())
        {
            Object[] tpGTs = new Object[tpSeq.size()];
            for (int i = 0; i < tpSeq.size(); i++)
            {
                Object tp = tpSeq.getBoxed(i);
                if (tp instanceof org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.type.generics.TypeParameter tParam)
                {
                    // Set owner to the new class
                    if (tParam instanceof org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.type.generics.TypeParameterImpl tpImpl)
                    {
                        tpImpl._owner(cls);
                    }
                    var tpGT = new org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.type.generics.UserDefinedGenericTypeImpl();
                    tpGT._type(tParam);
                    tpGTs[i] = tpGT;
                }
                else
                {
                    tpGTs[i] = tp;
                }
            }
            selfGT._typeArguments(new ObjectSequence(tpGTs));
        }

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

    private static PureSequence toSequence(Object value)
    {
        if (value instanceof PureSequence ps) return ps;
        if (value == null) return PureSequence.EMPTY;
        return new ObjectSequence(new Object[]{value});
    }
}
