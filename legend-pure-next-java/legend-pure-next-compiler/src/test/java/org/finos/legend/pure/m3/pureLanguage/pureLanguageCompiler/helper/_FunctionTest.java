package org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper;

import meta.pure.metamodel.function.Function;
import meta.pure.metamodel.type.FunctionType;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class _FunctionTest
{
    private static PureModel model;

    @BeforeAll
    public static void setUp()
    {
        String source = 
            "function test::testFn():Any[*] { {a:String[1]| $a}; }\n" +
            "function test::testFn2():Boolean[1] { true; }\n";

        model = PureModel.withModules(
                Lists.mutable.with(new BootstrapModule(),
                        new LocalModule("test", "*", Lists.mutable.with("m3"),
                                Lists.mutable.with(new PureContent(source, "test.pure"))))
        ).withExtensions(Lists.mutable.with(new PureLanguageExtension())).build();

        CompilationResult result = model.compile();
        assertTrue(result.errors().isEmpty(), "Compilation errors should be empty: " + result.errors());
    }

    @Test
    public void testResolveFunctionType()
    {
        Function func = (Function) model.getModule("test").getElement("test::testFn__Any_MANY_");
        
        FunctionType ft = _Function.getFunctionType(func, new ScopedMetadataAccess(model.getModule("test"), model));
        
        assertNotNull(ft);
        assertTrue(ft._parameters().isEmpty());
        assertEquals("Any", ((meta.pure.metamodel.PackageableElement) _GenericType.type(ft._returnType()))._name());
    }

    @Test
    public void testGetFunctionType()
    {
        Function func = (Function) model.getModule("test").getElement("test::testFn2__Boolean_1_");
        
        FunctionType ft = _Function.getFunctionType(func, new ScopedMetadataAccess(model.getModule("test"), model));
        
        assertNotNull(ft);
        assertEquals("Boolean", ((meta.pure.metamodel.PackageableElement) _GenericType.type(ft._returnType()))._name());
        assertEquals(1L, _Multiplicity.lowerBound(ft._returnMultiplicity()));
    }
}
