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

package org.finos.legend.pure.platform.java.runtime;

import org.finos.legend.pure.platform.java.compiler.module.ModuleRegistry;
import org.finos.legend.pure.platform.java.compiler.module.pdbModule.Metadata;
import org.finos.legend.pure.platform.java.compiler.module.pdbModule.PdbModule;
import org.eclipse.collections.api.factory.Lists;
import org.finos.legend.pure.platform.java.grammar.Language;
import org.finos.legend.pure.platform.java.grammar.PureLanguage;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The same builder contract as Truffle and JavaScript: ###Pure is always there (put first unless named),
 * nothing else is filled in, a missing registry is named,
 * a host's own PDBs are registered in the registry (no {@code withArchives}), and the modules the languages
 * declare still open first — the metadata opens archives in that order, and core.pdb has to lead.
 */
class PureRuntimeBuilderTest
{
    @Test
    void aMissingRegistryIsNamed()
    {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> PureRuntime.builder().withLanguages(PureLanguage.all()).withModuleRoot(repoRoot()).build());
        assertTrue(e.getMessage().contains("withRegistry(...)"), e.getMessage());
    }

    @Test
    void thePureLanguageIsAddedByDefault()
    {
        // A host names only the extensions it wants; ###Pure is never optional, so it comes first anyway.
        PureRuntime runtime = PureRuntime.builder().withRegistry(new ModuleRegistry()).withModuleRoot(repoRoot()).build();
        try
        {
            assertEquals(PureLanguage.all().collect(Language::name), PureRuntime.languages().collect(Language::name));
        }
        finally
        {
            runtime.close();
        }
    }

    @Test
    void anEmptyLanguageListStillGetsPureOnce()
    {
        PureRuntime runtime = PureRuntime.builder().withRegistry(new ModuleRegistry())
                .withLanguages(Lists.immutable.empty()).withModuleRoot(repoRoot()).build();
        try
        {
            assertEquals(PureLanguage.all().collect(Language::name), PureRuntime.languages().collect(Language::name));
            assertSame(runtime, PureRuntime.current());
        }
        finally
        {
            runtime.close();
        }
    }

    @Test
    void declaredModulesOpenBeforeTheHostsOwn() throws Exception
    {
        ModuleRegistry registry = new ModuleRegistry();
        registry.register(PdbModule.open(repoRoot().resolve("pure/extensions/diagram/build/diagram.pdb")));
        PureRuntime runtime = PureRuntime.builder().withRegistry(registry)
                .withLanguages(PureLanguage.all()).withModuleRoot(repoRoot()).build();
        try
        {
            assertEquals("core", Metadata.runtime().moduleNames().iterator().next());
            assertTrue(runtime.compilerExtensions().notEmpty(), "pureExtension() at least");
        }
        finally
        {
            runtime.close();
        }
    }

    @Test
    void currentIsTheLastBuiltRuntime()
    {
        PureRuntime runtime = PureRuntime.builder().withRegistry(new ModuleRegistry())
                .withLanguages(PureLanguage.all()).withModuleRoot(repoRoot()).build();
        try
        {
            assertSame(runtime, PureRuntime.current());
        }
        finally
        {
            runtime.close();
        }
    }

    private static Path repoRoot()
    {
        Path p = Path.of("").toAbsolutePath();
        while (p != null && !Files.isDirectory(p.resolve("pure/testdata/diagram-roundtrip")))
        {
            p = p.getParent();
        }
        assertNotNull(p, "repo root not found above " + Path.of("").toAbsolutePath());
        return p;
    }
}
