package org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper;

import meta.pure.metamodel.type.Class;
import meta.pure.metamodel.type.generics.GenericType;
import meta.pure.metamodel.valuespecification.FunctionExpression;
import meta.pure.metamodel.function.FunctionDefinition;
import org.eclipse.collections.api.factory.Lists;
import org.eclipse.collections.api.factory.Sets;
import org.finos.legend.pure.m3.PureModel;
import org.finos.legend.pure.m3.module.CompilationResult;
import org.finos.legend.pure.m3.module.ScopedMetadataAccess;
import org.finos.legend.pure.m3.module.bootstrapModule.BootstrapModule;
import org.finos.legend.pure.m3.module.localModule.LocalModule;
import org.finos.legend.pure.m3.module.localModule.PureContent;
import org.finos.legend.pure.m3.pureLanguage.PureLanguageExtension;
import org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.ParametersBinding;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class _FunctionExpressionTest
{
    @Test
    public void testExtractResolvedParametersBinding_empty()
    {
        String source = 
            "function test::f2(a:String[1]):String[1] { $a }\n" +
            "function test::f():Any[*]\n" +
            "{\n" +
            "   test::f2('hi');\n" +
            "}\n";

        PureModel model = PureModel.withModules(
                Lists.mutable.with(new BootstrapModule(),
                        new LocalModule("test", "*", Lists.mutable.with("m3"),
                                Lists.mutable.with(new PureContent(source, "test.pure"))))
        ).withExtensions(Lists.mutable.with(new PureLanguageExtension())).build();
        model.compile();

        FunctionDefinition fd = (FunctionDefinition) model.getModule("test").getElement("test::f__Any_MANY_");
        FunctionExpression expr = (FunctionExpression) fd._expressionSequence().getFirst();

        ParametersBinding bindings = _FunctionExpression.extractResolvedParametersBinding(expr);
        assertTrue(bindings.typeBindings().isEmpty());
        assertTrue(bindings.multiplicityBindings().isEmpty());
    }

    @Test
    public void testPopulateAndExtract()
    {
        String source = 
            "Class test::Target\n" +
            "{\n" +
            "}\n" +
            "function test::f2(a:String[1]):String[1] { $a }\n" +
            "function test::f():Any[*]\n" +
            "{\n" +
            "   test::f2('hi');\n" +
            "}\n";

        PureModel model = PureModel.withModules(
                Lists.mutable.with(new BootstrapModule(),
                        new LocalModule("test", "*", Lists.mutable.with("m3"),
                                Lists.mutable.with(new PureContent(source, "test.pure"))))
        ).withExtensions(Lists.mutable.with(new PureLanguageExtension())).build();

        CompilationResult result = model.compile();
        assertTrue(result.errors().isEmpty(), "Compilation errors should be empty: " + result.errors());

        Class targetClass = (Class) model.getModule("test").getElement("test::Target");
        FunctionDefinition fd = (FunctionDefinition) model.getModule("test").getElement("test::f__Any_MANY_");
        FunctionExpression expr = (FunctionExpression) fd._expressionSequence().getFirst();
        
        GenericType targetGT = targetClass._classifierGenericType();
        meta.pure.metamodel.multiplicity.Multiplicity mul = (meta.pure.metamodel.multiplicity.Multiplicity) model.getModule("m3").getElement("meta::pure::metamodel::multiplicity::PureOne");

        ParametersBinding bindings = org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.PlainParametersBinding.empty();
        bindings.typeBindings().put("T", Sets.mutable.with(targetGT));
        bindings.typeBindings().put("Z", Sets.mutable.with(targetGT)); // Will be written but not extracted as own if we filtered later
        bindings.multiplicityBindings().put("m", Sets.mutable.with(mul));

        _FunctionExpression.populateResolvedParameters(expr, bindings, new ScopedMetadataAccess(model.getModule("test"), model));

        assertNotNull(expr._resolvedTypeParameters());
        assertEquals(2, expr._resolvedTypeParameters().size());

        assertNotNull(expr._resolvedMultiplicityParameters());
        assertEquals(1, expr._resolvedMultiplicityParameters().size());
        assertEquals("m", expr._resolvedMultiplicityParameters().getFirst()._name());
        assertEquals(mul, expr._resolvedMultiplicityParameters().getFirst()._value());

        // Now test extraction
        ParametersBinding extracted = _FunctionExpression.extractResolvedParametersBinding(expr);
        assertEquals(2, extracted.typeBindings().size());
        assertEquals(targetGT, extracted.typeBindings().get("T").iterator().next());
        
        assertEquals(1, extracted.multiplicityBindings().size());
        assertEquals(mul, extracted.multiplicityBindings().get("m").iterator().next());
    }

    @Test
    public void testPopulateWithOwnership()
    {
        String source = 
            "Class test::Target\n" +
            "{\n" +
            "}\n" +
            "function test::f2(a:String[1]):String[1] { $a }\n" +
            "function test::f():Any[*]\n" +
            "{\n" +
            "   test::f2('hi');\n" +
            "}\n";

        PureModel model = PureModel.withModules(
                Lists.mutable.with(new BootstrapModule(),
                        new LocalModule("test", "*", Lists.mutable.with("m3"),
                                Lists.mutable.with(new PureContent(source, "test.pure"))))
        ).withExtensions(Lists.mutable.with(new PureLanguageExtension())).build();

        model.compile();

        Class targetClass = (Class) model.getModule("test").getElement("test::Target");
        FunctionDefinition fd = (FunctionDefinition) model.getModule("test").getElement("test::f__Any_MANY_");
        FunctionExpression expr = (FunctionExpression) fd._expressionSequence().getFirst();
        
        GenericType targetGT = targetClass._classifierGenericType();
        meta.pure.metamodel.multiplicity.Multiplicity mul = (meta.pure.metamodel.multiplicity.Multiplicity) model.getModule("m3").getElement("meta::pure::metamodel::multiplicity::PureOne");

        ParametersBinding bindings = org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.PlainParametersBinding.empty();
        bindings.typeBindings().put("T", Sets.mutable.with(targetGT));
        bindings.typeBindings().put("Z", Sets.mutable.with(targetGT)); // Should be filtered out
        bindings.multiplicityBindings().put("m", Sets.mutable.with(mul));
        bindings.multiplicityBindings().put("n", Sets.mutable.with(mul)); // Should be filtered out

        _FunctionExpression.populateResolvedParameters(expr, bindings, Sets.mutable.with("T"), Sets.mutable.with("m"), new ScopedMetadataAccess(model.getModule("test"), model));

        assertEquals(1, expr._resolvedTypeParameters().size());
        assertEquals("T", expr._resolvedTypeParameters().getFirst()._name());

        assertEquals(1, expr._resolvedMultiplicityParameters().size());
        assertEquals("m", expr._resolvedMultiplicityParameters().getFirst()._name());
    }
}
