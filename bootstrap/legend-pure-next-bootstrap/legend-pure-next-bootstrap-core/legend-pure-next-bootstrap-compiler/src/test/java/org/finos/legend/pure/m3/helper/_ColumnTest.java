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

import platform.pure.metamodel.multiplicity.Multiplicity;
import platform.pure.metamodel.relation.Column;
import platform.pure.metamodel.type.Type;
import platform.pure.metamodel.type.generics.GenericType;
import org.eclipse.collections.api.factory.Lists;
import org.finos.legend.pure.m3.JavaCompiler;
import org.finos.legend.pure.m3.module.CompilationResult;
import org.finos.legend.pure.m3.module.ScopedMetadataAccess;
import org.finos.legend.pure.m3.module.bootstrapModule.BootstrapModule;
import org.finos.legend.pure.m3.module.sourceModule.SourceModule;
import org.finos.legend.pure.m3.pureLanguage.PureLanguageExtension;
import org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._Column;
import org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._GenericType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class _ColumnTest
{
    private static JavaCompiler model;

    @BeforeAll
    public static void setUp()
    {
        model = JavaCompiler.withModules(
                Lists.mutable.with(new BootstrapModule(BootstrapModule.locateM3Ttl()),
                        new SourceModule("test", "*", Lists.mutable.with("m3"), Lists.mutable.empty()))
        ).withExtensions(Lists.mutable.with(new PureLanguageExtension())).build();

        CompilationResult result = model.compile();
        assertTrue(result.errors().isEmpty(), "Compilation errors should be empty: " + result.errors());
    }

    @Test
    public void testBuildColumn()
    {
        ScopedMetadataAccess metadataAccess = new ScopedMetadataAccess(model.registry().module("test"), model.registry());
        
        GenericType ownerGT = _GenericType.buildUserDefinedGenericType((Type) metadataAccess.getElement("String"), metadataAccess);
        GenericType valueGT = _GenericType.buildUserDefinedGenericType((Type) metadataAccess.getElement("Integer"), metadataAccess);
        Multiplicity pureOne = (Multiplicity) metadataAccess.getElement("platform::pure::metamodel::multiplicity::PureOne");

        Column col = _Column.build("age", ownerGT, valueGT, pureOne, false, metadataAccess);

        assertNotNull(col);
        assertEquals("age", col._name());
        assertFalse(col._nameWildCard());
        assertEquals(valueGT, col._genericType());
        assertEquals(pureOne, col._multiplicity());
        
        GenericType cgt = col._classifierGenericType();
        assertEquals("Column", ((platform.pure.metamodel.PackageableElement) _GenericType.type(cgt))._name());
        assertEquals(2, _GenericType.typeArguments(cgt).size());
        assertEquals(ownerGT, _GenericType.typeArguments(cgt).getFirst());
        assertEquals(valueGT, _GenericType.typeArguments(cgt).getLast());
        
        assertEquals(1, _GenericType.multiplicityArguments(cgt).size());
        assertEquals(pureOne, _GenericType.multiplicityArguments(cgt).getFirst());
    }
}
