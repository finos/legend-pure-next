package org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper;

import meta.pure.metamodel.type.Class;
import meta.pure.metamodel.type.generics.GenericType;
import meta.pure.metamodel.function.property.Property;
import org.eclipse.collections.api.factory.Lists;
import org.finos.legend.pure.m3.PureModel;
import org.finos.legend.pure.m3.module.CompilationResult;
import org.finos.legend.pure.m3.module.bootstrapModule.BootstrapModule;
import org.finos.legend.pure.m3.module.localModule.LocalModule;
import org.finos.legend.pure.m3.module.localModule.PureContent;
import org.finos.legend.pure.m3.pureLanguage.PureLanguageExtension;
import org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.ParametersBinding;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class _ClassTest
{
    @Test
    public void testBuildBindingsFromGenericType()
    {
        String source = 
            "Class test::Wrapper<Z>\n" +
            "{\n" +
            "}\n" +
            "\n" +
            "Class test::Target\n" +
            "{\n" +
            "   prop2 : meta::pure::metamodel::relation::Column<test::Wrapper<String>, Integer|1..*>[1];\n" +
            "}\n";

        PureModel model = PureModel.withModules(
                Lists.mutable.with(new BootstrapModule(BootstrapModule.locateM3Ttl()),
                        new LocalModule("test", "*", Lists.mutable.with("m3"),
                                Lists.mutable.with(new PureContent(source, "test.pure"))))
        ).withExtensions(Lists.mutable.with(new PureLanguageExtension())).build();

        CompilationResult result = model.compile();
        assertTrue(result.errors().isEmpty(), "Compilation errors should be empty: " + result.errors());

        Class ownerClass = (Class) model.getModule("m3").getElement("meta::pure::metamodel::relation::Column");
        Class targetClass = (Class) model.getModule("test").getElement("test::Target");
        
        Property prop2 = targetClass._properties().getFirst();
        GenericType receiverType = prop2._genericType();

        ParametersBinding bindings = _Class.buildBindingsFromGenericType(ownerClass, receiverType);
        assertEquals("{U=test::Wrapper<String>, V=Integer | m=[1..*]}", bindings.toString());
    }

    @Test
    public void testFindProperty()
    {
        String source = 
            "Class test::SuperClass\n" +
            "{\n" +
            "   superProp : String[1];\n" +
            "}\n" +
            "Class test::SubClass extends test::SuperClass\n" +
            "{\n" +
            "   subProp : Integer[1];\n" +
            "}\n" +
            "Association test::Asso\n" +
            "{\n" +
            "   origin : test::SubClass[1];\n" +
            "   assocProp : test::SuperClass[1];\n" +
            "}\n";

        PureModel model = PureModel.withModules(
                Lists.mutable.with(new BootstrapModule(BootstrapModule.locateM3Ttl()),
                        new LocalModule("test", "*", Lists.mutable.with("m3"),
                                Lists.mutable.with(new PureContent(source, "test.pure"))))
        ).withExtensions(Lists.mutable.with(new PureLanguageExtension())).build();

        CompilationResult result = model.compile();
        assertTrue(result.errors().isEmpty(), "Compilation errors should be empty: " + result.errors());

        Class subClass = (Class) model.getModule("test").getElement("test::SubClass");

        // Direct
        Property subProp = _Class.findProperty(subClass, "subProp");
        assertNotNull(subProp);
        assertEquals("subProp", subProp._name());

        // Inherited
        Property superProp = _Class.findProperty(subClass, "superProp");
        assertNotNull(superProp);
        assertEquals("superProp", superProp._name());

        // Association
        Property assocProp = _Class.findProperty(subClass, "assocProp");
        assertNotNull(assocProp);
        assertEquals("assocProp", assocProp._name());

        // Not Found
        Property notFound = _Class.findProperty(subClass, "notFound");
        assertNull(notFound);
    }

    @Test
    public void testFindQualifiedProperties()
    {
        String source = 
            "Class test::SuperClass\n" +
            "{\n" +
            "   superQual(a:String[1]) { $a } : String[1];\n" +
            "   overloadedQual(a:String[1]) { $a } : String[1];\n" +
            "}\n" +
            "Class test::SubClass extends test::SuperClass\n" +
            "{\n" +
            "   subQual(a:String[1]) { $a } : String[1];\n" +
            "   overloadedQual(a:String[1], b:String[1]) { $a } : String[1];\n" +
            "}\n";

        PureModel model = PureModel.withModules(
                Lists.mutable.with(new BootstrapModule(BootstrapModule.locateM3Ttl()),
                        new LocalModule("test", "*", Lists.mutable.with("m3"),
                                Lists.mutable.with(new PureContent(source, "test.pure"))))
        ).withExtensions(Lists.mutable.with(new PureLanguageExtension())).build();

        CompilationResult result = model.compile();
        assertTrue(result.errors().isEmpty(), "Compilation errors should be empty: " + result.errors());

        Class subClass = (Class) model.getModule("test").getElement("test::SubClass");

        assertEquals(1, _Class.findQualifiedProperties(subClass, "subQual").size());
        assertEquals(1, _Class.findQualifiedProperties(subClass, "superQual").size());
        // Since SubClass defines overloadedQual with different signature than SuperClass, both are returned
        assertEquals(2, _Class.findQualifiedProperties(subClass, "overloadedQual").size());

        assertEquals(0, _Class.findQualifiedProperties(subClass, "notFound").size());
    }
}
