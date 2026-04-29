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

package org.finos.legend.pure.m3;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * Tripwire that walks every {@code src/test/java} tree across the repo and
 * fails if any class with JUnit annotations would be silently skipped by
 * surefire's include pattern.
 *
 * <p>Why this exists: surefire's defaults ({@code Test*}, {@code *Test},
 * {@code *Tests}, {@code *TestCase}) drop classes named e.g.
 * {@code FooTestRunner} or {@code FooSuite}. We had a 674-test class hidden
 * for months because of this. The parent POMs widen the include pattern to
 * {@code **}<!---->{@code /*Test*.java}; this test enforces that any future
 * test class also matches that pattern.</p>
 *
 * <p>Lives in the bootstrap reactor but walks the entire repo, so a misnamed
 * test in the truffle reactor still fails this build (assuming you run
 * bootstrap before truffle, which the dependency order requires anyway).</p>
 */
class SurefireDiscoveryTest
{
    /** Surefire <include> pattern from each parent POM, as a Java regex. */
    private static final Pattern SUREFIRE_INCLUDE = Pattern.compile(".*Test.*\\.java");

    /** Test annotations whose presence means a class participates in test execution. */
    private static final Pattern TEST_ANNOTATION = Pattern.compile(
            "@(Test|TestFactory|ParameterizedTest|RepeatedTest|TestTemplate|Nested)\\b");

    @Test
    void noTestAnnotatedClassIsHiddenFromSurefire() throws Exception
    {
        Path repoRoot = locateRepoRoot();
        List<String> mismatches = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(repoRoot))
        {
            walk
                    .filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> p.toString().contains("/src/test/java/"))
                    .filter(p -> !p.toString().contains("/target/"))
                    .forEach(p ->
                    {
                        try
                        {
                            String src = Files.readString(p, StandardCharsets.UTF_8);
                            if (TEST_ANNOTATION.matcher(src).find()
                                    && !SUREFIRE_INCLUDE.matcher(p.getFileName().toString()).matches())
                            {
                                mismatches.add(repoRoot.relativize(p).toString());
                            }
                        }
                        catch (java.io.IOException ignored)
                        {
                            // Unreadable file — skip; not our problem to diagnose.
                        }
                    });
        }
        if (!mismatches.isEmpty())
        {
            fail("Found " + mismatches.size() + " test class(es) with JUnit annotations whose file name "
                    + "does NOT match the surefire <include> pattern '" + SUREFIRE_INCLUDE.pattern() + "'. "
                    + "These would be silently skipped by `mvn test`. Either rename the file to contain 'Test' "
                    + "in its name, or update <includes> in the relevant pom.xml:\n  "
                    + String.join("\n  ", mismatches));
        }
    }

    /**
     * Walk upward from the current working directory until we find the repo
     * root marker (a {@code specification/} directory). This way the test runs
     * unchanged from any module's working directory.
     */
    private static Path locateRepoRoot()
    {
        Path current = Path.of("").toAbsolutePath();
        while (current != null)
        {
            if (Files.isDirectory(current.resolve("specification"))
                    && Files.isDirectory(current.resolve("bootstrap")))
            {
                return current;
            }
            current = current.getParent();
        }
        throw new RuntimeException("Cannot locate repo root from " + Path.of("").toAbsolutePath()
                + " (looking for a parent containing both 'specification/' and 'bootstrap/')");
    }
}
