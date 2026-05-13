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

package org.finos.legend.pure.execution;

import meta.pure.metamodel.PackageableElement;
import meta.pure.metamodel.extension.Stereotype;
import meta.pure.metamodel.function.FunctionDefinition;
import org.eclipse.collections.api.factory.Lists;
import org.finos.legend.pure.execution.natives.io.IONatives;
import org.finos.legend.pure.m3.PureModel;
import org.finos.legend.pure.m3.module.bootstrapModule.BootstrapModule;
import org.finos.legend.pure.m3.module.pdbModule.PDBModule;
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
        PDBModule coreModule = new PDBModule(
                BootstrapModule.locateCorePdb(),
                PDBModule.Mode.COMPILATION);

        // Build model to initialize the resolver (required for getElement to work)
        PureModel.withModules(Lists.mutable.with(coreModule))
                .withExtensions(Lists.mutable.with(new PureLanguageExtension()))
                .build()
                .compile();

        PureExecution execution = new PureExecution(coreModule);

        List<DynamicTest> tests = new ArrayList<>();
        for (String path : coreModule.elementPaths())
        {
            PackageableElement element = coreModule.getElement(path);
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
