package org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper;

import meta.pure.metamodel.function.FunctionDefinition;
import meta.pure.metamodel.function.LambdaFunction;
import meta.pure.metamodel.type.FunctionType;
import meta.pure.metamodel.type.generics.GenericType;
import meta.pure.metamodel.valuespecification.FunctionExpression;
import meta.pure.metamodel.valuespecification.ValueSpecification;
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

public class _LambdaTest
{
    private static PureModel model;
    
    @BeforeAll
    public static void setUp()
    {
        String source = 
            "function test::testFn():Any[*] { {a:String[1]| $a}; }";

        model = PureModel.withModules(
                Lists.mutable.with(new BootstrapModule(),
                        new LocalModule("test", "*", Lists.mutable.with("m3"),
                                Lists.mutable.with(new PureContent(source, "test.pure"))))
        ).withExtensions(Lists.mutable.with(new PureLanguageExtension())).build();

        CompilationResult result = model.compile();
        assertTrue(result.errors().isEmpty(), "Compilation errors should be empty: " + result.errors());
    }

    private LambdaFunction getLambda()
    {
        FunctionDefinition fd = (FunctionDefinition) model.getModule("test").getElement("test::testFn__Any_MANY_");
        meta.pure.metamodel.valuespecification.AtomicValue letExpr = (meta.pure.metamodel.valuespecification.AtomicValue) fd._expressionSequence().getFirst();
        return (LambdaFunction) letExpr._value();
    }

    @Test
    public void testBuildFunctionType()
    {
        LambdaFunction lambda = getLambda();
        FunctionType ft = _Lambda.buildFunctionType(lambda, new ScopedMetadataAccess(model.getModule("test"), model));
        
        assertNotNull(ft);
        assertEquals(1, ft._parameters().size());
        assertEquals("a", ft._parameters().getFirst()._name());
        assertEquals("String", ((meta.pure.metamodel.PackageableElement) _GenericType.type(ft._parameters().getFirst()._genericType()))._name());
        
        ValueSpecification lastExpr = lambda._expressionSequence().getLast();
        assertEquals(lastExpr._genericType(), ft._returnType());
        assertEquals(1L, _Multiplicity.lowerBound(ft._returnMultiplicity()));
    }

    @Test
    public void testGetLambdaClassifierGenericType()
    {
        LambdaFunction lambda = getLambda();
        GenericType gto = _Lambda.getLambdaClassifierGenericType(new ScopedMetadataAccess(model.getModule("test"), model), lambda);
        
        assertEquals("LambdaFunction", ((meta.pure.metamodel.PackageableElement) _GenericType.type(gto))._name());
        assertEquals(1, _GenericType.typeArguments(gto).size());
        assertTrue(_GenericType.type(_GenericType.typeArguments(gto).getFirst()) instanceof FunctionType);
    }
}
