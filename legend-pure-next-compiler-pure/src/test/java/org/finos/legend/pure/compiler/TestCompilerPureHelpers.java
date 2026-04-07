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

package org.finos.legend.pure.compiler;

import meta.pure.metamodel.PackageableElement;
import meta.pure.metamodel.extension.Stereotype;
import meta.pure.metamodel.function.FunctionDefinition;
import org.eclipse.collections.api.factory.Lists;
import org.finos.legend.pure.execution.PureAssertionError;
import org.finos.legend.pure.execution.PureExecution;
import org.finos.legend.pure.m3.PureModel;
import org.finos.legend.pure.m3.module.ScopedMetadataAccess;
import org.finos.legend.pure.m3.module.localModule.LocalModule;
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
 * Discovers and runs all Pure functions annotated with {@code <<test.Test>>}
 * from the compiler Pure helpers (compiled against core.pdb).
 */
class TestCompilerPureHelpers
{
    @TestFactory
    Collection<DynamicTest> pureHelperTests() throws IOException
    {
        // Load core.pdb (contains bootstrap + standard library)
        PDBModule coreModule = new PDBModule(
                Path.of("../legend-pure-next-java/legend-pure-next-compiler/target/core.pdb"),
                PDBModule.Mode.COMPILATION);

        // Compile compiler Pure files against core.pdb
        LocalModule compilerModule = new LocalModule("compiler", "*",
                Lists.mutable.with(coreModule.getName()),
                Path.of("src/main/resources"));

        PureModel model = PureModel.withModules(Lists.mutable.with(coreModule, compilerModule))
                .withExtensions(Lists.mutable.with(new PureLanguageExtension()))
                .build();
        model.compile();

        PureExecution execution = PureExecution.builder()
                .withResolver(new ScopedMetadataAccess(compilerModule, model))
                .withNativeExtensions(Lists.mutable.with(new TestCompilerNatives()))
                .build();

        List<DynamicTest> tests = new ArrayList<>();
        for (String path : compilerModule.elementPaths())
        {
            PackageableElement element = compilerModule.getElement(path);
            if (element instanceof FunctionDefinition fd && isTestFunction(element))
            {
                tests.add(DynamicTest.dynamicTest(path, () ->
                {
                    try
                    {
                        execution.execute(fd);
                    }
                    catch (PureAssertionError e)
                    {
                        throw new org.opentest4j.AssertionFailedError(e.getMessage(), e);
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
