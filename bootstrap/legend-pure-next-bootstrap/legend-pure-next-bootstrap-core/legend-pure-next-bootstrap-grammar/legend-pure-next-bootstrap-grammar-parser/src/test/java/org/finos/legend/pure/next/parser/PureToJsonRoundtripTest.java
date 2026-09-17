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
import org.finos.legend.pure.next.parser.pureLanguage.PureLanguageSerializer;
import org.finos.legend.pure.next.parser.pureLanguage.PureLanguageParser;
import org.finos.legend.pure.next.parser.topLevel.TopLevelParser;
import org.finos.legend.pure.next.parser.topLevel.TopLevelProtocolJsonSerializer;
import org.finos.legend.pure.next.parser.topLevel.TopLevelSerializer;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
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
    private static final String GRAMMAR_FILE = "grammar.pure";
    private static final String PROTOCOL_FILE = "protocol.json";
    private static final String GRAMMAR_COMPARE_FILE = "grammar_compare.pure";
    private static final String GRAMMAR_COMPARE_EXPLICIT_FILE = "grammar_compare_explicit.pure";

    /**
     * Walk up from cwd until we find the project's {@code pure/specification/} directory.
     */
    private static Path locateTestsRoot() {
        Path current = Path.of("").toAbsolutePath();
        while (current != null) {
            Path candidate = current.resolve("pure").resolve("specification").resolve("grammar").resolve("tests");
            if (Files.isDirectory(candidate)) {
                return candidate;
            }
            current = current.getParent();
        }
        throw new RuntimeException("Cannot locate pure/specification/grammar/tests by walking up from " + Path.of("").toAbsolutePath());
    }

    /**
     * Discover all test cases from the filesystem.
     *
     * @return stream of test parameters [testName, testPath]
     * @throws IOException if the tests directory cannot be walked
     */
    public static Collection<Arguments> discoverTests() throws IOException {
        List<Arguments> tests = new ArrayList<>();
        Path rootDir = locateTestsRoot();
        try (java.util.stream.Stream<Path> walk = Files.walk(rootDir)) {
            walk.filter(Files::isDirectory)
                    .filter(dir -> Files.exists(dir.resolve(GRAMMAR_FILE))
                            && Files.exists(dir.resolve(PROTOCOL_FILE)))
                    .sorted()
                    .forEach(dir -> {
                        String relative = rootDir.relativize(dir).toString().replace('\\', '/');
                        if (!relative.isEmpty()) {
                            // testPath is the absolute directory path with a trailing slash
                            tests.add(Arguments.of(relative, dir.toString() + "/"));
                        }
                    });
        }
        return tests;
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
        meta.pure.protocol.PureFile pureFile = TopLevelParser.parse(pureSource, "testFile", Lists.mutable.with(new PureLanguageParser()));
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
        final PureLanguageSerializer.ParenthesisMode mode;

        if (resourceExists(cl, explicitCompareResource))
        {
            compareSource = loadResource(cl, explicitCompareResource);
            mode = PureLanguageSerializer.ParenthesisMode.EXPLICIT;
        }
        else if (resourceExists(cl, minimalCompareResource))
        {
            compareSource = loadResource(cl, minimalCompareResource);
            mode = PureLanguageSerializer.ParenthesisMode.MINIMAL;
        }
        else
        {
            compareSource = pureSource;
            mode = PureLanguageSerializer.ParenthesisMode.MINIMAL;
        }

        // Pure roundtrip: serialize PureFile back to Pure
        TopLevelSerializer pureSerializer =
                new TopLevelSerializer(mode);
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
        Path fsPath = Path.of(path);
        if (Files.exists(fsPath)) {
            return Files.readString(fsPath, StandardCharsets.UTF_8);
        }
        try (InputStream is = cl.getResourceAsStream(path)) {
            if (is == null) {
                throw new IOException("Resource not found: " + path);
            }
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private boolean resourceExists(final ClassLoader cl, final String path) {
        return Files.exists(Path.of(path)) || cl.getResource(path) != null;
    }

    private void writeProtocolBaseline(String testPath, String actualJson) throws IOException
    {
        Path cwd = Paths.get(System.getProperty("user.dir"));
        Path moduleRoot = cwd;
        while (moduleRoot != null && !Files.exists(moduleRoot.resolve("pure").resolve("specification")))
        {
            moduleRoot = moduleRoot.getParent();
        }
        if (moduleRoot != null)
        {
            Path target = moduleRoot.resolve("pure").resolve("specification")
                    .resolve(testPath + PROTOCOL_FILE);
            Files.writeString(target, actualJson);
            System.out.println("Updated baseline: " + target);
        }
    }
}
