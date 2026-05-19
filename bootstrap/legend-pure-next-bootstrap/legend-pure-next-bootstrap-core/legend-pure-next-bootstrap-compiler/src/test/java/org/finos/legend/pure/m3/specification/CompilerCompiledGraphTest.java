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

package org.finos.legend.pure.m3.specification;

import meta.pure.metamodel.PackageableElement;
import meta.pure.protocol.PureFile;
import org.eclipse.collections.api.factory.Lists;
import org.finos.legend.pure.m3.PureModel;
import org.finos.legend.pure.m3.extensions.compiledgraph.CompiledGraph;
import org.finos.legend.pure.m3.extensions.compiledgraph.CompiledGraphImpl;
import org.finos.legend.pure.m3.extensions.compiledgraph.CompiledGraphLanguageExtension;
import org.finos.legend.pure.m3.extensions.compilerstats.CompilerStatsLanguageExtension;
import org.finos.legend.pure.m3.extensions.testfile.TestFileLanguageExtension;
import org.finos.legend.pure.m3.module.CompilationError;
import org.finos.legend.pure.m3.module.CompilationResult;
import org.finos.legend.pure.m3.module.Module;
import org.finos.legend.pure.m3.module.bootstrapModule.BootstrapModule;
import org.finos.legend.pure.m3.module.localModule.LocalModule;
import org.finos.legend.pure.m3.module.localModule.PureContent;
import org.finos.legend.pure.m3.module.pdbModule.PDBModule;
import org.finos.legend.pure.m3.printer.CompiledGraphPrinter;
import org.finos.legend.pure.m3.pureLanguage.PureLanguageExtension;
import org.finos.legend.pure.next.parser.PureParser;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
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
    private static final String SECTION_MARKER = "###CompiledGraph";

    // Tests that compile with intentional errors — skip them here (covered by CompilerErrorTest)
    private static final Set<String> SKIP_TESTS = Set.of();

    /**
     * Walk up from cwd until we find the {@code specification/compiler/tests/} directory.
     */
    private static Path locateTestsRoot()
    {
        Path current = Path.of("").toAbsolutePath();
        while (current != null)
        {
            Path candidate = current.resolve("pure").resolve("specification").resolve("compiler").resolve("tests");
            if (Files.isDirectory(candidate))
            {
                return candidate;
            }
            current = current.getParent();
        }
        throw new RuntimeException("Cannot locate pure/specification/compiler/tests by walking up from " + Path.of("").toAbsolutePath());
    }

    /**
     * Every {@code .pure} file under the compiler tests tree must declare
     * either a {@code ###CompiledGraph} section (success spec) or a
     * {@code ###Error} section (compile-error spec). A file with neither
     * is silently skipped by both runners — exactly the failure mode we
     * want to prevent. Fails the build with the full list of offenders
     * so they can be annotated or removed in one pass.
     */
    @org.junit.jupiter.api.Test
    public void everyTestFileMustDeclareCompiledGraphOrError() throws IOException
    {
        Path rootDir = locateTestsRoot();
        List<String> offenders = new ArrayList<>();
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
                            if (!content.contains(SECTION_MARKER) && !content.contains("###Error"))
                            {
                                offenders.add(rootDir.relativize(p).toString().replace('\\', '/'));
                            }
                        }
                        catch (IOException e)
                        {
                            throw new RuntimeException(e);
                        }
                    });
        }
        Assertions.assertTrue(offenders.isEmpty(),
                "The following test files are missing a ###CompiledGraph or ###Error section "
                        + "(would be silently skipped by the runners):\n  "
                        + String.join("\n  ", offenders));
    }

    public static Collection<Arguments> discoverTests() throws IOException
    {
        List<Arguments> tests = new ArrayList<>();
        Path rootDir = locateTestsRoot();
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
                                    // Pass absolute filesystem path; loadResource falls through to it
                                    tests.add(Arguments.of(testName, p.toString()));
                                }
                            }
                        }
                        catch (IOException e)
                        {
                            throw new RuntimeException(e);
                        }
                    });
        }
        return tests;
    }

    private static final SpecTestRuntime RUNTIME = new SpecTestRuntime();

    @ParameterizedTest(name = "{0}")
    @MethodSource("discoverTests")
    public void testCompiledGraph(String testName, String resourcePath) throws Exception
    {
        ClassLoader cl = getClass().getClassLoader();
        String content = loadResource(cl, resourcePath);

        CompiledSpec spec = RUNTIME.compileSpec(content, testName);

        // Extract expected compiled graph from parsed CompiledGraph elements
        String expectedGraph = spec.primary()._sections().flatCollect(s -> s._elements())
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

        // Assert no compilation errors
        List<String> errors = spec.result().errors().stream()
                .map(CompilationError::message)
                .toList();
        Assertions.assertTrue(errors.isEmpty(),
                "Compilation errors for " + testName + ":\n" + String.join("\n", errors));

        // Print and compare
        String actualGraph = CompiledGraphPrinter.print(spec.compiledElementsInDeclarationOrder()).stripTrailing();

        // Baseline generation mode: write actual output back to source file.
        // Always also emits a ###ReverseIndex section so every success test
        // carries the recorded reverse index alongside its compiled graph —
        // makes the test the canonical fixture for both signals.
        String actualReverseIndex = formatReverseIndex(spec.result().referencedBy());
        if (Boolean.getBoolean("legend.pure.generateBaselines"))
        {
            writeBaseline(resourcePath, actualGraph, actualReverseIndex);
            return;
        }

        Assertions.assertEquals(expectedGraph, actualGraph,
                "CompiledGraph mismatch for " + testName
                        + "\n\nExpected:\n" + expectedGraph
                        + "\n\nActual:\n" + actualGraph);

        // ###ReverseIndex is REQUIRED on every success test — the reverse
        // index is part of the canonical test fixture. Missing section
        // fails the test (re-run with -Dlegend.pure.generateBaselines=true
        // to backfill). Empty sections are valid: parser's dispatchSection
        // produces zero elements for an empty content body, so detect
        // section presence via {@code Section._parserName()} instead of
        // looking for a parsed {@code ReverseIndex} element.
        boolean hasReverseIndexSection = spec.primary()._sections()
                .anySatisfy(s -> "ReverseIndex".equals(s._parserName()));
        Assertions.assertTrue(hasReverseIndexSection,
                "Test file must contain a ###ReverseIndex section: " + testName
                        + " (run with -Dlegend.pure.generateBaselines=true to backfill)");
        String expectedRevIndex = spec.primary()._sections().flatCollect(s -> s._elements())
                .selectInstancesOf(org.finos.legend.pure.m3.extensions.reverseindex.ReverseIndex.class)
                .collect(org.finos.legend.pure.m3.extensions.reverseindex.ReverseIndex::_value)
                .getFirst();
        if (expectedRevIndex == null) expectedRevIndex = ""; // empty section
        String actualRevIndex = formatReverseIndex(spec.result().referencedBy());
        String normalizedExpected = normalizeReverseIndex(expectedRevIndex);
        Assertions.assertEquals(normalizedExpected, actualRevIndex,
                "ReverseIndex mismatch for " + testName
                        + "\n\nExpected:\n" + normalizedExpected
                        + "\n\nActual:\n" + actualRevIndex);
    }

    /**
     * Render the reverse reference index as deterministic text. Targets
     * appear in sorted order each followed by their sorted, two-space-
     * indented callers. Matches the reverse-index shape (target → callers):
     * <pre>
     *   target_path:
     *     caller_path_1
     *     caller_path_2
     *   other_target:
     *     caller_path_1
     * </pre>
     */
    private static String formatReverseIndex(java.util.Map<String, java.util.Set<String>> index)
    {
        java.util.List<String> targets = new java.util.ArrayList<>(index.keySet());
        java.util.Collections.sort(targets);
        StringBuilder sb = new StringBuilder();
        for (String target : targets)
        {
            sb.append(target).append(':').append('\n');
            java.util.List<String> callers = new java.util.ArrayList<>(index.get(target));
            java.util.Collections.sort(callers);
            for (String caller : callers)
            {
                sb.append("  ").append(caller).append('\n');
            }
        }
        // Trim final newline so equality with stripped expected works
        int len = sb.length();
        while (len > 0 && sb.charAt(len - 1) == '\n')
        {
            len--;
        }
        sb.setLength(len);
        return sb.toString();
    }

    /**
     * Parse the user-authored {@code ###ReverseIndex} text into the same
     * grouped/sorted shape as {@link #formatReverseIndex}. Lines ending
     * in {@code :} declare a target; subsequent indented lines are its
     * callers. Blank lines tolerated. Re-rendering ensures the expected
     * matches the formatter's exact output even when authored loosely.
     */
    private static String normalizeReverseIndex(String raw)
    {
        java.util.LinkedHashMap<String, java.util.Set<String>> parsed = new java.util.LinkedHashMap<>();
        String currentTarget = null;
        for (String line : raw.split("\n"))
        {
            if (line.trim().isEmpty())
            {
                continue;
            }
            if (!Character.isWhitespace(line.charAt(0)))
            {
                String t = line.trim();
                if (t.endsWith(":"))
                {
                    currentTarget = t.substring(0, t.length() - 1);
                    parsed.computeIfAbsent(currentTarget, k -> new java.util.LinkedHashSet<>());
                }
            }
            else if (currentTarget != null)
            {
                parsed.get(currentTarget).add(line.trim());
            }
        }
        return formatReverseIndex(parsed);
    }

    private void writeBaseline(String resourcePath, String actualGraph, String actualReverseIndex) throws IOException
    {
        // Walk up from cwd to find legend-pure-next root, then locate spec source
        Path cwd = Paths.get(System.getProperty("user.dir"));
        // cwd is typically the compiler module; go up to legend-pure-next root
        Path moduleRoot = cwd;
        while (moduleRoot != null && !Files.exists(moduleRoot.resolve("pure").resolve("specification")))
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
                .resolve("pure")
                .resolve("specification")
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

        // Build the new file: keep everything up to and including the
        // `###CompiledGraph` marker, append the actual graph, then append a
        // `###ReverseIndex` section when there's anything to record. We
        // intentionally wipe anything that followed the marker — same
        // semantics as before, just extended for the new section.
        StringBuilder sb = new StringBuilder();
        sb.append(content, 0, idx + marker.length());
        sb.append(actualGraph).append('\n');
        // Always emit ###ReverseIndex (even when empty) so the test contract
        // "section must be present" is upheld for every success test. An
        // empty body still represents a valid, deliberately-empty index.
        sb.append('\n').append("###ReverseIndex").append('\n');
        if (actualReverseIndex != null && !actualReverseIndex.isEmpty())
        {
            sb.append(actualReverseIndex).append('\n');
        }
        String newContent = sb.toString();
        if (newContent.equals(content))
        {
            return;
        }
        Files.writeString(sourceFile, newContent, StandardCharsets.UTF_8);
        System.out.println("Baseline written: " + sourceFile);
    }

    private String loadResource(ClassLoader cl, String path) throws IOException
    {
        Path fsPath = Path.of(path);
        if (Files.exists(fsPath))
        {
            return Files.readString(fsPath, StandardCharsets.UTF_8);
        }
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
