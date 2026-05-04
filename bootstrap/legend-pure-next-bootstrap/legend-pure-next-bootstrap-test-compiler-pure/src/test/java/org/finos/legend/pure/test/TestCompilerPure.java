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

package org.finos.legend.pure.test;

import meta.pure.metamodel.PackageableElement;
import meta.pure.metamodel.function.FunctionDefinition;
import org.eclipse.collections.api.factory.Lists;
import org.finos.legend.pure.execution.PureAssertionError;
import org.finos.legend.pure.execution.PureExecution;
import org.finos.legend.pure.m3.PureModel;
import org.finos.legend.pure.m3.extensions.compiledgraph.CompiledGraphLanguageExtension;
import org.finos.legend.pure.m3.extensions.error.ErrorLanguageExtension;
import org.finos.legend.pure.m3.module.ScopedMetadataAccess;
import org.finos.legend.pure.m3.module.bootstrapModule.BootstrapModule;
import org.finos.legend.pure.m3.module.pdbModule.PDBModule;
import org.finos.legend.pure.m3.pureLanguage.PureLanguageExtension;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Runs the {@code specification/compiler} tests using the Pure-written
 * compiler ({@code compiler.pdb}) via the Java execution engine.
 *
 * <p>Each {@code .pure} file under {@code specification/compiler/} that
 * contains a {@code ###CompiledGraph} or {@code ###Error} section becomes
 * an individual JUnit 5 dynamic test. The test invokes
 * {@code meta::pure::compiler::test::assertCompiledGraph(content, sourceId)}
 * from the compiler-pure code through {@link PureExecution}.</p>
 */
class TestCompilerPure
{
    private static final String ASSERT_FN_PATH =
            "meta::pure::compiler::test::assertCompiledGraph_String_1__String_1__Boolean_1_";

    private static PureExecution execution;
    private static FunctionDefinition assertCompiledGraphFn;

    private static synchronized void ensureSetup() throws IOException
    {
        if (execution != null)
        {
            return;
        }

        Path corePdb = BootstrapModule.locateCorePdb();
        Path compilerPdb = BootstrapModule.locateCompilerPdb();

        PDBModule coreModule = new PDBModule(corePdb, PDBModule.Mode.COMPILATION,
                "core", "*", List.of());
        PDBModule compilerModule = new PDBModule(compilerPdb, PDBModule.Mode.COMPILATION,
                "compiler", "*", List.of("core"));

        PureModel model = PureModel.withModules(Lists.mutable.with(coreModule, compilerModule))
                .withExtensions(Lists.mutable.with(new PureLanguageExtension()))
                .build();
        model.compile();

        // Build a resolver that sees both modules
        ScopedMetadataAccess resolver = new ScopedMetadataAccess(compilerModule, model);

        execution = PureExecution.builder()
                .withResolver(resolver)
                .withParserExtensions(List.of(new CompiledGraphLanguageExtension(), new ErrorLanguageExtension()))
                .build();

        // Locate the assertCompiledGraph function
        PackageableElement fn = compilerModule.getElement(ASSERT_FN_PATH);
        if (fn == null)
        {
            fn = coreModule.getElement(ASSERT_FN_PATH);
        }
        assertNotNull(fn, "Should find " + ASSERT_FN_PATH + " in compiler.pdb");
        assertCompiledGraphFn = (FunctionDefinition) fn;
    }

    @TestFactory
    Collection<DynamicTest> compilerTests() throws IOException
    {
        ensureSetup();

        Path testsRoot = locateTestsRoot();
        List<DynamicTest> tests = new ArrayList<>();

        try (Stream<Path> walk = Files.walk(testsRoot))
        {
            walk.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".pure"))
                    .sorted()
                    .forEach(p ->
                    {
                        try
                        {
                            String content = Files.readString(p, StandardCharsets.UTF_8);
                            if (content.contains("###CompiledGraph") || content.contains("###Error"))
                            {
                                String relative = testsRoot.relativize(p)
                                        .toString().replace('\\', '/');
                                String testName = relative.endsWith(".pure")
                                        ? relative.substring(0, relative.length() - 5)
                                        : relative;
                                tests.add(createTest(testName, content));
                            }
                        }
                        catch (IOException e)
                        {
                            throw new RuntimeException(e);
                        }
                    });
        }

        assertFalse(tests.isEmpty(), "Should discover at least one compiler test in " + testsRoot);
        return tests;
    }

    private DynamicTest createTest(String testName, String content)
    {
        return DynamicTest.dynamicTest(testName, () ->
        {
            try
            {
                execution.execute(assertCompiledGraphFn, content, testName);
            }
            catch (PureAssertionError e)
            {
                throw new org.opentest4j.AssertionFailedError(
                        "[" + testName + "] " + e.getMessage(), e);
            }
        });
    }

    private static Path locateTestsRoot()
    {
        Path current = Path.of("").toAbsolutePath();
        while (current != null)
        {
            Path candidate = current.resolve("pure").resolve("specification").resolve("compiler");
            if (Files.isDirectory(candidate))
            {
                return candidate;
            }
            current = current.getParent();
        }
        throw new RuntimeException("Cannot locate pure/specification/compiler by walking up from "
                + Path.of("").toAbsolutePath());
    }
}
