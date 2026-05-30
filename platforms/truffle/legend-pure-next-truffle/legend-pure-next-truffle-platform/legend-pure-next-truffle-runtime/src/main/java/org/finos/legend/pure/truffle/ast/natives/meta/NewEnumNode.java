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
        Object enumeration = org.finos.legend.pure.truffle.runtime.TruffleInstanceFactory.createInstance(
                "meta::pure::metamodel::type::Enumeration", resolver);
        org.finos.legend.pure.truffle.runtime.dynobj.PureObj.write(enumeration, "name",
                name instanceof String s ? s : String.valueOf(name));
        if (pkg != null)
        {
            org.finos.legend.pure.truffle.runtime.dynobj.PureObj.write(enumeration, "package", pkg);
        }

        // Add generalization: every user-defined enumeration extends Enum.
        // Resolver elements are PureDynamicObject post-loader-flip; the
        // typed `instanceof Type` guards would skip the CGT-write branches
        // and leave the resulting Enumeration with empty `properties`,
        // tripping the testNewEnumeration assertion.
        Object enumTypeObj = resolver.getElement("meta::pure::metamodel::type::Enum");
        if (enumTypeObj != null)
        {
            Object gen = org.finos.legend.pure.truffle.runtime.TruffleInstanceFactory.createInstance(
                    "meta::pure::metamodel::relationship::Generalization", resolver);
            org.finos.legend.pure.truffle.runtime.dynobj.PureObj.write(gen, "general",
                    _GenericType.buildUserDefinedGenericType(enumTypeObj, resolver));
            org.finos.legend.pure.truffle.runtime.dynobj.PureObj.write(gen, "specific", enumeration);
            Object genTypeObj = resolver.getElement("meta::pure::metamodel::relationship::Generalization");
            if (genTypeObj != null)
            {
                org.finos.legend.pure.truffle.runtime.dynobj.PureObj.write(gen, "classifierGenericType",
                        _GenericType.buildUserDefinedGenericType(genTypeObj, resolver));
            }
            org.finos.legend.pure.truffle.runtime.dynobj.PureObj.write(enumeration, "generalizations",
                    new ObjectSequence(new Object[]{gen}));
        }

        // Self-referencing CGT: Enumeration<self>
        var selfRef = _GenericType.buildUserDefinedGenericType(enumeration, resolver);
        Object enumerationTypeObj = resolver.getElement("meta::pure::metamodel::type::Enumeration");
        var cgt = _GenericType.buildUserDefinedGenericType(enumerationTypeObj, resolver);
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
            Object enumInstance = org.finos.legend.pure.truffle.runtime.TruffleInstanceFactory.createInstance(
                    "meta::pure::metamodel::type::Enum", resolver);
            org.finos.legend.pure.truffle.runtime.dynobj.PureObj.write(enumInstance, "name", valueName);
            org.finos.legend.pure.truffle.runtime.dynobj.PureObj.write(enumInstance, "classifierGenericType", enumGT);

            // Build FunctionType for the lambda: {-> EnumType[1]}
            Object ft = org.finos.legend.pure.truffle.runtime.TruffleInstanceFactory.createInstance(
                    "meta::pure::metamodel::type::FunctionType", resolver);
            if (functionTypeTypeObj != null)
            {
                org.finos.legend.pure.truffle.runtime.dynobj.PureObj.write(ft, "classifierGenericType",
                        _GenericType.buildUserDefinedGenericType(functionTypeTypeObj, resolver));
            }
            org.finos.legend.pure.truffle.runtime.dynobj.PureObj.write(ft, "returnType", enumGT);
            org.finos.legend.pure.truffle.runtime.dynobj.PureObj.write(ft, "returnMultiplicity", pureOne);

            // Lambda CGT: LambdaFunction<{-> EnumType[1]}>
            Object igtType = resolver.getElement("meta::pure::metamodel::type::generics::InferredGenericType");
            Object ftGT = org.finos.legend.pure.truffle.runtime.TruffleInstanceFactory.createInstance(
                    "meta::pure::metamodel::type::generics::InferredGenericType", resolver);
            if (igtType != null)
            {
                org.finos.legend.pure.truffle.runtime.dynobj.PureObj.write(ftGT, "classifierGenericType",
                        _GenericType.buildUserDefinedGenericType(igtType, resolver));
            }
            org.finos.legend.pure.truffle.runtime.dynobj.PureObj.write(ftGT, "type", ft);
            Object lambdaCGT = org.finos.legend.pure.truffle.runtime.TruffleInstanceFactory.createInstance(
                    "meta::pure::metamodel::type::generics::InferredGenericType", resolver);
            if (igtType != null)
            {
                org.finos.legend.pure.truffle.runtime.dynobj.PureObj.write(lambdaCGT, "classifierGenericType",
                        _GenericType.buildUserDefinedGenericType(igtType, resolver));
            }
            org.finos.legend.pure.truffle.runtime.dynobj.PureObj.write(lambdaCGT, "type", lambdaFunctionTypeObj);
            org.finos.legend.pure.truffle.runtime.dynobj.PureObj.write(lambdaCGT, "typeArguments",
                    new ObjectSequence(new Object[]{ftGT}));

            // Lambda wrapping the AtomicValue
            Object atomicValue = org.finos.legend.pure.truffle.runtime.TruffleInstanceFactory.createInstance(
                    "meta::pure::metamodel::valuespecification::AtomicValue", resolver);
            Object avType = resolver.getElement("meta::pure::metamodel::valuespecification::AtomicValue");
            if (avType != null)
            {
                org.finos.legend.pure.truffle.runtime.dynobj.PureObj.write(atomicValue, "classifierGenericType",
                        _GenericType.buildUserDefinedGenericType(avType, resolver));
            }
            org.finos.legend.pure.truffle.runtime.dynobj.PureObj.write(atomicValue, "value", enumInstance);
            org.finos.legend.pure.truffle.runtime.dynobj.PureObj.write(atomicValue, "genericType", enumGT);
            org.finos.legend.pure.truffle.runtime.dynobj.PureObj.write(atomicValue, "multiplicity", pureOne);

            Object lambda = org.finos.legend.pure.truffle.runtime.TruffleInstanceFactory.createInstance(
                    "meta::pure::metamodel::function::LambdaFunction", resolver);
            org.finos.legend.pure.truffle.runtime.dynobj.PureObj.write(lambda, "classifierGenericType", lambdaCGT);
            org.finos.legend.pure.truffle.runtime.dynobj.PureObj.write(lambda, "expressionSequence",
                    new ObjectSequence(new Object[]{atomicValue}));

            // Property CGT: Property<Enumeration<self>, EnumType|1>
            var propCGT = _GenericType.buildUserDefinedGenericType(propertyTypeObj, resolver);
            org.finos.legend.pure.truffle.runtime.dynobj.PureObj.write(propCGT, "typeArguments",
                    new ObjectSequence(new Object[]{cgt, enumGT}));
            org.finos.legend.pure.truffle.runtime.dynobj.PureObj.write(propCGT, "multiplicityArguments",
                    new ObjectSequence(new Object[]{pureOne}));

            Object prop = org.finos.legend.pure.truffle.runtime.TruffleInstanceFactory.createInstance(
                    "meta::pure::metamodel::function::property::Property", resolver);
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
