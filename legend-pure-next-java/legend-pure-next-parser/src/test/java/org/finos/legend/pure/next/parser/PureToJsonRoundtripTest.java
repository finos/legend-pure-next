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

package org.finos.legend.pure.next.parser;

import org.eclipse.collections.api.factory.Lists;
import org.finos.legend.pure.next.parser.m3.M3ProtocolSerializer;
import org.finos.legend.pure.next.parser.m3.PureLanguageParser;
import org.finos.legend.pure.next.parser.topLevel.TopLevelProtocolBuilder;
import org.finos.legend.pure.next.parser.topLevel.TopLevelProtocolJsonSerializer;
import org.finos.legend.pure.next.parser.topLevel.TopLevelProtocolSerializer;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Parameterized roundtrip tests for Pure to JSON serialization.
 *
 * <p>
 * Discovers all grammar.pure/protocol.json pairs in the test resources,
 * parses the Pure file, serializes to JSON, and semantically compares
 * to expected JSON.
 * </p>
 */
public class PureToJsonRoundtripTest {
    private static final String TESTS_ROOT = "specification/grammar/tests/";
    private static final String GRAMMAR_FILE = "grammar.pure";
    private static final String PROTOCOL_FILE = "protocol.json";
    private static final String GRAMMAR_COMPARE_FILE = "grammar_compare.pure";
    private static final String GRAMMAR_COMPARE_EXPLICIT_FILE = "grammar_compare_explicit.pure";

    /**
     * Discover all test cases from resources.
     *
     * @return stream of test parameters [testName, testPath]
     * @throws IOException if resources cannot be read
     */
    public static Collection<Arguments> discoverTests() throws IOException {
        List<Arguments> tests = new ArrayList<>();
        discoverTestsRecursive(TESTS_ROOT, tests);
        return tests;
    }

    private static void discoverTestsRecursive(
            final String basePath,
            final List<Arguments> tests) throws IOException {
        ClassLoader cl = PureToJsonRoundtripTest.class.getClassLoader();
        URL baseUrl = cl.getResource(basePath);
        if (baseUrl == null) {
            return;
        }

        try {
            java.net.URI baseUri = baseUrl.toURI();
            java.nio.file.Path rootDir;
            java.nio.file.FileSystem jarFs = null;

            if ("jar".equals(baseUri.getScheme())) {
                String[] parts = baseUri.toString().split("!");
                jarFs = java.nio.file.FileSystems.newFileSystem(
                        java.net.URI.create(parts[0]),
                        java.util.Collections.emptyMap());
                rootDir = jarFs.getPath(parts[1]);
            } else {
                rootDir = java.nio.file.Paths.get(baseUri);
            }

            try (java.util.stream.Stream<java.nio.file.Path> walk = java.nio.file.Files.walk(rootDir)) {
                walk.filter(java.nio.file.Files::isDirectory)
                        .filter(dir -> java.nio.file.Files.exists(
                                dir.resolve(GRAMMAR_FILE))
                                && java.nio.file.Files.exists(
                                        dir.resolve(PROTOCOL_FILE)))
                        .sorted()
                        .forEach(dir -> {
                            String relative = rootDir.relativize(dir)
                                    .toString().replace('\\', '/');
                            if (!relative.isEmpty()) {
                                tests.add(Arguments.of(relative,
                                        basePath + relative + "/"));
                            }
                        });
            } finally {
                if (jarFs != null) {
                    jarFs.close();
                }
            }
        } catch (java.net.URISyntaxException e) {
            throw new IOException(
                    "Invalid URI for resource: " + basePath, e);
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("discoverTests")
    public void testRoundtrip(final String testName,
            final String testPath) throws Exception {
        ClassLoader cl = getClass().getClassLoader();

        // Load grammar.pure
        String pureSource = loadResource(cl, testPath + GRAMMAR_FILE);

        // Load expected protocol.json
        String expectedJson = loadResource(cl, testPath + PROTOCOL_FILE);

        // Parse Pure via TopLevelProtocolBuilder
        meta.pure.protocol.PureFile pureFile = TopLevelProtocolBuilder.parse(pureSource, "testFile", Lists.mutable.with(new PureLanguageParser()));
        Assertions.assertFalse(pureFile._sections().isEmpty(),
                "Expected at least one section for " + testName);
        Assertions.assertEquals("Pure", pureFile._sections().get(0)._parserName(),
                "Expected Pure section for " + testName);

        // Serialize to JSON via TopLevelProtocolJsonSerializer
        TopLevelProtocolJsonSerializer jsonSerializer =
                new TopLevelProtocolJsonSerializer();
        String actualJson = jsonSerializer.serialize(pureFile);

        // Parse both JSONs for semantic comparison
        ObjectMapper mapper = jsonSerializer.getMapper();
        JsonNode expected = mapper.readTree(expectedJson);
        JsonNode actual = mapper.readTree(actualJson);

        // Baseline generation mode: write actual output back to source file
        if (Boolean.getBoolean("legend.pure.generateBaselines"))
        {
            writeProtocolBaseline(testPath, actualJson);
        }
        else
        {
            // Skip JSON comparison for placeholder protocol.json files
            if (!expected.isEmpty()) {
                Assertions.assertEquals(
                        expected,
                        actual,
                        "JSON mismatch for " + testName
                                + "\n\nExpected:\n" + mapper.writerWithDefaultPrettyPrinter()
                                        .writeValueAsString(expected)
                                + "\n\nActual:\n" + mapper.writerWithDefaultPrettyPrinter()
                                        .writeValueAsString(actual));
            }
        }

        // Determine which compare file and mode to use
        String explicitCompareResource = testPath + GRAMMAR_COMPARE_EXPLICIT_FILE;
        String minimalCompareResource = testPath + GRAMMAR_COMPARE_FILE;

        final String compareSource;
        final M3ProtocolSerializer.ParenthesisMode mode;

        if (cl.getResource(explicitCompareResource) != null)
        {
            compareSource = loadResource(cl, explicitCompareResource);
            mode = M3ProtocolSerializer.ParenthesisMode.EXPLICIT;
        }
        else if (cl.getResource(minimalCompareResource) != null)
        {
            compareSource = loadResource(cl, minimalCompareResource);
            mode = M3ProtocolSerializer.ParenthesisMode.MINIMAL;
        }
        else
        {
            compareSource = pureSource;
            mode = M3ProtocolSerializer.ParenthesisMode.MINIMAL;
        }

        // Pure roundtrip: serialize PureFile back to Pure
        TopLevelProtocolSerializer pureSerializer =
                new TopLevelProtocolSerializer(mode);
        String actualPure = pureSerializer.serialize(pureFile);

        // Normalize both for comparison (remove extra whitespace)
        String normalizedExpected = normalizePure(compareSource);
        String normalizedOutput = normalizePure(actualPure);

        Assertions.assertEquals(
                normalizedExpected,
                normalizedOutput,
                "Pure roundtrip mismatch for " + testName
                        + "\n\nExpected (normalized):\n" + normalizedExpected
                        + "\n\nSerialized (normalized):\n" + normalizedOutput);
    }

    /**
     * Normalize Pure code for comparison.
     * Removes comments, normalizes whitespace, and trims each line.
     */
    private String normalizePure(final String pure) {
        StringBuilder sb = new StringBuilder();
        for (String line : pure.split("\n")) {
            // Remove line comments
            int commentIdx = line.indexOf("//");
            String cleanLine = commentIdx >= 0
                    ? line.substring(0, commentIdx)
                    : line;

            // Trim and normalize whitespace
            cleanLine = cleanLine.trim();

            if (!cleanLine.isEmpty()) {
                sb.append(cleanLine).append("\n");
            }
        }
        return sb.toString().trim();
    }

    private String loadResource(
            final ClassLoader cl,
            final String path) throws IOException {
        try (InputStream is = cl.getResourceAsStream(path)) {
            if (is == null) {
                throw new IOException("Resource not found: " + path);
            }
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private void writeProtocolBaseline(String testPath, String actualJson) throws IOException
    {
        Path cwd = Paths.get(System.getProperty("user.dir"));
        Path moduleRoot = cwd;
        while (moduleRoot != null && !Files.exists(moduleRoot.resolve("legend-pure-next-specification")))
        {
            moduleRoot = moduleRoot.getParent();
        }
        if (moduleRoot != null)
        {
            Path target = moduleRoot.resolve("legend-pure-next-specification/src/main/resources")
                    .resolve(testPath + PROTOCOL_FILE);
            Files.writeString(target, actualJson);
            System.out.println("Updated baseline: " + target);
        }
    }
}
