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

import meta.pure.protocol.PureFile;
import org.eclipse.collections.api.factory.Lists;
import org.finos.legend.pure.m3.module.CompilationError;
import org.finos.legend.pure.m3.module.CompilationResult;
import org.finos.legend.pure.m3.PureModel;
import org.finos.legend.pure.m3.localModule.error.Error;
import org.finos.legend.pure.m3.localModule.error.ErrorLanguageExtension;
import org.finos.legend.pure.m3.module.localModule.LocalModule;
import org.finos.legend.pure.m3.module.localModule.PureContent;
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
import java.util.stream.Stream;

/**
 * Parameterized compiler error tests.
 *
 * <p>Discovers all {@code .pure} files under {@code tests/compiler/}
 * that contain a {@code ###Error} section, parses the entire file
 * through the {@link PureParser}, compiles it, and
 * asserts the compiled model contains errors matching the expected
 * error message from the {@code ###Error} section.</p>
 *
 * <p>Test file format:</p>
 * <pre>
 * ###Error
 * The type 'X' can't be found in property 'y' in class 'pack::MyClass' (at 4:11-4:20)
 *
 * ###Pure
 * import pack::*;
 * Class pack::MyClass { ... }
 * </pre>
 */
public class CompilerErrorTest
{
    private static final String TESTS_ROOT = "specification/compiler/";

    public static Collection<Arguments> discoverErrorTests() throws IOException
    {
        List<Arguments> tests = new ArrayList<>();
        discoverErrorTestsRecursive(TESTS_ROOT, tests);
        return tests;
    }

    private static void discoverErrorTestsRecursive(
            String basePath,
            List<Arguments> tests) throws IOException
    {
        ClassLoader cl = CompilerErrorTest.class.getClassLoader();
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
                                if (content.contains("###Error"))
                                {
                                    String relative = rootDir.relativize(p)
                                            .toString().replace('\\', '/');
                                    String testName = relative.endsWith(".pure")
                                            ? relative.substring(0, relative.length() - 5)
                                            : relative;
                                    tests.add(Arguments.of(testName, basePath + relative));
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
    @MethodSource("discoverErrorTests")
    public void testCompilerError(String testName, String resourcePath) throws Exception
    {
        ClassLoader cl = getClass().getClassLoader();
        String content = loadResource(cl, resourcePath);

        // Parse for assertion extraction (with Error section support)
        ErrorLanguageExtension errExt = new ErrorLanguageExtension();
        PureLanguageExtension pureExt = new PureLanguageExtension();

        PureParser parser = PureParser.builder().withExtensions(Lists.mutable.with(errExt, pureExt)).build();
        PureFile pureFile = parser.parse(testName, content);

        // Extract expected errors from Error elements.
        // Each error block is separated by a blank line.
        // Multi-line errors (e.g. with suggestion lines) are preserved.
        String rawExpected = pureFile._sections().flatCollect(s -> s._elements())
                .selectInstancesOf(Error.class)
                .collect(Error::_value)
                .getFirst();

        Assertions.assertNotNull(rawExpected,
                "Test file must contain a ###Error section: " + testName);

        // Parse expected errors: each non-indented line starts a new error.
        // Lines starting with whitespace continue the previous error (multi-line).
        List<String> expectedErrors = new ArrayList<>();
        for (String line : rawExpected.split("\n"))
        {
            if (line.isEmpty())
            {
                continue;
            }
            if (!line.isEmpty() && !Character.isWhitespace(line.charAt(0)))
            {
                // New error
                expectedErrors.add(line);
            }
            else if (!expectedErrors.isEmpty())
            {
                // Continuation of the previous error
                int last = expectedErrors.size() - 1;
                expectedErrors.set(last, expectedErrors.get(last) + "\n" + line);
            }
        }

        Assertions.assertFalse(expectedErrors.isEmpty(),
                "###Error section must contain at least one error line: " + testName);

        // Compile (LocalModule parses internally, errExt provides both grammar + compiler)
        PDBModule module =
                new PDBModule(Path.of("target/core.pdb"),
                        PDBModule.Mode.COMPILATION);

        CompilationResult result = PureModel.withModules(
                Lists.mutable.with(new LocalModule("test", "*", Lists.mutable.with(module.getName()),
                        Lists.mutable.with(new PureContent(content, testName))), module))
                .withExtensions(Lists.mutable.with(errExt, pureExt)).build().compile();

        Assertions.assertFalse(result.errors().isEmpty(),
                "Expected compilation errors for: " + testName);

        List<String> actualErrors = result.errors().stream()
                .map(CompilationError::message)
                .toList();

        // Baseline generation mode: write actual errors back to source file
        if (Boolean.getBoolean("legend.pure.generateBaselines"))
        {
            writeBaseline(resourcePath, actualErrors);
            return;
        }

        Assertions.assertEquals(expectedErrors.size(), actualErrors.size(),
                "Error count mismatch for: " + testName
                        + "\nExpected:\n" + String.join("\n\n", expectedErrors)
                        + "\nActual:\n" + String.join("\n\n", actualErrors));

        for (int i = 0; i < expectedErrors.size(); i++)
        {
            Assertions.assertEquals(expectedErrors.get(i), actualErrors.get(i),
                    "Error #" + (i + 1) + " mismatch for: " + testName);
        }
    }

    private void writeBaseline(String resourcePath, List<String> actualErrors) throws IOException
    {
        Path cwd = Paths.get(System.getProperty("user.dir"));
        Path moduleRoot = cwd;
        while (moduleRoot != null && !Files.exists(moduleRoot.resolve("legend-pure-next-specification")))
        {
            moduleRoot = moduleRoot.getParent();
        }
        if (moduleRoot == null)
        {
            System.err.println("WARNING: Cannot locate legend-pure-next root from: " + cwd);
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
        // Find ###Error section and replace its content
        int errorStart = content.indexOf("###Error\n");
        if (errorStart < 0)
        {
            System.err.println("WARNING: No ###Error marker in: " + sourceFile);
            return;
        }
        int errorContentStart = errorStart + "###Error\n".length();

        // Find the next ### section or end of file.
        // Try \n### first; if not found, look for ### right after the error content
        // (some files have no newline between the error and the next section).
        int nextSection = content.indexOf("\n###", errorContentStart);
        if (nextSection < 0)
        {
            nextSection = content.indexOf("###", errorContentStart);
        }

        // Extract existing error content and compare with actual errors
        String existingErrors = nextSection >= 0
                ? content.substring(errorContentStart, nextSection).trim()
                : content.substring(errorContentStart).trim();
        String newErrors = String.join("\n", actualErrors).trim();
        if (existingErrors.equals(newErrors))
        {
            return;
        }

        String before = content.substring(0, errorContentStart);
        String after = nextSection >= 0
                ? (content.charAt(nextSection) == '\n' ? content.substring(nextSection) : "\n" + content.substring(nextSection))
                : "";

        String newContent = before + String.join("\n", actualErrors) + "\n" + after;
        Files.writeString(sourceFile, newContent, StandardCharsets.UTF_8);
        System.out.println("Error baseline written: " + sourceFile);
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
