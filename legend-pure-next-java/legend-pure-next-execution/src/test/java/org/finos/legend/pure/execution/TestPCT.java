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
import org.finos.legend.pure.m3.PureModel;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * PCT (Pure Compatibility Test) runner for the in-memory execution engine.
 *
 * <p>Discovers all Pure functions annotated with {@code <<PCT.test>>} from
 * the compiled core.pdb and executes each one by passing the in-memory
 * adapter function ({@code testAdapterForInMemoryExecution}) as argument.</p>
 *
 * <p>The adapter simply calls {@code $f->eval()}, which makes the interpreter
 * execute the test expression directly. Each PCT test is surfaced as an
 * individual JUnit 5 dynamic test for per-test pass/fail reporting.</p>
 */
class TestPCT
{
    @TestFactory
    Collection<DynamicTest> pctTests() throws IOException
    {
        PDBModule coreModule = new PDBModule(
                Path.of("../legend-pure-next-compiler/target/core.pdb"),
                PDBModule.Mode.COMPILATION);

        PureModel.withModules(Lists.mutable.with(coreModule))
                .withExtensions(Lists.mutable.with(new PureLanguageExtension()))
                .build()
                .compile();

        PureExecution execution = new PureExecution(coreModule);

        // Find the in-memory PCT adapter by scanning for <<PCT.adapter>> stereotype
        FunctionDefinition adapterFd = null;
        List<DynamicTest> tests = new ArrayList<>();

        for (String path : coreModule.elementPaths())
        {
            PackageableElement element = coreModule.getElement(path);
            if (element instanceof FunctionDefinition fd)
            {
                if (isPCTAdapter(element))
                {
                    adapterFd = fd;
                }
            }
        }

        assertNotNull(adapterFd, "Should find a <<PCT.adapter>> function in core.pdb");
        FunctionDefinition adapter = adapterFd;

        for (String path : coreModule.elementPaths())
        {
            PackageableElement element = coreModule.getElement(path);
            if (element instanceof FunctionDefinition fd && isPCTTest(element))
            {
                tests.add(DynamicTest.dynamicTest(path, () ->
                {
                    try
                    {
                        execution.execute(fd, adapter);
                    }
                    catch (PureAssertionError e)
                    {
                        throw new org.opentest4j.AssertionFailedError(e.getMessage(), e);
                    }
                }));
            }
        }

        assertFalse(tests.isEmpty(), "Should discover at least one PCT test function");
        return tests;
    }

    /**
     * Check if an element has the {@code <<PCT.test>>} stereotype.
     */
    private static boolean isPCTTest(PackageableElement element)
    {
        if (element instanceof meta.pure.metamodel.extension.ElementWithStereotypes ews)
        {
            for (Stereotype s : ews._stereotypes())
            {
                if (s != null && "test".equals(s._value()) && "PCT".equals(s._profile()._name()))
                {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Check if an element has the {@code <<PCT.adapter>>} stereotype.
     */
    private static boolean isPCTAdapter(PackageableElement element)
    {
        if (element instanceof meta.pure.metamodel.extension.ElementWithStereotypes ews)
        {
            for (Stereotype s : ews._stereotypes())
            {
                if (s != null && "adapter".equals(s._value()) && "PCT".equals(s._profile()._name()))
                {
                    return true;
                }
            }
        }
        return false;
    }
}
