package org.finos.legend.pure.m3.helper;

import meta.pure.metamodel.function.property.Property;
import meta.pure.metamodel.type.Class;
import meta.pure.metamodel.type.generics.GenericType;
import meta.pure.metamodel.type.generics.GenericTypeValue;
import meta.pure.metamodel.relation.RelationType;
import org.eclipse.collections.api.factory.Lists;
import org.finos.legend.pure.m3.PureModel;
import org.finos.legend.pure.m3.module.CompilationResult;
import org.finos.legend.pure.m3.module.bootstrapModule.BootstrapModule;
import org.finos.legend.pure.m3.module.localModule.LocalModule;
import org.finos.legend.pure.m3.module.localModule.PureContent;
import org.finos.legend.pure.m3.pureLanguage.PureLanguageExtension;
import org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._GenericType;
import org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._RelationType;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import org.finos.legend.pure.m3.module.ScopedMetadataAccess;

public class _RelationTypeTest
{
    private static PureModel model;
    private static Class testClass;

    @BeforeAll
    public static void setUp()
    {
        String source = 
            "Class test::RelClass<T>\n" +
            "{\n" +
            "    emptyRt: meta::pure::metamodel::relation::Relation<Any>[1];\n" +
            "    simpleRt: meta::pure::metamodel::relation::Relation<(col1:String, col2:Integer)>[1];\n" +
            "    wildcardRt: meta::pure::metamodel::relation::Relation<(?:T)>[1];\n" +
            "    subRt: meta::pure::metamodel::relation::Relation<(col1:String, col2:Integer, col3:Float)>[1];\n" +
            "    otherRt: meta::pure::metamodel::relation::Relation<(col2:Integer, col4:Boolean)>[1];\n" +
            "    otherRt2: meta::pure::metamodel::relation::Relation<(col3:Float)>[1];\n" +
            "}\n";

        model = PureModel.withModules(
                Lists.mutable.with(new BootstrapModule(BootstrapModule.locateM3Ttl()),
                        new LocalModule("test", "*", Lists.mutable.with("m3"),
                                Lists.mutable.with(new PureContent(source, "test.pure"))))
        ).withExtensions(Lists.mutable.with(new PureLanguageExtension())).build();

        CompilationResult result = model.compile();
        assertTrue(result.errors().isEmpty(), "Compilation errors should be empty: " + result.errors());

        testClass = (Class) model.getModule("test").getElement("test::RelClass");
    }

    private RelationType getPropRT(String name)
    {
        Property prop = testClass._properties().detect(p -> name.equals(p._name()));
        GenericType gt = prop._genericType();
        GenericType arg = _GenericType.typeArguments(gt).get(0);
        return (RelationType) ((GenericTypeValue) arg)._type();
    }

    @Test
    public void testPrintRelationType()
    {
        RelationType simpleRt = getPropRT("simpleRt");
        Assertions.assertEquals("(col1:String[1], col2:Integer[1])", _RelationType.print(simpleRt, false));
        assertEquals("(col1:String[1], col2:Integer[1])", _RelationType.print(simpleRt, true));
        
        RelationType wildcardRt = getPropRT("wildcardRt");
        assertEquals("(?:T[1])", _RelationType.print(wildcardRt, false));
    }

    @Test
    public void testIsConcreteRelationType()
    {
        RelationType simpleRt = getPropRT("simpleRt");
        assertTrue(_RelationType.isConcrete(simpleRt));

        RelationType wildcardRt = getPropRT("wildcardRt");
        assertFalse(_RelationType.isConcrete(wildcardRt));
    }

    @Test
    public void testCollectReferencedTypeParameterNames()
    {
        RelationType simpleRt = getPropRT("simpleRt");
        assertTrue(_RelationType.collectReferencedTypeParameterNames(simpleRt).isEmpty());

        RelationType wildcardRt = getPropRT("wildcardRt");
        assertEquals(1, _RelationType.collectReferencedTypeParameterNames(wildcardRt).size());
        assertTrue(_RelationType.collectReferencedTypeParameterNames(wildcardRt).contains("T"));
    }

    @Test
    public void testIsCompatibleRelationType()
    {
        RelationType simpleRt = getPropRT("simpleRt");
        RelationType subRt = getPropRT("subRt");
        RelationType otherRt = getPropRT("otherRt");

        ScopedMetadataAccess scopedModel = new ScopedMetadataAccess(model.getModule("test"), model);

        // exact match
        assertTrue(_RelationType.isCompatible(simpleRt, simpleRt, false, scopedModel));
        
        // subRt has (col1:String, col2:Integer, col3:Float), which extends simpleRt horizontally
        assertTrue(_RelationType.isCompatible(simpleRt, subRt, false, scopedModel));
        
        // simpleRt is NOT compatible with subRt because it lacks col3
        assertFalse(_RelationType.isCompatible(subRt, simpleRt, false, scopedModel));

        // otherRt lacks col1, so not compatible
        assertFalse(_RelationType.isCompatible(simpleRt, otherRt, false, scopedModel));
    }

    @Test
    public void testFindCommonRelationType()
    {
        RelationType simpleRt = getPropRT("simpleRt"); // col1:String, col2:Integer
        RelationType otherRt = getPropRT("otherRt");   // col2:Integer, col4:Boolean

        ScopedMetadataAccess scopedModel = new ScopedMetadataAccess(model.getModule("test"), model);

        meta.pure.metamodel.type.Type commonType = _RelationType.findCommonRelationType(Lists.mutable.with(simpleRt, otherRt), scopedModel);
        
        assertTrue(commonType instanceof RelationType);
        RelationType commonRt = (RelationType) commonType;
        assertEquals("(col2:Integer[1])", _RelationType.print(commonRt, false));
        
        // If we intersect two types with nothing in common, should return Any
        RelationType otherRt2 = getPropRT("otherRt2"); // col3
        meta.pure.metamodel.type.Type noCommon = _RelationType.findCommonRelationType(Lists.mutable.with(simpleRt, otherRt2), scopedModel);
        assertEquals("Any", ((meta.pure.metamodel.PackageableElement) noCommon)._name());
    }
}
