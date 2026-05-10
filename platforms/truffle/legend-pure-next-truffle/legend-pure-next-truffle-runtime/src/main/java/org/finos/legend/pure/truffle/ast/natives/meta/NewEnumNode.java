package org.finos.legend.pure.truffle.ast.natives.meta;

import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.NodeInfo;
import org.finos.legend.pure.truffle.ast.PureNode;
import org.finos.legend.pure.truffle.ast.natives.collection.CollectionHelper;
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

    public NewEnumNode(PureNode nameArg, PureNode packageArg, PureNode valueNamesArg)
    {
        this.nameArg = nameArg;
        this.packageArg = packageArg;
        this.valueNamesArg = valueNamesArg;
    }

    @Override
    public Object executeGeneric(VirtualFrame frame)
    {
        Object name = nameArg.executeGeneric(frame);
        Object pkg = packageArg.executeGeneric(frame);
        Object valueNames = valueNamesArg.executeGeneric(frame);
        return doNewEnumeration(name, pkg, valueNames, getResolver());
    }

    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    private static Object doNewEnumeration(Object name, Object pkg, Object valueNames, TruffleMetadataAccess resolver)
    {
        var enumeration = new org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.type.EnumerationImpl();
        org.finos.legend.pure.truffle.runtime.dynobj.PureObj.write(enumeration, "name",
                name instanceof String s ? s : String.valueOf(name));
        if (pkg != null)
        {
            org.finos.legend.pure.truffle.runtime.dynobj.PureObj.write(enumeration, "package", pkg);
        }

        // Add generalization: every user-defined enumeration extends Enum
        Object enumTypeObj = resolver.getElement("meta::pure::metamodel::type::Enum");
        if (enumTypeObj instanceof org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.type.Type enumT)
        {
            var gen = new org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.relationship.GeneralizationImpl();
            org.finos.legend.pure.truffle.runtime.dynobj.PureObj.write(gen, "general",
                    _GenericType.buildUserDefinedGenericType(enumT, resolver));
            org.finos.legend.pure.truffle.runtime.dynobj.PureObj.write(gen, "specific", enumeration);
            Object genTypeObj = resolver.getElement("meta::pure::metamodel::relationship::Generalization");
            if (genTypeObj instanceof org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.type.Type genT)
            {
                org.finos.legend.pure.truffle.runtime.dynobj.PureObj.write(gen, "classifierGenericType",
                        _GenericType.buildUserDefinedGenericType(genT, resolver));
            }
            org.finos.legend.pure.truffle.runtime.dynobj.PureObj.write(enumeration, "generalizations",
                    new ObjectSequence(new Object[]{gen}));
        }

        // Self-referencing CGT: Enumeration<self>
        var selfRef = _GenericType.buildUserDefinedGenericType(enumeration, resolver);
        Object enumerationTypeObj = resolver.getElement("meta::pure::metamodel::type::Enumeration");
        var cgt = _GenericType.buildUserDefinedGenericType(
                enumerationTypeObj instanceof org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.type.Type t ? t : null, resolver);
        org.finos.legend.pure.truffle.runtime.dynobj.PureObj.write(cgt, "typeArguments",
                new ObjectSequence(new Object[]{selfRef}));
        org.finos.legend.pure.truffle.runtime.dynobj.PureObj.write(enumeration, "classifierGenericType", cgt);

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
            org.finos.legend.pure.truffle.runtime.dynobj.PureObj.write(enumInstance, "name", valueName);
            org.finos.legend.pure.truffle.runtime.dynobj.PureObj.write(enumInstance, "classifierGenericType", enumGT);

            // Build FunctionType for the lambda: {-> EnumType[1]}
            var ft = new org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.type.FunctionTypeImpl();
            if (functionTypeTypeObj instanceof org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.type.Type ftT)
            {
                org.finos.legend.pure.truffle.runtime.dynobj.PureObj.write(ft, "classifierGenericType",
                        _GenericType.buildUserDefinedGenericType(ftT, resolver));
            }
            org.finos.legend.pure.truffle.runtime.dynobj.PureObj.write(ft, "returnType", enumGT);
            org.finos.legend.pure.truffle.runtime.dynobj.PureObj.write(ft, "returnMultiplicity", pureOne);

            // Lambda CGT: LambdaFunction<{-> EnumType[1]}>
            Object igtType = resolver.getElement("meta::pure::metamodel::type::generics::InferredGenericType");
            var ftGT = new org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.type.generics.InferredGenericTypeImpl();
            if (igtType instanceof org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.type.Type igtT)
            {
                org.finos.legend.pure.truffle.runtime.dynobj.PureObj.write(ftGT, "classifierGenericType",
                        _GenericType.buildUserDefinedGenericType(igtT, resolver));
            }
            org.finos.legend.pure.truffle.runtime.dynobj.PureObj.write(ftGT, "type", ft);
            var lambdaCGT = new org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.type.generics.InferredGenericTypeImpl();
            if (igtType instanceof org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.type.Type igtT2)
            {
                org.finos.legend.pure.truffle.runtime.dynobj.PureObj.write(lambdaCGT, "classifierGenericType",
                        _GenericType.buildUserDefinedGenericType(igtT2, resolver));
            }
            org.finos.legend.pure.truffle.runtime.dynobj.PureObj.write(lambdaCGT, "type", lambdaFunctionTypeObj);
            org.finos.legend.pure.truffle.runtime.dynobj.PureObj.write(lambdaCGT, "typeArguments",
                    new ObjectSequence(new Object[]{ftGT}));

            // Lambda wrapping the AtomicValue
            var atomicValue = new org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.valuespecification.AtomicValueImpl();
            Object avType = resolver.getElement("meta::pure::metamodel::valuespecification::AtomicValue");
            if (avType instanceof org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.type.Type avT)
            {
                org.finos.legend.pure.truffle.runtime.dynobj.PureObj.write(atomicValue, "classifierGenericType",
                        _GenericType.buildUserDefinedGenericType(avT, resolver));
            }
            org.finos.legend.pure.truffle.runtime.dynobj.PureObj.write(atomicValue, "value", enumInstance);
            org.finos.legend.pure.truffle.runtime.dynobj.PureObj.write(atomicValue, "genericType", enumGT);
            org.finos.legend.pure.truffle.runtime.dynobj.PureObj.write(atomicValue, "multiplicity", pureOne);

            var lambda = new org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.function.LambdaFunctionImpl();
            org.finos.legend.pure.truffle.runtime.dynobj.PureObj.write(lambda, "classifierGenericType", lambdaCGT);
            org.finos.legend.pure.truffle.runtime.dynobj.PureObj.write(lambda, "expressionSequence",
                    new ObjectSequence(new Object[]{atomicValue}));

            // Property CGT: Property<Enumeration<self>, EnumType|1>
            var propCGT = _GenericType.buildUserDefinedGenericType(
                    propertyTypeObj instanceof org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.type.Type pt ? pt : null, resolver);
            org.finos.legend.pure.truffle.runtime.dynobj.PureObj.write(propCGT, "typeArguments",
                    new ObjectSequence(new Object[]{cgt, enumGT}));
            org.finos.legend.pure.truffle.runtime.dynobj.PureObj.write(propCGT, "multiplicityArguments",
                    new ObjectSequence(new Object[]{pureOne}));

            var prop = new org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.function.property.PropertyImpl();
            org.finos.legend.pure.truffle.runtime.dynobj.PureObj.write(prop, "name", valueName);
            org.finos.legend.pure.truffle.runtime.dynobj.PureObj.write(prop, "classifierGenericType", propCGT);
            org.finos.legend.pure.truffle.runtime.dynobj.PureObj.write(prop, "genericType", enumGT);
            org.finos.legend.pure.truffle.runtime.dynobj.PureObj.write(prop, "multiplicity", pureOne);
            org.finos.legend.pure.truffle.runtime.dynobj.PureObj.write(prop, "owner", enumeration);
            org.finos.legend.pure.truffle.runtime.dynobj.PureObj.write(prop, "aggregation",
                    org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.function.property.AggregationKindEnum.None);
            org.finos.legend.pure.truffle.runtime.dynobj.PureObj.write(prop, "defaultValue", lambda);

            properties[i] = prop;
        }

        org.finos.legend.pure.truffle.runtime.dynobj.PureObj.write(enumeration, "properties", new ObjectSequence(properties));
        return enumeration;
    }
}
