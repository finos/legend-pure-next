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

package org.finos.legend.pure.m3.module.pdbModule.archive;

import org.finos.legend.pure.m3.pureLanguage.PureLanguageExtension;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

/**
 * A PDB holding a language extension's elements (here ###Diagram, which the bootstrap has no class
 * for) still loads: the extension's entries are skipped — no PDBExtension claims them — and the m3
 * entries come back.
 */
public class PdbLoaderUnknownKindTest
{
    @Test
    public void diagramEntriesAreSkipped() throws Exception
    {
        PdbLoader loader = new PdbLoader(repoRoot().resolve("pure/extensions/diagram/tests/compiler-pdb-serialization/diagram/propertyAndAssociation.pdb"));
        // As the compiler sets it up: m3's own element kinds, and nothing for ###Diagram.
        loader.setExtensions(List.of(new PureLanguageExtension()));
        Map<String, ?> loaded = loader.loadAll();
        Assertions.assertTrue(loaded.containsKey("m::Person"), loaded.keySet().toString());
        Assertions.assertFalse(loaded.containsKey("m::D"), loaded.keySet().toString());
    }

    private static Path repoRoot()
    {
        Path p = Paths.get("").toAbsolutePath();
        while (p != null && !Files.isDirectory(p.resolve("pure/extensions/diagram/tests/compiler-pdb-serialization")))
        {
            p = p.getParent();
        }
        Assertions.assertNotNull(p, "repo root not found above " + Paths.get("").toAbsolutePath());
        return p;
    }
}
