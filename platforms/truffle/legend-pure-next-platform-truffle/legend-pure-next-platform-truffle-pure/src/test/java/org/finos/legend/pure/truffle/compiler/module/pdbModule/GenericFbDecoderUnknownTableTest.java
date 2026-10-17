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

package org.finos.legend.pure.truffle.compiler.module.pdbModule;

import org.finos.legend.pure.truffle.compiler.module.MetadataAccess;
import org.finos.legend.pure.truffle.compiler.module.TruffleTypeCache;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.Set;

/**
 * A PDB entry whose table neither m3.fbs nor any registered language fragment declares — a ###Diagram
 * element read by a runtime that never registered the diagram language — must fail naming the table,
 * not decode into an object whose every property is empty.
 */
public class GenericFbDecoderUnknownTableTest
{
    @Test
    public void unknownTableFailsNamingIt()
    {
        MetadataAccess nothing = new MetadataAccess()
        {
            @Override
            public Object getElement(String path)
            {
                return null;
            }

            @Override
            public boolean hasElement(String path)
            {
                return false;
            }

            @Override
            public Set<String> elementPaths()
            {
                return Set.of();
            }

            @Override
            public TruffleTypeCache typeCache()
            {
                return null;
            }
        };
        IllegalStateException e = Assertions.assertThrows(IllegalStateException.class,
                () -> GenericFbDecoder.purePathForDef("NoSuchThingDef", nothing));
        Assertions.assertTrue(e.getMessage().contains("NoSuchThingDef"), e.getMessage());
    }

    /**
     * The same when a class of that short name IS loaded (diagram.pdb in a runtime built without the
     * diagram language): the table → class guess succeeds, so it is the missing table that must fail —
     * not a plan with no fields, under which every property reads empty.
     */
    @Test
    public void tableMissingFromTheSchemaFailsNamingIt()
    {
        // The decoder has a schema only once languages are named — what PureRuntime.build() does.
        org.finos.legend.pure.truffle.grammar.PureLanguage.all().forEach(GenericFbDecoder::registerLanguage);
        IllegalStateException e = Assertions.assertThrows(IllegalStateException.class,
                () -> GenericFbDecoder.planFor("NoSuchThingDef"));
        Assertions.assertTrue(e.getMessage().contains("no schema for NoSuchThingDef"), e.getMessage());
    }
}
