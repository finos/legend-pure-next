package org.finos.legend.pure.truffle.ast.natives.meta;

import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.NodeInfo;
import org.finos.legend.pure.truffle.ast.PureNode;
import org.finos.legend.pure.truffle.StandaloneEvaluator;
import org.finos.legend.pure.truffle.runtime.TruffleMetadataAccess;
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

    private final TruffleMetadataAccess resolver;

    public NewClassNode(PureNode typeParamsArg, PureNode multParamsArg)
    {
        this.typeParamsArg = typeParamsArg;
        this.multParamsArg = multParamsArg;
        this.resolver = StandaloneEvaluator.INSTANCE.resolver();
    }

    @Override
    public Object executeGeneric(VirtualFrame frame)
    {
        Object typeParams = typeParamsArg.executeGeneric(frame);
        Object multParams = multParamsArg.executeGeneric(frame);
        return doNewClass(typeParams, multParams, resolver);
    }

    private static Object doNewClass(Object typeParams, Object multParams, TruffleMetadataAccess resolver)
    {
        var cls = new org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.type.ClassImpl();

        // Normalize to PureSequence (single values get wrapped)
        PureSequence tpSeq = toSequence(typeParams);
        PureSequence mpSeq = toSequence(multParams);

        // Set type parameters and multiplicity parameters
        cls._typeParameters(tpSeq);
        cls._multiplicityParameters(mpSeq);

        // Build self-referencing CGT: Class<cls>
        var selfGT = org.finos.legend.pure.truffle.runtime.helper._GenericType.buildUserDefinedGenericType(cls, resolver);
        // Add type parameter GTs as typeArguments on the self-GT
        if (!tpSeq.isEmpty())
        {
            Object[] tpGTs = new Object[tpSeq.size()];
            for (int i = 0; i < tpSeq.size(); i++)
            {
                Object tp = tpSeq.getBoxed(i);
                if (tp instanceof org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.type.generics.TypeParameter tParam)
                {
                    tParam._owner(cls);
                    var tpGT = org.finos.legend.pure.truffle.runtime.helper._GenericType.buildUserDefinedGenericType(tParam, resolver);
                    tpGTs[i] = tpGT;
                }
                else
                {
                    tpGTs[i] = tp;
                }
            }
            selfGT._typeArguments(new ObjectSequence(tpGTs));
        }

        // Add multiplicity parameter as multiplicityArguments on the self-GT
        if (!mpSeq.isEmpty())
        {
            Object[] mpArgs = new Object[mpSeq.size()];
            for (int i = 0; i < mpSeq.size(); i++)
            {
                Object mp = mpSeq.getBoxed(i);
                if (mp instanceof org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.multiplicity.MultiplicityParameter mpParam)
                {
                    mpParam._owner(cls);
                }
                mpArgs[i] = mp;
            }
            selfGT._multiplicityArguments(new ObjectSequence(mpArgs));
        }

        Object classType = resolver.getElement("meta::pure::metamodel::type::Class");
        var cgt = org.finos.legend.pure.truffle.runtime.helper._GenericType.buildUserDefinedGenericType(
                classType instanceof org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.type.Type t ? t : null, resolver);
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
