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

package org.finos.legend.pure.m3.localModule;

import meta.pure.metamodel.PackageableElement;
import meta.pure.protocol.PureFile;
import org.eclipse.collections.api.factory.Lists;
import org.finos.legend.pure.m3.module.CompilationError;
import org.finos.legend.pure.m3.module.CompilationResult;
import org.finos.legend.pure.m3.module.Module;
import org.finos.legend.pure.m3.PureModel;
import org.finos.legend.pure.m3.localModule.compiledgraph.CompiledGraph;
import org.finos.legend.pure.m3.localModule.compiledgraph.CompiledGraphLanguageExtension;
import org.finos.legend.pure.m3.localModule.compiledgraph.CompiledGraphImpl;
import org.finos.legend.pure.m3.module.localModule.LocalModule;
import org.finos.legend.pure.m3.module.localModule.PureContent;
import org.finos.legend.pure.m3.printer.CompiledGraphPrinter;
import org.finos.legend.pure.m3.module.pdbModule.PDBModule;
import org.finos.legend.pure.m3.pureLanguage.PureLanguageExtension;
import org.finos.legend.pure.next.parser.PureParser;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Parameterized compiled graph tests.
 *
 * <p>Discovers all {@code .pure} files under {@code tests/compiler/}
 * that contain a {@code ###CompiledGraph} section. For each file it:
 * <ol>
 *   <li>Parses the file through {@link PureParser} with
 *       a {@link CompiledGraphLanguageExtension} registered for
 *       {@code ###CompiledGraph} sections</li>
 *   <li>Compiles and asserts no errors</li>
 *   <li>Prints the compiled elements via {@link CompiledGraphPrinter}</li>
 *   <li>Compares to the expected content from the parsed
 *       {@link CompiledGraphImpl} element</li>
 * </ol>
 *
 * <p>Test file format:
 * <pre>
 * {@code
 * ###Pure
 * function pack::greet(name: String[1]): String[1]
 * {
 *   'Hello ' + $name;
 * }
 *
 * ###CompiledGraph
 * function pack::greet(name:String[1]):String[1]
 *   FunctionApplication plus_String_1__String_1__String_1_ :String[1]  @5:3-5:20
 *     AtomicValue :String[1]  'Hello '
 *     Variable name :String[1]
 * }
 * </pre>
 */
public class CompilerCompiledGraphTest
{
    private static final String TESTS_ROOT = "specification/compiler/";
    private static final String SECTION_MARKER = "###CompiledGraph";

    // Tests that compile with intentional errors — skip them here (covered by CompilerErrorTest)
    private static final Set<String> SKIP_TESTS = Set.of();

    public static Collection<Arguments> discoverTests() throws IOException
    {
        List<Arguments> tests = new ArrayList<>();
        discoverTestsRecursive(TESTS_ROOT, tests);
        return tests;
    }

    private static void discoverTestsRecursive(
            String basePath,
            List<Arguments> tests) throws IOException
    {
        ClassLoader cl = CompilerCompiledGraphTest.class.getClassLoader();
        URL baseUrl = cl.getResource(basePath);
        if (baseUrl == null)
        {
            return;
        }

        try
        {
            URI baseUri = baseUrl.toURI();
            Path rootDir;
            FileSystem jarFs = null;

            if ("jar".equals(baseUri.getScheme()))
            {
                String[] parts = baseUri.toString().split("!");
                jarFs = FileSystems.newFileSystem(
                        URI.create(parts[0]),
                        Collections.emptyMap());
                rootDir = jarFs.getPath(parts[1]);
            }
            else
            {
                rootDir = Paths.get(baseUri);
            }

            try (Stream<Path> walk = Files.walk(rootDir))
            {
                walk.filter(Files::isRegularFile)
                        .filter(p -> p.toString().endsWith(".pure"))
                        .sorted()
                        .forEach(p ->
                        {
                            try
                            {
                                String content = Files.readString(p, StandardCharsets.UTF_8);
                                if (content.contains(SECTION_MARKER))
                                {
                                    String relative = rootDir.relativize(p)
                                            .toString().replace('\\', '/');
                                    String testName = relative.endsWith(".pure")
                                            ? relative.substring(0, relative.length() - 5)
                                            : relative;
                                    if (!SKIP_TESTS.contains(testName))
                                    {
                                        tests.add(Arguments.of(testName, basePath + relative));
                                    }
                                }
                            }
                            catch (IOException e)
                            {
                                throw new RuntimeException(e);
                            }
                        });
            }
            finally
            {
                if (jarFs != null)
                {
                    jarFs.close();
                }
            }
        }
        catch (URISyntaxException e)
        {
            throw new IOException("Invalid URI for resource: " + basePath, e);
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("discoverTests")
    public void testCompiledGraph(String testName, String resourcePath) throws Exception
    {
        ClassLoader cl = getClass().getClassLoader();
        String content = loadResource(cl, resourcePath);

        // Parse for assertion extraction (with CompiledGraph section support)
        CompiledGraphLanguageExtension cgExt = new CompiledGraphLanguageExtension();
        PureLanguageExtension pureExt = new PureLanguageExtension();

        PureParser parser = PureParser.builder().withExtensions(Lists.mutable.with(cgExt, pureExt)).build();
        PureFile pureFile = parser.parse(testName, content);

        // Extract expected compiled graph from parsed CompiledGraph elements
        String expectedGraph = pureFile._sections().flatCollect(s -> s._elements())
                .selectInstancesOf(CompiledGraph.class)
                .collect(CompiledGraph::_value)
                .getFirst();

        Assertions.assertNotNull(expectedGraph,
                "Test file must contain a ###CompiledGraph section: " + testName);

        // Strip leading newline and trailing whitespace
        if (expectedGraph.startsWith("\n"))
        {
            expectedGraph = expectedGraph.substring(1);
        }
        expectedGraph = expectedGraph.stripTrailing();

        // Compile (LocalModule parses internally, cgExt provides both grammar + compiler)
        PDBModule module =
                new PDBModule(Path.of("target/core.pdb"),
                        PDBModule.Mode.COMPILATION);

        PureModel model = PureModel.withModules(
                        Lists.mutable.with(new LocalModule("test", "*", Lists.mutable.with(module.getName()),
                                Lists.mutable.with(new PureContent(content, testName))), module))
                .withExtensions(Lists.mutable.with(cgExt, pureExt))
                .build();
        CompilationResult result = model.compile();

        // Assert no compilation errors
        List<String> errors = result.errors().stream()
                .map(CompilationError::message)
                .toList();
        Assertions.assertTrue(errors.isEmpty(),
                "Compilation errors for " + testName + ":\n" + String.join("\n", errors));

        // Collect compiled elements in source order
        List<PackageableElement> compiledElements = new ArrayList<>();
        Module testModule = model.getModule("test");
        pureFile._sections().forEach(section ->
                section._elements().forEach(grammarElement ->
                {
                    if (grammarElement instanceof CompiledGraph)
                    {
                        return; // Skip CompiledGraph elements
                    }
                    String name = grammarElement._name();
                    String packagePath = grammarElement._package() != null
                            ? grammarElement._package()._pointerValue()
                            : null;
                    String fullPath = packagePath != null
                            ? packagePath + "::" + name
                            : name;
                    PackageableElement resolved = testModule.getElement(fullPath);
                    if (resolved != null)
                    {
                        compiledElements.add(resolved);
                    }
                }));

        // Print and compare
        String actualGraph = CompiledGraphPrinter.print(compiledElements).stripTrailing();

        // Baseline generation mode: write actual output back to source file
        if (Boolean.getBoolean("legend.pure.generateBaselines"))
        {
            writeBaseline(resourcePath, actualGraph);
            return;
        }

        Assertions.assertEquals(expectedGraph, actualGraph,
                "CompiledGraph mismatch for " + testName
                        + "\n\nExpected:\n" + expectedGraph
                        + "\n\nActual:\n" + actualGraph);
    }

    private void writeBaseline(String resourcePath, String actualGraph) throws IOException
    {
        // Walk up from cwd to find legend-pure-next root, then locate spec source
        Path cwd = Paths.get(System.getProperty("user.dir"));
        // cwd is typically the compiler module; go up to legend-pure-next root
        Path moduleRoot = cwd;
        while (moduleRoot != null && !Files.exists(moduleRoot.resolve("legend-pure-next-specification")))
        {
            moduleRoot = moduleRoot.getParent();
        }
        if (moduleRoot == null)
        {
            System.err.println("WARNING: Cannot locate legend-pure-next root from: " + cwd);
            // Fallback: try pom.xml detection from parent chain
            return;
        }

        Path sourceFile = moduleRoot
                .resolve("legend-pure-next-specification/src/main/resources")
                .resolve(resourcePath);

        if (!Files.exists(sourceFile))
        {
            System.err.println("WARNING: Source file not found: " + sourceFile);
            return;
        }

        String content = Files.readString(sourceFile, StandardCharsets.UTF_8);
        String marker = "###CompiledGraph\n";
        int idx = content.indexOf(marker);
        if (idx < 0)
        {
            System.err.println("WARNING: No ###CompiledGraph marker in: " + sourceFile);
            return;
        }

        String newContent = content.substring(0, idx + marker.length()) + actualGraph + "\n";
        if (newContent.equals(content))
        {
            return;
        }
        Files.writeString(sourceFile, newContent, StandardCharsets.UTF_8);
        System.out.println("Baseline written: " + sourceFile);
    }

    private String loadResource(ClassLoader cl, String path) throws IOException
    {
        try (InputStream is = cl.getResourceAsStream(path))
        {
            if (is == null)
            {
                throw new IOException("Resource not found: " + path);
            }
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
