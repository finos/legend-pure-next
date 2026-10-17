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

package org.finos.legend.pure.platform.java.grammar;

import org.eclipse.collections.api.factory.Lists;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The PDB schema is the languages' own: every installed language's {@code fbs}, the always-active ones
 * (m3, from {@code ###Pure}) first. Nothing reads m3.fbs from a fixed resource path any more.
 */
class LanguageSchemaTest
{
    private static Language extension(String name, String fbs)
    {
        return new Language(name, name, Lists.immutable.empty(), null, null, null, fbs, null);
    }

    @Test
    void thePureManifestCarriesM3sSchema() throws Exception
    {
        assertEquals(Files.readString(repoRoot().resolve("shared/specification/m3.fbs")),
                Language.schemaText(PureLanguage.all(), null),
                "###Pure's `fbs` is m3.fbs, and the test sections add none");
    }

    @Test
    void m3IsNoLongerAHardWiredResource()
    {
        assertNull(LanguageSchemaTest.class.getClassLoader().getResource("generated-specification/m3.fbs"),
                "the schema reaches the platform through language_pure.json, not a fixed resource path");
    }

    @Test
    void extensionsFollowTheAlwaysActiveLanguages() throws Exception
    {
        String text = Language.schemaText(Lists.immutable.of(extension("x", "table XDef {}")).newWithAll(PureLanguage.all()), null);
        assertTrue(text.startsWith(PureLanguage.all().get(0).fbsText()), "m3 first even when listed last");
        assertTrue(text.endsWith("\ntable XDef {}"), text.substring(Math.max(0, text.length() - 40)));
    }

    @Test
    void anExplicitSchemaReplacesTheAlwaysActiveLanguages(@TempDir Path dir) throws Exception
    {
        Path override = Files.writeString(dir.resolve("other.fbs"), "table ADef {}");
        assertEquals("table ADef {}\ntable XDef {}",
                Language.schemaText(PureLanguage.all().newWith(extension("x", "table XDef {}")), override));
    }

    @Test
    void noAlwaysActiveSchemaIsAnError()
    {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> Language.schemaText(Lists.immutable.of(extension("x", "table XDef {}")), null));
        assertTrue(e.getMessage().contains("PureLanguage.all()"), e.getMessage());
    }

    private static Path repoRoot()
    {
        Path p = Paths.get("").toAbsolutePath();
        while (p != null && !Files.isRegularFile(p.resolve("shared/specification/m3.fbs")))
        {
            p = p.getParent();
        }
        assertNotNull(p, "repo root not found above " + Paths.get("").toAbsolutePath());
        return p;
    }
}
