// Copyright 2024 Goldman Sachs
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

package org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper;

import meta.pure.metamodel.relation.GenericTypeOperationType;
import meta.pure.metamodel.relation.RelationType;
import meta.pure.metamodel.relation.RelationTypeImpl;
import meta.pure.metamodel.type.generics.GenericType;
import org.eclipse.collections.api.list.MutableList;
import org.eclipse.collections.impl.factory.Lists;
import org.finos.legend.pure.m3.PureModel;
import org.finos.legend.pure.m3.module.CompilationResult;
import org.finos.legend.pure.m3.module.bootstrapModule.BootstrapModule;
import org.finos.legend.pure.m3.module.localModule.LocalModule;
import org.finos.legend.pure.m3.module.ScopedMetadataAccess;
import org.finos.legend.pure.m3.pureLanguage.PureLanguageExtension;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class _GenericTypeOperationTest
{
    private static PureModel model;

    @BeforeAll
    public static void setUp()
    {
        model = PureModel.withModules(
                Lists.mutable.with(new BootstrapModule(),
                        new LocalModule("test", "*", Lists.mutable.with("m3"),
                                Lists.mutable.empty()))
        ).withExtensions(Lists.mutable.with(new PureLanguageExtension())).build();

        CompilationResult result = model.compile();
        assertTrue(result.errors().isEmpty(), "Compilation errors should be empty: " + result.errors());
    }

    @Test
    public void testEvaluateRelationTypeOperation()
    {
        ScopedMetadataAccess metadataAccess = new ScopedMetadataAccess(model.getModule("test"), model);
        
        RelationType rt1 = new RelationTypeImpl();
        GenericType gt1 = _GenericType.buildUserDefinedGenericType((meta.pure.metamodel.type.Type) metadataAccess.getElement("meta::pure::metamodel::relation::RelationType"), metadataAccess);
        rt1._columns(Lists.mutable.with(
            _Column.build("age", gt1, _GenericType.buildUserDefinedGenericType((meta.pure.metamodel.type.Type) metadataAccess.getElement("Integer"), metadataAccess), (meta.pure.metamodel.multiplicity.Multiplicity) metadataAccess.getElement("meta::pure::metamodel::multiplicity::PureOne"), false, metadataAccess)
        ));

        RelationType rt2 = new RelationTypeImpl();
        GenericType gt2 = _GenericType.buildUserDefinedGenericType((meta.pure.metamodel.type.Type) metadataAccess.getElement("meta::pure::metamodel::relation::RelationType"), metadataAccess);
        rt2._columns(Lists.mutable.with(
            _Column.build("name", gt2, _GenericType.buildUserDefinedGenericType((meta.pure.metamodel.type.Type) metadataAccess.getElement("String"), metadataAccess), (meta.pure.metamodel.multiplicity.Multiplicity) metadataAccess.getElement("meta::pure::metamodel::multiplicity::PureOne"), false, metadataAccess)
        ));

        RelationType unionResult = _GenericTypeOperation.evaluateRelationTypeOperation(rt1, rt2, GenericTypeOperationType.Union, metadataAccess);
        assertNotNull(unionResult);
        MutableList<? extends meta.pure.metamodel.relation.Column> unionCols = unionResult._columns().toList();
        assertEquals(2, unionCols.size());
        assertEquals("age", unionCols.get(0)._name());
        assertEquals("name", unionCols.get(1)._name());

        RelationType diffResult = _GenericTypeOperation.evaluateRelationTypeOperation(unionResult, rt2, GenericTypeOperationType.Difference, metadataAccess);
        assertNotNull(diffResult);
        MutableList<? extends meta.pure.metamodel.relation.Column> diffCols = diffResult._columns().toList();
        assertEquals(1, diffCols.size());
        assertEquals("age", diffCols.get(0)._name());
    }
}
