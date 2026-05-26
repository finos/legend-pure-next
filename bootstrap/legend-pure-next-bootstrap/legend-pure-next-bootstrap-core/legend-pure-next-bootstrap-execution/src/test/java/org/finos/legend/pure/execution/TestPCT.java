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
import org.finos.legend.pure.m3.module.ScopedMetadataAccess;
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
        // Load core.pdb (lean) and core-tests.pdb (test elements + their
        // <<test.TestDependency>> model classes) side-by-side. PCT tests live
        // in core.pdb (stereotype <<PCT.test>> on the PCT profile), but their
        // model classes are <<test.TestDependency>> on the test profile and
        // thus live in core-tests.pdb. Both modules must be resolved for the
        // PCT test bodies to find their referenced classes.
        PDBModule coreModule = new PDBModule(
                BootstrapModule.locateCorePdb(),
                PDBModule.Mode.COMPILATION);
        PDBModule coreTestsModule = new PDBModule(
                BootstrapModule.locateCoreTestsPdb(),
                PDBModule.Mode.COMPILATION);

        PureModel model = PureModel.withModules(Lists.mutable.with(coreModule, coreTestsModule))
                .withExtensions(Lists.mutable.with(new PureLanguageExtension()))
                .build();
        model.compile();

        // Execute through the tests module, which has core as a declared
        // dependency in its manifest — so ScopedMetadataAccess resolves
        // <<PCT.adapter>> (in core) and <<test.TestDependency>> (in tests)
        // both transparently.
        PureExecution execution = new PureExecution(new ScopedMetadataAccess(coreTestsModule, model));

        // Both <<PCT.adapter>> and <<PCT.test>> functions live in core-tests.pdb
        // (the TestElementFilter sends the PCT test/adapter stereotypes plus
        // all <<test.*>>-annotated model classes to the tests companion;
        // <<PCT.function>> natives like map/filter stay in lean core.pdb).
        FunctionDefinition adapterFd = null;
        List<DynamicTest> tests = new ArrayList<>();

        for (String path : coreTestsModule.elementPaths())
        {
            PackageableElement element = coreTestsModule.getElement(path);
            if (element instanceof FunctionDefinition fd)
            {
                if (isPCTAdapter(element))
                {
                    adapterFd = fd;
                }
            }
        }

        assertNotNull(adapterFd, "Should find a <<PCT.adapter>> function in core-tests.pdb");
        FunctionDefinition adapter = adapterFd;

        for (String path : coreTestsModule.elementPaths())
        {
            PackageableElement element = coreTestsModule.getElement(path);
            if (element instanceof FunctionDefinition fd && isPCTTest(element))
            {
                tests.add(DynamicTest.dynamicTest(path, () ->
                {
                    Boolean prevSilenced = IONatives.SILENCED.get();
                    IONatives.SILENCED.set(Boolean.TRUE);
                    try
                    {
                        execution.execute(fd, adapter);
                    }
                    catch (PureAssertionError e)
                    {
                        throw new org.opentest4j.AssertionFailedError(e.getMessage(), e);
                    }
                    finally
                    {
                        IONatives.SILENCED.set(prevSilenced);
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
