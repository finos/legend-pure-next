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

package org.finos.legend.pure.truffle.test;

import org.finos.legend.pure.truffle.PureContext;
import org.finos.legend.pure.truffle.builder.NativeNodeRegistry;
import org.finos.legend.pure.truffle.runtime.TruffleMetadataAccess;
import org.finos.legend.pure.truffle.runtime.TruffleModuleRegistry;
import org.finos.legend.pure.truffle.runtime.TrufflePdbLoader;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Runs the {@code specification/compiler} tests using the Pure-written
 * compiler ({@code compiler.pdb}) via the Truffle context.
 */
class TruffleTestCompilerPure
{
    private static final String ASSERT_FN_PATH =
            "meta::pure::compiler::test::assertCompiledGraph_String_1__String_1__Boolean_1_";

    private static PureContext context;
    private static Object assertCompiledGraphFn;

    // Captures the polyglot Engine's err stream (where TraceCompilation writes
    // its `[engine] opt done|opt failed|...` events). Read in {@link
    // #assertNoCompilationFailures()} to enforce 100% Graal compilation.
    private static ByteArrayOutputStream graalLog;
    private static org.graalvm.polyglot.Context polyglotCtx;
    private static org.graalvm.polyglot.Engine engine;

    private static synchronized void ensureSetup() throws IOException
    {
        if (context != null)
        {
            return;
        }

        Path corePdb = extractClasspathResource("core.pdb");
        Path compilerPdb = extractClasspathResource("compiler.pdb");

        TrufflePdbLoader coreLoader = new TrufflePdbLoader(corePdb, "core", List.of());
        TrufflePdbLoader compilerLoader = new TrufflePdbLoader(compilerPdb, "compiler", List.of("core"));

        TruffleModuleRegistry resolver = new TruffleModuleRegistry();
        resolver.register(coreLoader);
        resolver.register(compilerLoader);

        coreLoader.setResolver(resolver);
        compilerLoader.setResolver(resolver);
        coreLoader.preloadAll();
        compilerLoader.preloadAll();

        org.finos.legend.pure.truffle.PureLanguage.configure(resolver, NativeNodeRegistry.createDefault());

        // Build the Engine with err redirected to a buffer + TraceCompilation
        // enabled. The {@link #assertNoCompilationFailures()} hook below scans
        // the buffer for `opt failed` lines and fails the build if any are
        // present — guarantees the Pure-on-Truffle stack stays 100%
        // Graal-compiled.
        graalLog = new ByteArrayOutputStream();
        engine = org.graalvm.polyglot.Engine.newBuilder()
                .err(new PrintStream(graalLog, true, StandardCharsets.UTF_8))
                .option("engine.TraceCompilation", "true")
                .option("engine.WarnInterpreterOnly", "false")
                .option("engine.CompilationFailureAction", "Silent")
                .build();
        polyglotCtx = org.graalvm.polyglot.Context.newBuilder(
                org.finos.legend.pure.truffle.PureLanguage.ID)
                .engine(engine)
                .allowAllAccess(true).build();
        polyglotCtx.initialize(org.finos.legend.pure.truffle.PureLanguage.ID);
        polyglotCtx.enter();
        context = org.finos.legend.pure.truffle.PureLanguage.get(null);

        // Configure the PureParser with CompiledGraph and Error section support
        List<org.finos.legend.pure.next.parser.ParserExtension> parserExts = new ArrayList<>();
        parserExts.add(new org.finos.legend.pure.next.parser.m3.PureLanguageParser());
        parserExts.add(new org.finos.legend.pure.truffle.runtime.TruffleCompiledGraphLanguageExtension());
        parserExts.add(new org.finos.legend.pure.m3.extensions.error.ErrorLanguageExtension());
        context.setPureParser(org.finos.legend.pure.next.parser.PureParser.builder()
                .withExtensions(parserExts)
                .build());

        Object fn = resolver.getElement(ASSERT_FN_PATH);
        assertNotNull(fn, "Should find " + ASSERT_FN_PATH + " in compiler.pdb");
        assertCompiledGraphFn = fn;
    }

    /**
     * Tripwire: every compilation Graal attempts during the suite must
     * succeed. Closing the polyglot context flushes any in-flight
     * compilations to the captured engine err stream; we then count
     * {@code opt failed} entries.
     *
     * <p>If this fails, run with {@code
     * -Dpolyglot.engine.CompilationFailureAction=Diagnose} (the maven
     * argLine wires this through {@code ${argLine}}) and inspect the
     * {@code graal_dumps/} directory for the offending root node's IR
     * graph and inlined-method histogram.</p>
     */
    @AfterAll
    static void assertNoCompilationFailures()
    {
        if (polyglotCtx != null)
        {
            polyglotCtx.leave();
            polyglotCtx.close();
        }
        if (engine != null)
        {
            engine.close();
        }
        if (graalLog == null)
        {
            return;
        }
        String log = graalLog.toString(StandardCharsets.UTF_8);
        List<String> failures = log.lines()
                .filter(line -> line.contains("opt failed"))
                .toList();
        long total = log.lines().filter(line -> line.contains("opt done")
                || line.contains("opt failed")).count();
        String preview = failures.stream().limit(10).collect(Collectors.joining("\n"));
        assertEquals(0, failures.size(),
                () -> "Graal compilation failures: " + failures.size() + " of " + total
                        + " attempts. Top failures:\n" + preview);
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
                context.executeFunction(
                        (org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.function.FunctionDefinition) assertCompiledGraphFn,
                        new Object[]{content, testName});
            }
            catch (org.finos.legend.pure.truffle.ast.PureException.AssertionError e)
            {
                throw new org.opentest4j.AssertionFailedError(
                        "[" + testName + "] " + e.getMessage(), e);
            }
            catch (org.finos.legend.pure.truffle.ast.PureException e)
            {
                throw new org.opentest4j.AssertionFailedError(
                        "[" + testName + "] " + e.getMessage() + formatPureStack(e), e);
            }
            catch (RuntimeException e)
            {
                Throwable cause = e;
                while (cause.getCause() != null && cause.getCause() != cause)
                {
                    cause = cause.getCause();
                }
                throw new org.opentest4j.AssertionFailedError(
                        "[" + testName + "] " + cause.getMessage(), e);
            }
        });
    }

    private static String formatPureStack(Throwable e)
    {
        StringBuilder sb = new StringBuilder();
        try
        {
            var frames = com.oracle.truffle.api.TruffleStackTrace.getStackTrace(e);
            if (frames != null)
            {
                for (var frame : frames)
                {
                    var target = frame.getTarget();
                    var rootNode = target.getRootNode();
                    if (rootNode != null)
                    {
                        String name = rootNode.getName();
                        var src = rootNode.getSourceSection();
                        String loc = src != null ? " (" + src.getSource().getName() + ":" + src.getStartLine() + ":" + src.getStartColumn() + ")" : "";
                        sb.append("\n  at ").append(name != null ? name : "<unknown>").append(loc);
                    }
                }
            }
        }
        catch (Exception ignored)
        {
        }
        return sb.length() > 0 ? "\nPure stack:" + sb : "";
    }

    private static Path locateTestsRoot()
    {
        Path current = Path.of("").toAbsolutePath();
        while (current != null)
        {
            Path candidate = current.resolve("specification").resolve("compiler");
            if (Files.isDirectory(candidate))
            {
                return candidate;
            }
            current = current.getParent();
        }
        throw new RuntimeException("Cannot locate specification/compiler by walking up from "
                + Path.of("").toAbsolutePath());
    }

    private static Path extractClasspathResource(String name) throws IOException
    {
        try (InputStream in = TruffleTestCompilerPure.class.getClassLoader().getResourceAsStream(name))
        {
            if (in == null)
            {
                throw new RuntimeException("Resource '" + name + "' not found on classpath");
            }
            Path temp = Files.createTempFile("pure-", "-" + name);
            temp.toFile().deleteOnExit();
            Files.copy(in, temp, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            return temp;
        }
    }
}
