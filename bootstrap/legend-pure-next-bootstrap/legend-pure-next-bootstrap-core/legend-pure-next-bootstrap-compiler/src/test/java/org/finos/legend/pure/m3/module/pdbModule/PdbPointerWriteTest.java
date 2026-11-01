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

package org.finos.legend.pure.m3.module.pdbModule;

import com.google.flatbuffers.FlatBufferBuilder;
import org.eclipse.collections.api.factory.Lists;
import org.finos.legend.pure.m3.pureLanguage.GeneratedFlatBufferWriter;
import org.junit.jupiter.api.Test;
import platform.pure.metamodel.extension.StereotypeImpl;
import platform.pure.metamodel.type.EnumImpl;
import platform.pure.metamodel.type.generics.UserDefinedPackageableGenericTypeImpl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * A pointer slot holding what nothing addresses fails the write: writing anything would lose the value, and the
 * reader would never know.
 */
public class PdbPointerWriteTest
{
    @Test
    public void testAPointerToWhatNothingAddressesFailsTheWrite()
    {
        // a stereotype is pointed to as its profile and value: one without a profile has no address
        EnumImpl value = new EnumImpl();
        value._classifierGenericType(new UserDefinedPackageableGenericTypeImpl()._name("Anchor"));
        value._name("A");
        value._stereotypes(Lists.mutable.with(new StereotypeImpl()._value("s")));
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> new GeneratedFlatBufferWriter(new FlatBufferBuilder()).writeEnum(value));
        assertEquals("PDB writer: cannot write a pointer to a platform.pure.metamodel.extension.StereotypeImpl", e.getMessage());
    }
}
