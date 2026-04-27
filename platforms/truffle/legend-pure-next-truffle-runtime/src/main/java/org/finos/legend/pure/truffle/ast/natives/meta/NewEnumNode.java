package org.finos.legend.pure.truffle.ast.natives.meta;

import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.NodeInfo;
import org.finos.legend.pure.truffle.ast.PureNode;
import org.finos.legend.pure.truffle.ast.natives.collection.CollectionHelper;
import org.finos.legend.pure.truffle.StandaloneEvaluator;
import org.finos.legend.pure.truffle.runtime.TruffleMetadataAccess;
import org.finos.legend.pure.truffle.runtime.helper._GenericType;
import org.finos.legend.pure.truffle.types.ObjectSequence;
import org.finos.legend.pure.truffle.types.PureSequence;

/**
 * {@code newEnumeration(String[*]) : Enumeration<Any>[1]}
 * Creates a fully constructed Enumeration with all enum values.
 */
@NodeInfo(shortName = "newEnumeration")
public final class NewEnumNode extends PureNode
{
    @Child
    private PureNode nameArg;

    @Child
    private PureNode packageArg;

    @Child
    private PureNode valueNamesArg;

    private final TruffleMetadataAccess resolver;

    public NewEnumNode(PureNode nameArg, PureNode packageArg, PureNode valueNamesArg)
    {
        this.nameArg = nameArg;
        this.packageArg = packageArg;
        this.valueNamesArg = valueNamesArg;
        this.resolver = StandaloneEvaluator.INSTANCE.resolver();
    }

    @Override
    public Object executeGeneric(VirtualFrame frame)
    {
        Object name = nameArg.executeGeneric(frame);
        Object pkg = packageArg.executeGeneric(frame);
        Object valueNames = valueNamesArg.executeGeneric(frame);
        return doNewEnumeration(name, pkg, valueNames, resolver);
    }

    private static Object doNewEnumeration(Object name, Object pkg, Object valueNames, TruffleMetadataAccess resolver)
    {
        var enumeration = new org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.type.EnumerationImpl();
        enumeration._name(name instanceof String s ? s : String.valueOf(name));
        if (pkg instanceof org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.Package p)
        {
            enumeration._package(p);
        }

        // Add generalization: every user-defined enumeration extends Enum
        Object enumTypeObj = resolver.getElement("meta::pure::metamodel::type::Enum");
        if (enumTypeObj instanceof org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.type.Type enumT)
        {
            var gen = new org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.relationship.GeneralizationImpl();
            gen._general(_GenericType.buildUserDefinedGenericType(enumT, resolver));
            gen._specific(enumeration);
            Object genTypeObj = resolver.getElement("meta::pure::metamodel::relationship::Generalization");
            if (genTypeObj instanceof org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.type.Type genT)
            {
                gen._classifierGenericType(_GenericType.buildUserDefinedGenericType(genT, resolver));
            }
            enumeration._generalizations(new ObjectSequence(new Object[]{gen}));
        }

        // Self-referencing CGT: Enumeration<self>
        var selfRef = _GenericType.buildUserDefinedGenericType(enumeration, resolver);
        Object enumerationTypeObj = resolver.getElement("meta::pure::metamodel::type::Enumeration");
        var cgt = _GenericType.buildUserDefinedGenericType(
                enumerationTypeObj instanceof org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.type.Type t ? t : null, resolver);
        cgt._typeArguments(new ObjectSequence(new Object[]{selfRef}));
        enumeration._classifierGenericType(cgt);

        // GenericType for this enumeration (CGT for enum values)
        var enumGT = _GenericType.buildUserDefinedGenericType(enumeration, resolver);

        Object pureOne = resolver.getElement("meta::pure::metamodel::multiplicity::PureOne");
        Object propertyTypeObj = resolver.getElement("meta::pure::metamodel::function::property::Property");
        Object lambdaFunctionTypeObj = resolver.getElement("meta::pure::metamodel::function::LambdaFunction");
        Object functionTypeTypeObj = resolver.getElement("meta::pure::metamodel::type::FunctionType");

        // Build properties for each value
        int count = CollectionHelper.size(valueNames);
        Object[] properties = new Object[count];
        for (int i = 0; i < count; i++)
        {
            String valueName = String.valueOf(CollectionHelper.at(valueNames, i));

            // Create Enum instance with CGT = enumGT
            var enumInstance = new org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.type.EnumImpl();
            enumInstance._name(valueName);
            enumInstance._classifierGenericType(enumGT);

            // Build FunctionType for the lambda: {-> EnumType[1]}
            var ft = new org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.type.FunctionTypeImpl();
            if (functionTypeTypeObj instanceof org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.type.Type ftT)
            {
                ft._classifierGenericType(_GenericType.buildUserDefinedGenericType(ftT, resolver));
            }
            ft._returnType(enumGT);
            ft._returnMultiplicity((org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.multiplicity.Multiplicity) pureOne);

            // Lambda CGT: LambdaFunction<{-> EnumType[1]}>
            Object igtType = resolver.getElement("meta::pure::metamodel::type::generics::InferredGenericType");
            var ftGT = new org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.type.generics.InferredGenericTypeImpl();
            if (igtType instanceof org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.type.Type igtT)
            {
                ftGT._classifierGenericType(_GenericType.buildUserDefinedGenericType(igtT, resolver));
            }
            ftGT._type(ft);
            var lambdaCGT = new org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.type.generics.InferredGenericTypeImpl();
            if (igtType instanceof org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.type.Type igtT2)
            {
                lambdaCGT._classifierGenericType(_GenericType.buildUserDefinedGenericType(igtT2, resolver));
            }
            lambdaCGT._type((org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.type.Type) lambdaFunctionTypeObj);
            lambdaCGT._typeArguments(new ObjectSequence(new Object[]{ftGT}));

            // Lambda wrapping the AtomicValue
            var atomicValue = new org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.valuespecification.AtomicValueImpl();
            Object avType = resolver.getElement("meta::pure::metamodel::valuespecification::AtomicValue");
            if (avType instanceof org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.type.Type avT)
            {
                atomicValue._classifierGenericType(_GenericType.buildUserDefinedGenericType(avT, resolver));
            }
            atomicValue._value(enumInstance);
            atomicValue._genericType(enumGT);
            atomicValue._multiplicity((org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.multiplicity.Multiplicity) pureOne);

            var lambda = new org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.function.LambdaFunctionImpl();
            lambda._classifierGenericType(lambdaCGT);
            lambda._expressionSequence(new ObjectSequence(new Object[]{atomicValue}));

            // Property CGT: Property<Enumeration<self>, EnumType|1>
            var propCGT = _GenericType.buildUserDefinedGenericType(
                    propertyTypeObj instanceof org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.type.Type pt ? pt : null, resolver);
            propCGT._typeArguments(new ObjectSequence(new Object[]{cgt, enumGT}));
            propCGT._multiplicityArguments(new ObjectSequence(new Object[]{pureOne}));

            var prop = new org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.function.property.PropertyImpl();
            prop._name(valueName);
            prop._classifierGenericType(propCGT);
            prop._genericType(enumGT);
            prop._multiplicity((org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.multiplicity.Multiplicity) pureOne);
            prop._owner(enumeration);
            prop._aggregation(org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.function.property.AggregationKindEnum.None);
            prop._defaultValue(lambda);

            properties[i] = prop;
        }

        enumeration._properties(new ObjectSequence(properties));
        return enumeration;
    }
}
