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
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * A language's natives on this platform are checked, when the runtime is built, against what its module
 * declares: a mismatch fails the build naming the natives, and a language whose module is not loaded has
 * its natives installed unchecked (the module may arrive late).
 */
public class PureRuntimeLanguageNativesTest
{
    private static final String UNDECLARED = "probe::notDeclaredAnywhere_Integer_1__Integer_1_";

    private static Language extensionOf(String module)
    {
        return new Language("probe", module, Lists.immutable.empty(), null, null, null, null,
                registry -> registry.register(UNDECLARED, (args, gt, mul, fe) -> null));
    }

    @Test
    public void nativesThatDoNotMatchTheModuleFailTheBuild() throws IOException
    {
        // core's natives are not the probe's, and the probe's one is not core's: both are named
        ModuleRegistry registry = new ModuleRegistry().register(PdbModule.open(sharedPdb("core.pdb")));
        IllegalStateException e = Assertions.assertThrows(IllegalStateException.class,
                () -> PureRuntime.builder().withRegistry(registry).withNatives(NativeRegistry.createDefault())
                        .withLanguages(Lists.immutable.of(extensionOf("core"))).build());
        Assertions.assertTrue(e.getMessage().contains("implemented but not declared: " + UNDECLARED), e.getMessage());
        Assertions.assertTrue(e.getMessage().contains("declared but not implemented: "), e.getMessage());
        // keyed by full id: core's size, named as such
        Assertions.assertTrue(e.getMessage().contains("platform::pure::functions::collection::size_Any_MANY__Integer_1_"), e.getMessage());
    }

    @Test
    public void anEntryForAFunctionWrittenInPureFailsTheBuild() throws IOException
    {
        // assert(Boolean[1]) has a Pure body: no entry may stand in for it, whoever registers it
        String writtenInPure = "platform::pure::functions::asserts::assert_Boolean_1__Boolean_1_";
        ModuleRegistry registry = new ModuleRegistry().register(PdbModule.open(sharedPdb("core.pdb")));
        IllegalStateException e = Assertions.assertThrows(IllegalStateException.class,
                () -> PureRuntime.builder().withRegistry(registry)
                        .withNatives(NativeRegistry.createDefault().register(writtenInPure, (args, gt, mul, fe) -> null))
                        .build());
        Assertions.assertTrue(e.getMessage().contains("implemented but not natives (a function written in Pure is never replaced): " + writtenInPure), e.getMessage());
    }

    @Test
    public void everyNativeTheLoadedModulesDeclareIsImplemented() throws IOException
    {
        // the default natives against core: complete, and nothing else
        ModuleRegistry registry = new ModuleRegistry().register(PdbModule.open(sharedPdb("core.pdb")));
        PureRuntime.builder().withRegistry(registry).withNatives(NativeRegistry.createDefault()).build().close();
    }

    @Test
    public void theNativesOfALanguageWhoseModuleIsNotLoadedAreInstalledUnchecked()
    {
        PureRuntime runtime = PureRuntime.builder().withRegistry(new ModuleRegistry()).withNatives(NativeRegistry.createDefault())
                .withLanguages(Lists.immutable.of(extensionOf("absent"))).build();
        runtime.close();
    }

    private static Path sharedPdb(String name)
    {
        return sharedPdbAbove(Path.of("").toAbsolutePath(), name);
    }

    /** {@code shared/<name>} in {@code dir} or the nearest directory above it. */
    private static Path sharedPdbAbove(Path dir, String name)
    {
        if (dir == null)
        {
            throw new IllegalStateException("no 'shared/" + name + "' above the working directory");
        }
        Path pdb = dir.resolve("shared").resolve(name);
        return Files.exists(pdb) ? pdb : sharedPdbAbove(dir.getParent(), name);
    }
}
