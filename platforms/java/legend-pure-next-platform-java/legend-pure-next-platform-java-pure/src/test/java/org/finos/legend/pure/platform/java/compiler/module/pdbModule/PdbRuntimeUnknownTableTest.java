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

package org.finos.legend.pure.platform.java.compiler.module.pdbModule;

import org.finos.legend.pure.m3.PdbTypes;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * A PDB holding a language extension's elements (###Diagram), opened by a runtime that never got the
 * extension's schema fragment, must fail naming the table — not decode an object whose every property
 * is empty.
 */
public class PdbRuntimeUnknownTableTest
{
    @Test
    public void unknownTableFailsNamingIt() throws Exception
    {
        PdbRuntime runtime = PdbRuntime.open(org.finos.legend.pure.platform.java.grammar.Language.schemaText(
                org.finos.legend.pure.platform.java.grammar.PureLanguage.all(), null), PdbTypes::create, repoRoot().resolve("pure/testdata/diagram-roundtrip/expected/roundtrip.pdb"));
        IllegalStateException e = Assertions.assertThrows(IllegalStateException.class, () -> runtime.element("m::D"));
        Assertions.assertTrue(e.getMessage().contains("no schema for DiagramDef"), e.getMessage());
    }

    private static Path repoRoot()
    {
        Path p = Paths.get("").toAbsolutePath();
        while (p != null && !Files.isDirectory(p.resolve("pure/testdata/diagram-roundtrip")))
        {
            p = p.getParent();
        }
        Assertions.assertNotNull(p, "repo root not found above " + Paths.get("").toAbsolutePath());
        return p;
    }
}
