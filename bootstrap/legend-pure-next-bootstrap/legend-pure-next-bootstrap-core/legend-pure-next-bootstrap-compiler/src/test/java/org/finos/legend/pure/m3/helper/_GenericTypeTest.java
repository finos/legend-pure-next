package org.finos.legend.pure.m3.helper;

import meta.pure.metamodel.function.property.Property;
import meta.pure.metamodel.type.Class;
import meta.pure.metamodel.type.generics.GenericType;
import meta.pure.metamodel.type.generics.UndefinedGenericTypeImpl;
import org.eclipse.collections.api.factory.Lists;
import org.eclipse.collections.api.factory.Sets;
import org.finos.legend.pure.m3.PureModel;
import org.finos.legend.pure.m3.module.CompilationResult;
import org.finos.legend.pure.m3.module.Module;
import org.finos.legend.pure.m3.module.bootstrapModule.BootstrapModule;
import org.finos.legend.pure.m3.module.localModule.LocalModule;
import org.finos.legend.pure.m3.module.localModule.PureContent;
import org.finos.legend.pure.m3.pureLanguage.PureLanguageExtension;
import org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._GenericType;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class _GenericTypeTest
{
    private static PureModel model;
    private static Module m3;
    private static Class testClass;
    private static Class myClass;

    @BeforeAll
    public static void setUp()
    {
        String source = 
            "Class test::MyClass<T>\n" +
            "{\n" +
            "    tpProp: T[1];\n" +
            "    nestedTpProp: Class<T>[1];\n" +
            "}\n" +
            "Class test::TestClass\n" +
            "{\n" +
            "    intProp: Integer[1];\n" +
            "    strProp: String[1];\n" +
            "    listProp: Class<Integer>[1];\n" +
            "    myClassProp: test::MyClass<Integer>[1];\n" +
            "    classTpProp: Class<test::MyClass<String>>[1];\n" +
            "}\n";

        model = PureModel.withModules(
                Lists.mutable.with(new BootstrapModule(BootstrapModule.locateM3Ttl()),
                        new LocalModule("test", "*", Lists.mutable.with("m3"),
                                Lists.mutable.with(new PureContent(source, "test.pure"))))
        ).withExtensions(Lists.mutable.with(new PureLanguageExtension())).build();

        CompilationResult result = model.compile();
        assertTrue(result.errors().isEmpty(), "Compilation errors should be empty: " + result.errors());

        m3 = model.getModule("m3");
        testClass = (Class) model.getModule("test").getElement("test::TestClass");
        myClass = (Class) model.getModule("test").getElement("test::MyClass");
    }

    private GenericType getPropGT(String name)
    {
        Property prop = testClass._properties().detect(p -> name.equals(p._name()));
        return prop._genericType();
    }

    private GenericType getMyClassTpGT()
    {
        Property prop = myClass._properties().detect(p -> "tpProp".equals(p._name()));
        return prop._genericType();
    }

    @Test
    public void testPrintGenericTypeSimple()
    {
        GenericType intGT = getPropGT("intProp");
        Assertions.assertEquals("Integer", _GenericType.print(intGT, true));
        assertEquals("Integer", _GenericType.print(intGT, false));
    }

    @Test
    public void testPrintGenericTypeString()
    {
        GenericType strGT = getPropGT("strProp");
        assertEquals("String", _GenericType.print(strGT, false));
    }

    @Test
    public void testPrintGenericTypeUndefined()
    {
        GenericType ugt = new UndefinedGenericTypeImpl(model.getModule("test"));
        assertEquals("?", _GenericType.print(ugt, false));
    }

    @Test
    public void testPrintGenericTypeTypeParameter()
    {
        GenericType tpGT = getMyClassTpGT();
        String result = _GenericType.print(tpGT, false);
        assertTrue(result.length() > 0);
    }

    @Test
    public void testGenericTypeTypeArguments()
    {
        GenericType listGT = getPropGT("listProp");
        assertEquals(1, _GenericType.typeArguments(listGT).size());
    }

    @Test
    public void testGenericTypeMultiplicityArguments()
    {
        GenericType intGT = getPropGT("intProp");
        assertTrue(_GenericType.multiplicityArguments(intGT).isEmpty());
    }

    @Test
    public void testIsConcreteGenericType()
    {
        GenericType intGT = getPropGT("intProp");
        assertTrue(_GenericType.isConcrete(intGT));

        GenericType tpGT = getMyClassTpGT();
        assertFalse(_GenericType.isConcrete(tpGT));

        GenericType ugt = new UndefinedGenericTypeImpl(model.getModule("test"));
        assertTrue(_GenericType.isConcrete(ugt));
    }

    @Test
    public void testIsConcreteGenericTypeWithTypeArgs()
    {
        GenericType classGT = getPropGT("listProp");
        assertTrue(_GenericType.isConcrete(classGT));

        GenericType tpClassProp = getPropGT("classTpProp");
        assertTrue(_GenericType.isConcrete(tpClassProp));
        
        Property nProp = myClass._properties().detect(p -> "nestedTpProp".equals(p._name()));
        assertFalse(_GenericType.isConcrete(nProp._genericType()));
    }

    @Test
    public void testCollectReferencedTypeParameterNames()
    {
        GenericType intGT = getPropGT("intProp");
        assertEquals(0, _GenericType.collectReferencedTypeParameterNames(intGT).size());

        GenericType tpGT = getMyClassTpGT();
        assertEquals(1, _GenericType.collectReferencedTypeParameterNames(tpGT).size());
        assertTrue(_GenericType.collectReferencedTypeParameterNames(tpGT).contains("T"));

        GenericType myClassProp = getPropGT("myClassProp"); // MyClass<Integer>
        assertEquals(0, _GenericType.collectReferencedTypeParameterNames(myClassProp).size());

        Property nProp = myClass._properties().detect(p -> "nestedTpProp".equals(p._name()));
        GenericType tpClassGT = nProp._genericType(); // Class<T>
        assertEquals(1, _GenericType.collectReferencedTypeParameterNames(tpClassGT).size());
        assertTrue(_GenericType.collectReferencedTypeParameterNames(tpClassGT).contains("T"));

        GenericType ugt = new UndefinedGenericTypeImpl(model.getModule("test"));
        assertEquals(0, _GenericType.collectReferencedTypeParameterNames(ugt).size());
    }

    @Test
    public void testCollectReferencedMultiplicityParameterNames()
    {
        GenericType intGT = getPropGT("intProp");
        assertEquals(0, _GenericType.collectReferencedMultiplicityParameterNames(intGT).size());

        GenericType ugt = new UndefinedGenericTypeImpl(model.getModule("test"));
        assertEquals(0, _GenericType.collectReferencedMultiplicityParameterNames(ugt).size());
    }

    @Test
    public void testIsConcreteGenericTypeInContext()
    {
        GenericType intGT = getPropGT("intProp");
        assertTrue(_GenericType.isConcreteInContext(intGT, Sets.mutable.empty()));

        GenericType tpGT = getMyClassTpGT();
        assertTrue(_GenericType.isConcreteInContext(tpGT, Sets.mutable.with("T")));
        assertFalse(_GenericType.isConcreteInContext(tpGT, Sets.mutable.empty()));
        assertFalse(_GenericType.isConcreteInContext(tpGT, Sets.mutable.with("V")));

        Property nProp = myClass._properties().detect(p -> "nestedTpProp".equals(p._name()));
        GenericType nestedTpProp = nProp._genericType();
        assertTrue(_GenericType.isConcreteInContext(nestedTpProp, Sets.mutable.with("T")));
        assertFalse(_GenericType.isConcreteInContext(nestedTpProp, Sets.mutable.empty()));
    }

    @Test
    public void testFindCommonGenericType_sameType()
    {
        GenericType intGT1 = getPropGT("intProp");
        GenericType intGT2 = getPropGT("intProp");
        GenericType result = _GenericType.findCommonGenericType(Lists.mutable.with(intGT1, intGT2), m3);
        assertNotNull(result);
        assertEquals("Integer", _GenericType.print(result));
    }

    @Test
    public void testFindCommonGenericType_subtypes()
    {
        meta.pure.metamodel.type.generics.InferredGenericTypeImpl intGT = new meta.pure.metamodel.type.generics.InferredGenericTypeImpl(m3)
                ._type((meta.pure.metamodel.type.Type) m3.getElement("meta::pure::metamodel::type::primitives::Integer"));
        meta.pure.metamodel.type.generics.InferredGenericTypeImpl floatGT = new meta.pure.metamodel.type.generics.InferredGenericTypeImpl(m3)
                ._type((meta.pure.metamodel.type.Type) m3.getElement("meta::pure::metamodel::type::primitives::Float"));
        GenericType result = _GenericType.findCommonGenericType(Lists.mutable.with(intGT, floatGT), m3);
        assertNotNull(result);
        assertEquals("Number", _GenericType.print(result));
    }

    @Test
    public void testFindCommonGenericType_lambdaWithDifferentFunctionTypeInstances()
    {
        meta.pure.metamodel.type.Type lambdaType = (meta.pure.metamodel.type.Type) m3.getElement("meta::pure::metamodel::function::LambdaFunction");
        meta.pure.metamodel.type.Type intType = (meta.pure.metamodel.type.Type) m3.getElement("meta::pure::metamodel::type::primitives::Integer");
        meta.pure.metamodel.multiplicity.Multiplicity pureOne = (meta.pure.metamodel.multiplicity.Multiplicity) m3.getElement("meta::pure::metamodel::multiplicity::PureOne");

        meta.pure.metamodel.type.FunctionTypeImpl ft1 = new meta.pure.metamodel.type.FunctionTypeImpl(m3);
        ft1._parameters(Lists.mutable.with(
                new meta.pure.metamodel.valuespecification.VariableExpressionImpl(m3)
                        ._name("x")
                        ._genericType(new meta.pure.metamodel.type.generics.InferredGenericTypeImpl(m3)._type(intType))
                        ._multiplicity(pureOne)));
        ft1._returnType(new meta.pure.metamodel.type.generics.InferredGenericTypeImpl(m3)._type(intType));
        ft1._returnMultiplicity(pureOne);

        meta.pure.metamodel.type.FunctionTypeImpl ft2 = new meta.pure.metamodel.type.FunctionTypeImpl(m3);
        ft2._parameters(Lists.mutable.with(
                new meta.pure.metamodel.valuespecification.VariableExpressionImpl(m3)
                        ._name("y")
                        ._genericType(new meta.pure.metamodel.type.generics.InferredGenericTypeImpl(m3)._type(intType))
                        ._multiplicity(pureOne)));
        ft2._returnType(new meta.pure.metamodel.type.generics.InferredGenericTypeImpl(m3)._type(intType));
        ft2._returnMultiplicity(pureOne);

        GenericType lambdaGT1 = new meta.pure.metamodel.type.generics.InferredGenericTypeImpl(m3)
                ._type(lambdaType)
                ._typeArguments(Lists.mutable.with(
                        new meta.pure.metamodel.type.generics.InferredGenericTypeImpl(m3)._type(ft1)));
        GenericType lambdaGT2 = new meta.pure.metamodel.type.generics.InferredGenericTypeImpl(m3)
                ._type(lambdaType)
                ._typeArguments(Lists.mutable.with(
                        new meta.pure.metamodel.type.generics.InferredGenericTypeImpl(m3)._type(ft2)));

        GenericType result = _GenericType.findCommonGenericType(Lists.mutable.with(lambdaGT1, lambdaGT2), m3);
        assertNotNull(result);
        String resultStr = _GenericType.print(result);
        assertTrue(resultStr.startsWith("LambdaFunction"), "Expected LambdaFunction but got: " + resultStr);
        assertTrue(resultStr.contains("Integer"), "Expected Integer in type args but got: " + resultStr);
    }
}
