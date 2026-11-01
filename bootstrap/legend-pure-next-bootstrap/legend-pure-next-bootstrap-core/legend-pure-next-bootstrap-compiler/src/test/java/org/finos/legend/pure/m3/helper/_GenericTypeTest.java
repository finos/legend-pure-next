// Copyright 2026 Goldman Sachs
// ©2026 JP Morgan Chase & Co. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package org.finos.legend.pure.m3.helper;

import platform.pure.metamodel.type.generics.TypeParameter;
import org.eclipse.collections.api.set.MutableSet;
import platform.pure.metamodel.function.property.Property;
import platform.pure.metamodel.type.Class;
import platform.pure.metamodel.type.generics.GenericType;
import platform.pure.metamodel.type.generics.UndefinedGenericTypeImpl;
import org.eclipse.collections.api.factory.Lists;
import org.eclipse.collections.api.factory.Sets;
import org.finos.legend.pure.m3.JavaCompiler;
import org.finos.legend.pure.m3.module.CompilationResult;
import org.finos.legend.pure.m3.module.Module;
import org.finos.legend.pure.m3.module.bootstrapModule.BootstrapModule;
import org.finos.legend.pure.m3.module.sourceModule.SourceModule;
import org.finos.legend.pure.m3.module.sourceModule.PureContent;
import org.finos.legend.pure.m3.pureLanguage.PureLanguageExtension;
import org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._GenericType;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class _GenericTypeTest
{
    private static JavaCompiler model;
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

        model = JavaCompiler.withModules(
                Lists.mutable.with(new BootstrapModule(BootstrapModule.locateM3Ttl()),
                        new SourceModule("test", "*", Lists.mutable.with("m3"),
                                Lists.mutable.with(new PureContent(source, "test.pure"))))
        ).withExtensions(Lists.mutable.with(new PureLanguageExtension())).build();

        CompilationResult result = model.compile();
        assertTrue(result.errors().isEmpty(), "Compilation errors should be empty: " + result.errors());

        m3 = model.registry().module("m3");
        testClass = (Class) model.registry().module("test").getElement("test::TestClass");
        myClass = (Class) model.registry().module("test").getElement("test::MyClass");
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
        GenericType ugt = new UndefinedGenericTypeImpl(model.registry().module("test"));
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

        GenericType ugt = new UndefinedGenericTypeImpl(model.registry().module("test"));
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

        GenericType ugt = new UndefinedGenericTypeImpl(model.registry().module("test"));
        assertEquals(0, _GenericType.collectReferencedTypeParameterNames(ugt).size());
    }

    @Test
    public void testCollectReferencedMultiplicityParameterNames()
    {
        GenericType intGT = getPropGT("intProp");
        assertEquals(0, _GenericType.collectReferencedMultiplicityParameterNames(intGT).size());

        GenericType ugt = new UndefinedGenericTypeImpl(model.registry().module("test"));
        assertEquals(0, _GenericType.collectReferencedMultiplicityParameterNames(ugt).size());
    }

    @Test
    public void testIsConcreteGenericTypeInContext()
    {
        GenericType intGT = getPropGT("intProp");
        assertTrue(_GenericType.isConcreteInContext(intGT, Sets.mutable.empty()));

        // the scope as the compiler has it: the scope keys of the class's own type parameters
        MutableSet<String> classScope = _GenericType.scopeKeysOf(myClass._typeParameters().collect(TypeParameter::_owner), myClass._typeParameters().collect(TypeParameter::_name));
        GenericType tpGT = getMyClassTpGT();
        assertTrue(_GenericType.isConcreteInContext(tpGT, classScope));
        assertFalse(_GenericType.isConcreteInContext(tpGT, Sets.mutable.empty()));
        assertFalse(_GenericType.isConcreteInContext(tpGT, Sets.mutable.with("V")));
        // another owner's T, named like the class's, is not in its scope
        assertFalse(_GenericType.isConcreteInContext(tpGT, Sets.mutable.with(_GenericType.scopeKey(new Object(), "T"))));

        Property nProp = myClass._properties().detect(p -> "nestedTpProp".equals(p._name()));
        GenericType nestedTpProp = nProp._genericType();
        assertTrue(_GenericType.isConcreteInContext(nestedTpProp, classScope));
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
        platform.pure.metamodel.type.generics.InferredGenericTypeImpl intGT = new platform.pure.metamodel.type.generics.InferredGenericTypeImpl(m3)
                ._type((platform.pure.metamodel.type.Type) m3.getElement("platform::pure::metamodel::type::primitives::Integer"));
        platform.pure.metamodel.type.generics.InferredGenericTypeImpl floatGT = new platform.pure.metamodel.type.generics.InferredGenericTypeImpl(m3)
                ._type((platform.pure.metamodel.type.Type) m3.getElement("platform::pure::metamodel::type::primitives::Float"));
        GenericType result = _GenericType.findCommonGenericType(Lists.mutable.with(intGT, floatGT), m3);
        assertNotNull(result);
        assertEquals("Number", _GenericType.print(result));
    }

    @Test
    public void testFindCommonGenericType_lambdaWithDifferentFunctionTypeInstances()
    {
        platform.pure.metamodel.type.Type lambdaType = (platform.pure.metamodel.type.Type) m3.getElement("platform::pure::metamodel::function::LambdaFunction");
        platform.pure.metamodel.type.Type intType = (platform.pure.metamodel.type.Type) m3.getElement("platform::pure::metamodel::type::primitives::Integer");
        platform.pure.metamodel.multiplicity.Multiplicity pureOne = (platform.pure.metamodel.multiplicity.Multiplicity) m3.getElement("platform::pure::metamodel::multiplicity::PureOne");

        platform.pure.metamodel.type.FunctionTypeImpl ft1 = new platform.pure.metamodel.type.FunctionTypeImpl(m3);
        ft1._parameters(Lists.mutable.with(
                new platform.pure.metamodel.valuespecification.VariableExpressionImpl(m3)
                        ._name("x")
                        ._genericType(new platform.pure.metamodel.type.generics.InferredGenericTypeImpl(m3)._type(intType))
                        ._multiplicity(pureOne)));
        ft1._returnType(new platform.pure.metamodel.type.generics.InferredGenericTypeImpl(m3)._type(intType));
        ft1._returnMultiplicity(pureOne);

        platform.pure.metamodel.type.FunctionTypeImpl ft2 = new platform.pure.metamodel.type.FunctionTypeImpl(m3);
        ft2._parameters(Lists.mutable.with(
                new platform.pure.metamodel.valuespecification.VariableExpressionImpl(m3)
                        ._name("y")
                        ._genericType(new platform.pure.metamodel.type.generics.InferredGenericTypeImpl(m3)._type(intType))
                        ._multiplicity(pureOne)));
        ft2._returnType(new platform.pure.metamodel.type.generics.InferredGenericTypeImpl(m3)._type(intType));
        ft2._returnMultiplicity(pureOne);

        GenericType lambdaGT1 = new platform.pure.metamodel.type.generics.InferredGenericTypeImpl(m3)
                ._type(lambdaType)
                ._typeArguments(Lists.mutable.with(
                        new platform.pure.metamodel.type.generics.InferredGenericTypeImpl(m3)._type(ft1)));
        GenericType lambdaGT2 = new platform.pure.metamodel.type.generics.InferredGenericTypeImpl(m3)
                ._type(lambdaType)
                ._typeArguments(Lists.mutable.with(
                        new platform.pure.metamodel.type.generics.InferredGenericTypeImpl(m3)._type(ft2)));

        GenericType result = _GenericType.findCommonGenericType(Lists.mutable.with(lambdaGT1, lambdaGT2), m3);
        assertNotNull(result);
        String resultStr = _GenericType.print(result);
        assertTrue(resultStr.startsWith("LambdaFunction"), "Expected LambdaFunction but got: " + resultStr);
        assertTrue(resultStr.contains("Integer"), "Expected Integer in type args but got: " + resultStr);
    }
}
