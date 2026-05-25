// Copyright 2026 Goldman Sachs
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

package org.finos.legend.pure.m3.specification;

import org.eclipse.collections.api.factory.Lists;
import org.finos.legend.pure.next.parser.pureLanguage.PureLanguageParser;
import org.finos.legend.pure.next.parser.topLevel.TopLevelParser;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.stream.Stream;

/**
 * Parameterized grammar (parser-stage) error tests.
 *
 * <p>Counterpart to {@link CompilerErrorTest}: this one tests errors that
 * fire at <em>parse</em> time, before any compiler logic runs. Each test
 * is a directory under {@code pure/specification/grammar/tests/} with two
 * files:</p>
 *
 * <pre>
 *   grammar_error.pure   the broken source (no ###Pure header — content only)
 *   error.txt            the expected parser error message, verbatim snapshot
 * </pre>
 *
 * <p>The runner walks {@code tests/} for any directory containing both
 * files, prepends {@code ###Pure} to the source, parses it via
 * {@link TopLevelParser}, and asserts the thrown exception's message
 * matches {@code error.txt} exactly.</p>
 *
 * <p>To regenerate snapshots after an intentional parser change, run
 * with {@code -Dlegend.pure.generateBaselines=true}.</p>
 *
 * <p>Success-test counterpart: {@code grammar.pure} + {@code protocol.json},
 * exercised by {@code PureToJsonRoundtripTest} in the parser module.</p>
 */
public class GrammarErrorTest
{
    private static final String GRAMMAR_FILE = "grammar_error.pure";
    private static final String ERROR_FILE = "error.txt";

    private static Path locateTestsRoot()
    {
        Path current = Path.of("").toAbsolutePath();
        while (current != null)
        {
            Path candidate = current.resolve("pure").resolve("specification").resolve("grammar").resolve("tests");
            if (Files.isDirectory(candidate))
            {
                return candidate;
            }
            current = current.getParent();
        }
        throw new RuntimeException("Cannot locate pure/specification/grammar/tests from " + Path.of("").toAbsolutePath());
    }

    public static Collection<Arguments> discoverErrorTests() throws IOException
    {
        List<Arguments> tests = new ArrayList<>();
        Path root = locateTestsRoot();
        try (Stream<Path> walk = Files.walk(root))
        {
            walk.filter(Files::isDirectory)
                    .filter(dir -> Files.exists(dir.resolve(GRAMMAR_FILE))
                            && Files.exists(dir.resolve(ERROR_FILE)))
                    .sorted()
                    .forEach(dir ->
                    {
                        String relative = root.relativize(dir).toString().replace('\\', '/');
                        if (!relative.isEmpty())
                        {
                            tests.add(Arguments.of(relative, dir));
                        }
                    });
        }
        return tests;
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("discoverErrorTests")
    public void testGrammarError(final String testName, final Path testDir) throws IOException
    {
        String body = new String(Files.readAllBytes(testDir.resolve(GRAMMAR_FILE)), StandardCharsets.UTF_8);
        Path errorFile = testDir.resolve(ERROR_FILE);

        String pureSource = "###Pure\n" + body;

        RuntimeException ex = Assertions.assertThrows(RuntimeException.class,
                () -> TopLevelParser.parse(pureSource, "testFile", Lists.mutable.with(new PureLanguageParser())),
                "Expected a parse error for " + testName + " but parsing succeeded");

        String actual = ex.getMessage();
        Assertions.assertNotNull(actual, "Expected non-null error message for " + testName);

        if (Boolean.getBoolean("legend.pure.generateBaselines"))
        {
            Files.write(errorFile, (actual + "\n").getBytes(StandardCharsets.UTF_8));
            return;
        }

        String expected = new String(Files.readAllBytes(errorFile), StandardCharsets.UTF_8);
        String expectedTrimmed = expected.endsWith("\n") ? expected.substring(0, expected.length() - 1) : expected;

        Assertions.assertEquals(expectedTrimmed, actual,
                testName + ": error message did not match snapshot.\n"
                + "To regenerate run with -Dlegend.pure.generateBaselines=true");
    }
}
