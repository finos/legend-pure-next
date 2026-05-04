package org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper;

import meta.pure.metamodel.function.property.Property;
import meta.pure.metamodel.type.Class;
import meta.pure.metamodel.type.generics.GenericType;
import org.eclipse.collections.api.factory.Lists;
import org.finos.legend.pure.m3.PureModel;
import org.finos.legend.pure.m3.module.CompilationResult;
import org.finos.legend.pure.m3.module.ScopedMetadataAccess;
import org.finos.legend.pure.m3.module.bootstrapModule.BootstrapModule;
import org.finos.legend.pure.m3.module.localModule.LocalModule;
import org.finos.legend.pure.m3.module.localModule.PureContent;
import org.finos.legend.pure.m3.pureLanguage.PureLanguageExtension;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class _PropertyTest
{
    private static PureModel model;
    private static Class testClass;
    private static Class myClass;

    @BeforeAll
    public static void setUp()
    {
        String source = 
            "Class test::MyClass<T, U|m>\n" +
            "{\n" +
            "}\n" +
            "Class test::TestClass\n" +
            "{\n" +
            "    prop: test::MyClass<String, Integer | 1..*>[1];\n" +
            "}\n";

        model = PureModel.withModules(
                Lists.mutable.with(new BootstrapModule(BootstrapModule.locateM3Ttl()),
                        new LocalModule("test", "*", Lists.mutable.with("m3"),
                                Lists.mutable.with(new PureContent(source, "test.pure"))))
        ).withExtensions(Lists.mutable.with(new PureLanguageExtension())).build();

        CompilationResult result = model.compile();
        assertTrue(result.errors().isEmpty(), "Compilation errors should be empty: " + result.errors());

        testClass = (Class) model.getModule("test").getElement("test::TestClass");
        myClass = (Class) model.getModule("test").getElement("test::MyClass");
    }

    @Test
    public void testResolveProperty()
    {
        Property prop = testClass._properties().detect(p -> "prop".equals(p._name()));
        GenericType receiverType = prop._genericType();
        
        ScopedMetadataAccess metadataAccess = new ScopedMetadataAccess(model.getModule("test"), model);
        
        Property sourceProp = new meta.pure.metamodel.function.property.PropertyImpl()
                ._name("mockProp")
                ._owner(myClass)
                ._genericType(_GenericType.buildUserDefinedGenericType(testClass, metadataAccess))
                ._multiplicity((meta.pure.metamodel.multiplicity.Multiplicity) metadataAccess.getElement("meta::pure::metamodel::multiplicity::PureOne"))
                ._classifierGenericType(_GenericType.buildUserDefinedGenericType((meta.pure.metamodel.type.Type) metadataAccess.getElement("meta::pure::metamodel::function::property::Property"), metadataAccess));
        
        Property resolvedProp = _Property.resolveProperty(sourceProp, receiverType, metadataAccess);
        assertEquals("mockProp", resolvedProp._name());
    }
}
