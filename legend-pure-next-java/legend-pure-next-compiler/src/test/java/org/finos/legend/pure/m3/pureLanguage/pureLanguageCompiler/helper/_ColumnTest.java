package org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper;

import meta.pure.metamodel.multiplicity.Multiplicity;
import meta.pure.metamodel.relation.Column;
import meta.pure.metamodel.type.Type;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class _ColumnTest
{
    private static PureModel model;

    @BeforeAll
    public static void setUp()
    {
        model = PureModel.withModules(
                Lists.mutable.with(new BootstrapModule(),
                        new LocalModule("test", "*", Lists.mutable.with("m3"), Lists.mutable.empty()))
        ).withExtensions(Lists.mutable.with(new PureLanguageExtension())).build();

        CompilationResult result = model.compile();
        assertTrue(result.errors().isEmpty(), "Compilation errors should be empty: " + result.errors());
    }

    @Test
    public void testBuildColumn()
    {
        ScopedMetadataAccess metadataAccess = new ScopedMetadataAccess(model.getModule("test"), model);
        
        GenericType ownerGT = _GenericType.buildUserDefinedGenericType((Type) metadataAccess.getElement("String"), metadataAccess);
        GenericType valueGT = _GenericType.buildUserDefinedGenericType((Type) metadataAccess.getElement("Integer"), metadataAccess);
        Multiplicity pureOne = (Multiplicity) metadataAccess.getElement("meta::pure::metamodel::multiplicity::PureOne");

        Column col = _Column.build("age", ownerGT, valueGT, pureOne, false, metadataAccess);

        assertNotNull(col);
        assertEquals("age", col._name());
        assertFalse(col._nameWildCard());
        assertEquals(valueGT, col._genericType());
        assertEquals(pureOne, col._multiplicity());
        
        GenericType cgt = col._classifierGenericType();
        assertEquals("Column", ((meta.pure.metamodel.PackageableElement) _GenericType.type(cgt))._name());
        assertEquals(2, _GenericType.typeArguments(cgt).size());
        assertEquals(ownerGT, _GenericType.typeArguments(cgt).getFirst());
        assertEquals(valueGT, _GenericType.typeArguments(cgt).getLast());
        
        assertEquals(1, _GenericType.multiplicityArguments(cgt).size());
        assertEquals(pureOne, _GenericType.multiplicityArguments(cgt).getFirst());
    }
}
