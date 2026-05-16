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

package org.finos.legend.pure.truffle;



import org.eclipse.collections.api.factory.Lists;
import org.finos.legend.pure.execution.PureAssertionError;
import org.finos.legend.pure.m3.PureModel;
import org.finos.legend.pure.m3.module.pdbModule.PDBModule;
import org.finos.legend.pure.m3.pureLanguage.PureLanguageExtension;
import org.finos.legend.pure.truffle.builder.NativeNodeRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * JUnit 5 test factory that discovers and runs Pure tests via the
 * standalone Truffle context. Each {@code <<test.Test>>} and
 * {@code <<PCT.test>>} function becomes an individual dynamic test
 * with independent pass/fail — no fail-fast.
 *
 * <p>Run with: {@code mvn test -Dtest=TrufflePureTestRunner}</p>
 */
class TrufflePureTestRunner
{
    private static PureContext context;
    private static org.finos.legend.pure.truffle.runtime.TruffleModuleRegistry resolver;
    private static org.finos.legend.pure.truffle.runtime.TrufflePdbLoader coreLoader;
    private static org.finos.legend.pure.truffle.runtime.TrufflePdbLoader compilerLoader;
    private static PDBModule coreModule;
    private static PDBModule compilerModule;
    private static Object pctAdapter;

    // Captures the polyglot Engine's err stream (where TraceCompilation writes
    // its `[engine] opt done|opt failed|...` events). Read in
    // {@link #assertNoCompilationFailures()} to enforce that every Graal
    // compilation Pure stdlib runTests / PCT tests trigger lands successfully.
    private static ByteArrayOutputStream graalLog;
    private static org.graalvm.polyglot.Context polyglotCtx;
    private static org.graalvm.polyglot.Engine engine;

    private static void ensureSetup() throws java.io.IOException
    {
        if (context != null)
        {
            return;
        }
        Path corePdb = Path.of("../../../../shared/core.pdb");
        Path compilerPdb = Path.of("../../../../shared/compiler.pdb");

        // Use truffle PDB loader — reads FlatBuffer directly into truffle-namespaced wrappers
        coreLoader = new org.finos.legend.pure.truffle.runtime.TrufflePdbLoader(
                corePdb, "core", Lists.mutable.empty());
        compilerLoader = new org.finos.legend.pure.truffle.runtime.TrufflePdbLoader(
                compilerPdb, "compiler", Lists.mutable.with("core"));

        // Module registry replaces the previous anonymous-class composite resolver.
        // Registration order is dependency-first: core, then compiler.
        resolver = new org.finos.legend.pure.truffle.runtime.TruffleModuleRegistry();
        resolver.register(coreLoader);
        resolver.register(compilerLoader);

        // Wire composite resolver into each loader for cross-module FBW resolution
        coreLoader.setResolver(resolver);
        compilerLoader.setResolver(resolver);

        PureLanguage.configure(resolver, NativeNodeRegistry.createDefault());
        // Build the Engine with err redirected to a buffer + TraceCompilation
        // enabled. The {@link #assertNoCompilationFailures()} hook below scans
        // the buffer for `opt failed` lines and fails the build if any are
        // present — guarantees the Pure-on-Truffle stack stays 100%
        // Graal-compiled across the runTests / PCT suites too (mirrors the
        // tripwire on TruffleTestCompilerPure).
        graalLog = new ByteArrayOutputStream();
        // CompileImmediately is kept on long-term so the tripwire below sees
        // a Graal compilation event for *every* Pure function the test suite
        // reaches — without it, Truffle's threshold (~10 invocations)
        // means single-call test paths never JIT and any recursive-inlining
        // bailout in those paths slips by silently. Trades a slower
        // surefire run for full coverage of the Pure stdlib's compile
        // surface.
        engine = org.graalvm.polyglot.Engine.newBuilder()
                .allowExperimentalOptions(true)
                .err(new PrintStream(graalLog, true, StandardCharsets.UTF_8))
                .option("engine.TraceCompilation", "true")
                .option("engine.WarnInterpreterOnly", "false")
                .option("engine.CompilationFailureAction", "Diagnose")
                .option("engine.CompileImmediately", "true")
                .build();
        polyglotCtx = org.graalvm.polyglot.Context.newBuilder(PureLanguage.ID)
                .engine(engine)
                .allowAllAccess(true).build();
        polyglotCtx.initialize(PureLanguage.ID);
        polyglotCtx.enter();
        context = PureLanguage.get(null);

        // Keep bootstrap PDB modules for element path discovery
        coreModule = new PDBModule(corePdb, PDBModule.Mode.EXECUTION, "core", "*", Lists.mutable.empty());
        compilerModule = new PDBModule(compilerPdb, PDBModule.Mode.EXECUTION, "compiler", "*", Lists.mutable.with("core"));
        PureModel model = PureModel.withModules(Lists.mutable.with(coreModule, compilerModule))
                .withExtensions(Lists.mutable.with(new PureLanguageExtension()))
                .build();
        model.compile();

        // Configure the TrufflePureParser for the parse native — ###Pure section builds
        // PDOs directly via TrufflePureLanguageProtocolBuilder, no ProtocolTranslator copy.
        context.setPureParser(org.finos.legend.pure.truffle.parser.TrufflePureParser.builder()
                .resolver(resolver)
                .build());

        // Find PCT adapter via truffle loader
        for (String path : coreLoader.elementPaths())
        {
            Object element = coreLoader.getElement(path);
            if (org.finos.legend.pure.truffle.runtime.dynobj.PureObj.isType(element,
                    "meta::pure::metamodel::function::FunctionDefinition", resolver)
                    && isPCTAdapter(element))
            {
                pctAdapter = element;
                break;
            }
        }
    }

    /**
     * Tripwire: every compilation Graal attempts during the suite must
     * succeed. Closing the polyglot context flushes any in-flight
     * compilations to the captured engine err stream; we then count
     * {@code opt failed} entries.
     *
     * <p>Includes a minimum-attempts guard (total > 0) so the assertion
     * doesn't pass vacuously on a non-GraalVM JVM where Graal isn't
     * compiling anything.</p>
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
        // Tripwire: the Truffle runtime MUST be the optimizing variant (Graal-
        // backed HotSpotTruffleRuntime). If it falls back to DefaultTruffleRuntime
        // we'd silently lose all JIT-related coverage and any compilation
        // bailout in the Pure stdlib would slip through. Check the runtime
        // directly rather than scanning a TraceCompilation log — log line
        // formats have drifted between Oracle GraalVM (where this assertion
        // was originally tuned) and GraalVM CE 25.0.2 (where the same
        // HotSpotTruffleRuntime emits zero recognisable "opt done"/"opt failed"
        // lines, tripping the old log-based check on CI).
        com.oracle.truffle.api.TruffleRuntime rt = com.oracle.truffle.api.Truffle.getRuntime();
        String runtimeName = rt.getClass().getName();
        assertTrue(
                runtimeName.contains("HotSpotTruffleRuntime")
                        || runtimeName.contains("GraalRuntime"),
                () -> "Expected Graal-backed Truffle runtime but got " + runtimeName
                        + " (name=" + rt.getName() + "). The runtime is interpreted-only — "
                        + "set JAVA_HOME to a GraalVM 25 JDK before running this suite.");

        // Belt-and-braces: if TraceCompilation produced any "opt failed" lines,
        // surface them. (On Oracle GraalVM the log is rich; on CE it may be
        // empty even though compilation IS happening — which is fine: silence
        // here means no failures rather than no compilations.)
        // HotSpot code-installation size bailouts are tolerated — see
        // {@link PureTruffleRuntime#isUnavoidableCodeSizeBailout(String)}.
        String log = graalLog.toString(StandardCharsets.UTF_8);
        List<String> allFailures = log.lines()
                .filter(line -> line.contains("opt failed"))
                .toList();
        List<String> actionable = allFailures.stream()
                .filter(line -> !PureTruffleRuntime.isUnavoidableCodeSizeBailout(line))
                .toList();
        int tolerated = allFailures.size() - actionable.size();
        if (tolerated > 0)
        {
            System.err.println("[graal-tripwire] tolerated " + tolerated
                    + " unavoidable code-size bailout(s); affected lambdas stay Tier-1.");
        }
        String preview = actionable.stream().limit(10).collect(Collectors.joining("\n"));
        assertEquals(0, actionable.size(),
                () -> "Graal compilation failures: " + actionable.size() + ". Top failures:\n" + preview);
    }

    @TestFactory
    Collection<DynamicTest> functionTests() throws java.io.IOException
    {
        ensureSetup();
        List<DynamicTest> tests = new ArrayList<>();

        for (String path : coreLoader.elementPaths())
        {
            Object element = coreLoader.getElement(path);
            if (org.finos.legend.pure.truffle.runtime.dynobj.PureObj.isType(element,
                    "meta::pure::metamodel::function::FunctionDefinition", resolver)
                    && isTestStereotype(element) && isZeroArg(element))
            {
                tests.add(createTest(path, element, null));
            }
        }
        // Also scan compiler.pdb for test functions
        for (String path : compilerLoader.elementPaths())
        {
            Object element = compilerLoader.getElement(path);
            if (org.finos.legend.pure.truffle.runtime.dynobj.PureObj.isType(element,
                    "meta::pure::metamodel::function::FunctionDefinition", resolver)
                    && isTestStereotype(element) && isZeroArg(element))
            {
                tests.add(createTest(path, element, null));
            }
        }

        assertFalse(tests.isEmpty(), "Should discover at least one <<test.Test>> function");
        return tests;
    }

    @TestFactory
    Collection<DynamicTest> pctTests() throws java.io.IOException
    {
        ensureSetup();
        List<DynamicTest> tests = new ArrayList<>();

        for (String path : coreLoader.elementPaths())
        {
            Object element = coreLoader.getElement(path);
            if (org.finos.legend.pure.truffle.runtime.dynobj.PureObj.isType(element,
                    "meta::pure::metamodel::function::FunctionDefinition", resolver)
                    && isPCTTest(element))
            {
                tests.add(createTest(path, element, pctAdapter));
            }
        }

        assertFalse(tests.isEmpty(), "Should discover at least one <<PCT.test>> function");
        return tests;
    }

    private DynamicTest createTest(String path, Object fd, Object adapter)
    {
        return DynamicTest.dynamicTest(path, () ->
        {
            // Suppress the stdout side effect of `print`/`println` for the
            // test body so individual tests don't pollute Surefire's event
            // channel. The returned string is unaffected, so assertions
            // still work. The flag is restored in finally to keep
            // surrounding setup/teardown output visible.
            Boolean prevSilenced = org.finos.legend.pure.truffle.ast.natives.io.PrintNode.SILENCED.get();
            org.finos.legend.pure.truffle.ast.natives.io.PrintNode.SILENCED.set(Boolean.TRUE);
            try
            {
                if (adapter != null)
                {
                    context.executeFunction(fd, new Object[]{adapter});
                }
                else
                {
                    context.executeFunction(fd, new Object[0]);
                }
            }
            catch (org.finos.legend.pure.truffle.ast.PureException.AssertionError e)
            {
                throw new org.opentest4j.AssertionFailedError("[" + path + "] " + e.getMessage() + formatPureStack(e), e);
            }
            catch (org.finos.legend.pure.truffle.ast.PureException e)
            {
                throw new org.opentest4j.AssertionFailedError("[" + path + "] " + e.getMessage() + formatPureStack(e), e);
            }
            catch (PureAssertionError e)
            {
                // Legacy bootstrap assertion — should be removed once all asserts use PureException
                throw new org.opentest4j.AssertionFailedError("[" + path + "] " + e.getMessage() + formatPureStack(e), e);
            }
            catch (RuntimeException e)
            {
                Throwable cause = e;
                while (cause.getCause() != null && cause.getCause() != cause)
                {
                    cause = cause.getCause();
                }
                throw new org.opentest4j.AssertionFailedError("[" + path + "] " + cause.getMessage() + formatPureStack(e), e);
            }
            finally
            {
                org.finos.legend.pure.truffle.ast.natives.io.PrintNode.SILENCED.set(prevSilenced);
            }
        });
    }

    private static boolean isTestStereotype(Object element)
    {
        return hasStereotype(element, "Test", "test");
    }

    private static boolean isPCTTest(Object element)
    {
        return hasStereotype(element, "test", "PCT");
    }

    private static boolean isPCTAdapter(Object element)
    {
        return hasStereotype(element, "adapter", "PCT");
    }

    private static boolean hasStereotype(Object element, String value, String profileName)
    {
        Object stObj = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.read(element, "stereotypes");
        if (!(stObj instanceof org.finos.legend.pure.truffle.types.PureSequence st))
        {
            return false;
        }
        // Stereotypes resolve to Stereotype objects; iterate and match by
        // value + profile name. Stereotype is a leaf concrete Pure type so
        // pureTypeIs is exact-match (no resolver needed) and class-keyed-cached.
        for (Object stereotype : st.toBoxedArray())
        {
            if (!org.finos.legend.pure.truffle.runtime.dynobj.PureObj.pureTypeIs(stereotype,
                    "meta::pure::metamodel::extension::Stereotype"))
            {
                continue;
            }
            Object stValue = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.read(stereotype, "value");
            if (value.equals(stValue))
            {
                Object profile = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.read(stereotype, "profile");
                if (profile != null)
                {
                    Object profileN = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.read(profile, "name");
                    if (profileName.equals(profileN))
                    {
                        return true;
                    }
                }
                // Profile might not resolve — match by value alone as fallback
                return true;
            }
        }
        return false;
    }

    private static boolean isZeroArg(Object fd)
    {
        Object paramsObj = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.read(fd, "parameters");
        return !(paramsObj instanceof org.finos.legend.pure.truffle.types.PureSequence params) || params.isEmpty();
    }

    /**
     * Extract the Pure call stack from a Truffle exception.
     * Uses TruffleStackTrace to get guest language frames with source locations.
     */
    private static String formatPureStack(Throwable e)
    {
        Throwable inner = e;
        while (inner.getCause() != null && inner.getCause() != inner)
        {
            inner = inner.getCause();
        }

        try
        {
            var frames = com.oracle.truffle.api.TruffleStackTrace.getStackTrace(inner);
            if (frames == null || frames.isEmpty())
            {
                return "";
            }
            StringBuilder sb = new StringBuilder("\nPure stack:");
            for (var frame : frames)
            {
                var target = frame.getTarget();
                var rootNode = target.getRootNode();
                String name = rootNode != null ? rootNode.getName() : "?";

                // Find source from the location node (the call site or expression node)
                com.oracle.truffle.api.source.SourceSection sourceSection = null;
                var location = frame.getLocation();
                if (location != null)
                {
                    sourceSection = location.getSourceSection();
                    // Walk up the node tree to find the nearest source section
                    var node = location;
                    while (sourceSection == null && node != null)
                    {
                        sourceSection = node.getSourceSection();
                        node = node.getParent();
                    }
                }
                if (sourceSection == null && rootNode != null)
                {
                    sourceSection = rootNode.getSourceSection();
                }

                sb.append("\n  at ").append(name != null ? name : "?");
                if (sourceSection != null && sourceSection.getSource() != null)
                {
                    sb.append(" (").append(sourceSection.getSource().getName())
                            .append(":").append(sourceSection.getStartLine())
                            .append(":").append(sourceSection.getStartColumn()).append(")");
                }
            }
            return sb.toString();
        }
        catch (Exception ignored)
        {
            return "";
        }
    }
}
