// Copyright 2024 Goldman Sachs
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
package org.finos.legend.pure.runtime;

import org.eclipse.collections.api.factory.Lists;
import org.finos.legend.pure.execution.InterpretedExecution;
import org.finos.legend.pure.execution.natives.NativesExtension;
import org.finos.legend.pure.m3.module.ModuleRegistry;
import org.finos.legend.pure.m3.module.bootstrapModule.BootstrapModule;
import org.finos.legend.pure.m3.module.pdbModule.PdbModule;
import org.finos.legend.pure.m3.pureLanguage.PureLanguageExtension;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

/**
 * The natives are checked against the loaded modules when an execution is built, by full id: every native a
 * loaded module declares is implemented, and no entry stands in for a function written in Pure.
 */
public class NativesCheckTest
{
    private static ModuleRegistry core() throws IOException
    {
        ModuleRegistry registry = new ModuleRegistry(List.of(new PdbModule(BootstrapModule.locateCorePdb(), PdbModule.Mode.EXECUTION)));
        registry.attach(List.of(new PureLanguageExtension()));
        return registry;
    }

    @Test
    public void everyNativeTheLoadedModulesDeclareIsImplemented() throws IOException
    {
        PureRuntime.builder().withRegistry(core()).build();
    }

    @Test
    public void anEntryForAFunctionWrittenInPureFailsTheBuild() throws IOException
    {
        // assert(Boolean[1]) has a Pure body: no entry may stand in for it, whoever registers it
        String writtenInPure = "platform::pure::functions::asserts::assert_Boolean_1__Boolean_1_";
        NativesExtension override = registry -> registry.register(writtenInPure, (args, eval, genericType, multiplicity) -> null);
        ModuleRegistry registry = core();
        IllegalStateException e = Assertions.assertThrows(IllegalStateException.class,
                () -> PureRuntime.builder().withRegistry(registry)
                        .withExecution(InterpretedExecution.builder().withNativeExtensions(Lists.mutable.with(override)))
                        .build());
        Assertions.assertTrue(e.getMessage().contains("implemented but not natives (a function written in Pure is never replaced): " + writtenInPure), e.getMessage());
    }
}
