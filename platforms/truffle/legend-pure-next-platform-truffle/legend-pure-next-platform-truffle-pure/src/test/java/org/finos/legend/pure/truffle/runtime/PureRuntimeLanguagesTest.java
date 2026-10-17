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

package org.finos.legend.pure.truffle.runtime;

import org.eclipse.collections.api.factory.Lists;
import org.finos.legend.pure.truffle.compiler.module.ModuleRegistry;
import org.finos.legend.pure.truffle.compiler.module.pdbModule.PdbModule;
import org.finos.legend.pure.truffle.execution.natives.NativeRegistry;
import org.finos.legend.pure.truffle.grammar.Language;
import org.finos.legend.pure.truffle.grammar.PureLanguage;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * ###Pure is never optional, so it is always there: build() puts the always-active languages first unless
 * the host named them — the same contract as the Java platform and the JavaScript builder. Nothing else
 * is filled in: a missing registry or natives fails build() naming the method to call.
 */
public class PureRuntimeLanguagesTest
{
    @Test
    public void thePureLanguageIsAddedByDefault()
    {
        // A host names only the extensions it wants; ###Pure is never optional, so it comes first anyway.
        PureRuntime runtime = PureRuntime.builder().withRegistry(new ModuleRegistry())
                .withNatives(NativeRegistry.createDefault()).build();
        try
        {
            Assertions.assertEquals(PureLanguage.all().collect(Language::name), runtime.languages().collect(Language::name));
        }
        finally
        {
            runtime.close();
        }
    }

    @Test
    public void namingThePureLanguageDoesNotAddItTwice()
    {
        PureRuntime runtime = PureRuntime.builder().withRegistry(new ModuleRegistry())
                .withNatives(NativeRegistry.createDefault()).withLanguages(PureLanguage.all()).build();
        try
        {
            Assertions.assertEquals(PureLanguage.all().collect(Language::name), runtime.languages().collect(Language::name));
        }
        finally
        {
            runtime.close();
        }
    }

    @Test
    public void aMissingRegistryIsNamed()
    {
        IllegalStateException e = Assertions.assertThrows(IllegalStateException.class,
                () -> PureRuntime.builder().withLanguages(PureLanguage.all())
                        .withNatives(NativeRegistry.createDefault()).build());
        Assertions.assertTrue(e.getMessage().contains("withRegistry(...)"), e.getMessage());
    }

    @Test
    public void missingNativesAreNamed()
    {
        IllegalStateException e = Assertions.assertThrows(IllegalStateException.class,
                () -> PureRuntime.builder().withRegistry(new ModuleRegistry())
                        .withLanguages(PureLanguage.all()).build());
        Assertions.assertTrue(e.getMessage().contains("withNatives(...)"), e.getMessage());
    }

    @Test
    public void withoutARootTheHostsModulesAreAllThereIs()
    {
        // A run that needs only core.pdb (the protocol tests): nothing the languages declare is opened
        // behind the host's back.
        ModuleRegistry registry = new ModuleRegistry();
        registry.register(openFromRepo("shared/core.pdb"));
        PureRuntime runtime = PureRuntime.builder().withRegistry(registry)
                .withLanguages(PureLanguage.all()).withNatives(NativeRegistry.createDefault()).build();
        Assertions.assertEquals(Lists.immutable.of("core"),
                Lists.immutable.withAll(registry.modules()).collect(m -> m.name()));
        runtime.close();
    }

    @Test
    public void declaredModulesRegisteredByTheHostNeedNoRoot()
    {
        // What the CLI does with its --pdb list: the modules are there, so there is nothing to open.
        ModuleRegistry registry = new ModuleRegistry();
        PureLanguage.all().flatCollect(Language::pdbs).distinct()
                .forEach(pdb -> registry.register(openFromRepo(pdb)));
        PureRuntime runtime = PureRuntime.builder().withRegistry(registry)
                .withLanguages(PureLanguage.all()).withNatives(NativeRegistry.createDefault()).build();
        runtime.close();
    }

    private static PdbModule openFromRepo(String pdb)
    {
        Path current = Path.of("").toAbsolutePath();
        while (current != null && !Files.exists(current.resolve("shared").resolve("core.pdb")))
        {
            current = current.getParent();
        }
        if (current == null)
        {
            throw new IllegalStateException("Cannot locate the repository (shared/core.pdb)");
        }
        try
        {
            return PdbModule.open(current.resolve(pdb));
        }
        catch (IOException e)
        {
            throw new UncheckedIOException(e);
        }
    }
}
