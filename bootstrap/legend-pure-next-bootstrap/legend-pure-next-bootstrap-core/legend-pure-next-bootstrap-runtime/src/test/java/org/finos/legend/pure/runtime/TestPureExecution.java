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

package org.finos.legend.pure.runtime;

import org.finos.legend.pure.execution.PureAssertionError;

import meta.pure.metamodel.PackageableElement;
import meta.pure.metamodel.extension.Stereotype;
import meta.pure.metamodel.function.FunctionDefinition;
import org.eclipse.collections.api.factory.Lists;
import org.finos.legend.pure.execution.natives.io.IONatives;
import org.finos.legend.pure.m3.module.bootstrapModule.BootstrapModule;
import org.finos.legend.pure.m3.module.pdbModule.PdbModule;
import org.finos.legend.pure.m3.pureLanguage.PureLanguageExtension;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Tests for the Pure execution engine.
 *
 * <p>Each Pure function annotated with {@code <<test.Test>>} in the core PDB
 * is surfaced as an individual JUnit 5 dynamic test, allowing per-test
 * pass/fail reporting in the IDE and build output.</p>
 */
class TestPureExecution
{
    @TestFactory
    Collection<DynamicTest> pureTestsFromCorePdb() throws IOException
    {
        // <<test.Test>> functions live in core-tests.pdb (its companion), not
        // core.pdb (lean). Load both so the resolver sees the test elements
        // alongside their non-test dependencies.
        PdbModule coreModule = new PdbModule(
                BootstrapModule.locateCorePdb(),
                PdbModule.Mode.COMPILATION);
        PdbModule coreTestsModule = new PdbModule(
                BootstrapModule.locateCoreTestsPdb(),
                PdbModule.Mode.COMPILATION);

        // Attach the modules to a registry (required for getElement to work)
        org.finos.legend.pure.m3.module.ModuleRegistry registry = new org.finos.legend.pure.m3.module.ModuleRegistry(java.util.List.of(coreModule, coreTestsModule));
        registry.attach(java.util.List.of(new PureLanguageExtension()));

        // Resolve through the tests module (it declares core as a dependency
        // in its manifest) so both pdbs are visible during execution.
        PureRuntime execution = PureRuntime.builder()
                .withRegistry(new org.finos.legend.pure.m3.module.ScopedMetadataAccess(coreTestsModule, registry))
                .build();

        List<DynamicTest> tests = new ArrayList<>();
        for (String path : coreTestsModule.elementPaths())
        {
            PackageableElement element = coreTestsModule.getElement(path);
            if (element instanceof FunctionDefinition fd && isTestFunction(element))
            {
                tests.add(DynamicTest.dynamicTest(path, () ->
                {
                    Boolean prevSilenced = IONatives.SILENCED.get();
                    IONatives.SILENCED.set(Boolean.TRUE);
                    try
                    {
                        execution.execute(fd);
                    }
                    catch (PureAssertionError e)
                    {
                        // Pure assertion failures → JUnit failures (not errors)
                        throw new org.opentest4j.AssertionFailedError(e.getMessage(), e);
                    }
                    finally
                    {
                        IONatives.SILENCED.set(prevSilenced);
                    }
                }));
            }
        }

        assertFalse(tests.isEmpty(), "Should discover at least one Pure test function");
        return tests;
    }

    private static boolean isTestFunction(PackageableElement element)
    {
        if (element instanceof meta.pure.metamodel.extension.ElementWithStereotypes ews)
        {
            for (Stereotype s : ews._stereotypes())
            {
                if (s != null && "Test".equals(s._value()) && "test".equals(s._profile()._name()))
                {
                    return true;
                }
            }
        }
        return false;
    }
}
