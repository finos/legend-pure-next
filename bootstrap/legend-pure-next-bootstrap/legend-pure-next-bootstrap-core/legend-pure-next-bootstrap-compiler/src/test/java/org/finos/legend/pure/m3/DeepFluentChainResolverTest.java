// Copyright 2026 Goldman Sachs
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0

package org.finos.legend.pure.m3;

import org.eclipse.collections.api.factory.Lists;
import org.finos.legend.pure.m3.module.CompilationResult;
import org.finos.legend.pure.m3.module.bootstrapModule.BootstrapModule;
import org.finos.legend.pure.m3.module.localModule.LocalModule;
import org.finos.legend.pure.m3.module.localModule.PureContent;
import org.finos.legend.pure.m3.module.pdbModule.PDBModule;
import org.finos.legend.pure.m3.pureLanguage.PureLanguageExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Reproduces a hang observed when porting the legend-engine java-generation
 * Pure modules into legend-pure-next. The hang manifests during the compile
 * passes (firstPass/secondPass/thirdPass) on source files that contain deeply
 * chained fluent-builder expressions of the form:
 *
 * <pre>
 *   x-&gt;f(a1)-&gt;f(a2)-&gt;f(a3)-&gt; ... -&gt;f(aN)
 * </pre>
 *
 * <p>A jstack on the hung process shows the main thread alternating between
 * {@code FunctionApplicationResolver.fixpointResolveParams} and
 * {@code FunctionApplicationResolver.resolveFunctionApplicationUsingTemplateFunctionForInference}.
 * Each fixpoint iteration of the outer call recursively re-resolves the entire
 * inner expression tree, producing exponential blow-up as N grows: a chain of
 * length N where each call's outer fixpoint takes k iterations costs O(k^N)
 * inner re-resolutions.</p>
 *
 * <p>This test compiles a synthetic chain of growing length and asserts that
 * each compile completes within a bounded wall-clock budget. The test is
 * scaled on N (chain length); short chains pass in &lt; 1s, longer chains
 * currently hang indefinitely. Once the resolver caches inner-expression
 * resolutions across outer fixpoint iterations the longer cases should also
 * land in &lt; 1s.</p>
 */
public class DeepFluentChainResolverTest
{
    /** Per-compile wall-clock budget. Far above any healthy compile time. */
    private static final long COMPILE_TIMEOUT_SECONDS = 10;

    /**
     * Build a Pure source with N nested {@code if(cond, |branchA, |branchB)} calls
     * whose branches are lambdas returning a common type. Mirrors the structural
     * shape of {@code pureTypeToJavaType} (legend-engine's java-generation Pure
     * code), the function that hangs the legend-pure-next compiler at depth ~13.
     * Each {@code if} introduces two lambda parameters that the resolver must
     * make-as-concrete-as-possible against the shared return type, recursing
     * through {@code _GenericType.findCommonGenericType}.
     */
    private static String buildSource(int nestedIfDepth)
    {
        // Mirror the pureTypeToJavaType pattern: a Conventions-like class plus
        // many overloaded helper functions called via UCS (`$c->f($x)`) inside
        // each branch — same shape as `$conventions->stringType($mult)` etc.
        // Plus a recursive self-call in the innermost else branch.
        StringBuilder sb = new StringBuilder();
        sb.append("Class test::Out\n{\n  tag : String[1];\n}\n\n");
        sb.append("Class test::Conv\n{\n");
        sb.append("  basePackage : String[1];\n");
        sb.append("  factory : Function<{String[1]->test::Out[1]}>[1];\n");
        sb.append("}\n\n");
        // Multiple overloads of helper, each callable via UCS as `$c->helperX(arg)`.
        for (int i = 0; i < 12; i++)
        {
            sb.append("function test::helper").append(i)
                    .append("(c: test::Conv[1], n: meta::pure::metamodel::multiplicity::Multiplicity[1]): test::Out[1]\n")
                    .append("{\n  $c.factory->eval('h").append(i).append("')\n}\n\n");
            // 1-arg overload using the same name (UCS would resolve correctly).
            sb.append("function test::helper").append(i)
                    .append("(c: test::Conv[1]): test::Out[1]\n")
                    .append("{\n  $c->test::helper").append(i).append("(PureOne)\n}\n\n");
        }
        // Hanging-element-style function: deeply nested if with UCS dispatch in each branch
        // and a recursive self-call in the final else.
        sb.append("function test::deepIf(c: test::Conv[1], t: meta::pure::metamodel::type::Type[1], mult: meta::pure::metamodel::multiplicity::Multiplicity[1]): test::Out[1]\n");
        sb.append("{\n");
        for (int i = 0; i < nestedIfDepth; i++)
        {
            int hi = i % 12;
            sb.append("   if($t == String,\n");
            sb.append("      |$c->test::helper").append(hi).append("($mult),\n");
            sb.append("      |");
        }
        sb.append("test::deepIf($c, $t, $mult)\n");
        for (int i = 0; i < nestedIfDepth; i++) sb.append("   )");
        sb.append(";\n}\n");
        return sb.toString();
    }

    private static CompilationResult compile(String content) throws Exception
    {
        PDBModule core = new PDBModule(BootstrapModule.locateCorePdb(), PDBModule.Mode.COMPILATION);
        LocalModule local = new LocalModule("test", "*",
                Lists.mutable.with(core.getName()),
                Lists.mutable.with(new PureContent(content, "test.pure")));
        return PureModel.withModules(Lists.mutable.with(core, local))
                .withExtensions(Lists.mutable.with(new PureLanguageExtension()))
                .build()
                .compile();
    }

    /** Sanity check: a 1-deep chain compiles cleanly and quickly. */
    @Test
    void shallowChainCompiles() throws Exception
    {
        CompilationResult result = compile(buildSource(1));
        assertTrue(result.errors().isEmpty(), "1-deep chain compile errors: " + result.errors());
    }

    /**
     * Compile the ported java-generation module
     * ({@code pure/language/java}, the one that surfaced the resolver hang
     * while being ported into legend-pure-next) and fail with a stack dump
     * if the compile hangs. The hung element is
     * {@code meta::external::language::java::transform::pureTypeToJavaType}
     * (4-arg overload, see {@code pure/generation/conventions.pure}); a
     * companion documentation fixture lives at
     * {@code pure/specification/compiler/known_issues/deep_nested_if_resolver_hang.pure}.
     *
     * <p>On timeout, prints (a) the currently-compiling element via
     * {@link LocalModule#currentElement()} and (b) a thread dump of the
     * compile thread, so a regression points straight at the hung element
     * and resolver path.</p>
     */
    @Test
    void portedJavaModuleCompilesWithinBudget() throws Exception
    {
        Path javaModuleDir = locateJavaModule();
        if (javaModuleDir == null || !Files.isDirectory(javaModuleDir))
        {
            // Test is opt-in: only runs in checkouts where pure/language/java exists.
            System.out.println("Skipping portedJavaModule test — pure/language/java not found.");
            return;
        }

        long timeoutSec = 60;
        long t0 = System.nanoTime();
        Path moduleDir = javaModuleDir;

        Thread[] compileThreadHolder = new Thread[1];
        LocalModule[] localHolder = new LocalModule[1];
        var executor = Executors.newSingleThreadExecutor(r ->
        {
            Thread t = new Thread(r, "java-module-compile");
            t.setDaemon(true);
            compileThreadHolder[0] = t;
            return t;
        });

        try
        {
            Future<CompilationResult> future = executor.submit(() ->
            {
                PDBModule core = new PDBModule(BootstrapModule.locateCorePdb(), PDBModule.Mode.COMPILATION);
                LocalModule local = new LocalModule("javaModule", "*",
                        Lists.mutable.with(core.getName()), moduleDir);
                localHolder[0] = local;
                return PureModel.withModules(Lists.mutable.with(core, local))
                        .withExtensions(Lists.mutable.with(new PureLanguageExtension()))
                        .build()
                        .compile();
            });

            CompilationResult result;
            try
            {
                result = future.get(timeoutSec, TimeUnit.SECONDS);
            }
            catch (TimeoutException te)
            {
                String elem = localHolder[0] == null ? "(unknown — compile not yet started)"
                        : localHolder[0].currentElement();
                long elapsedOnElement = localHolder[0] == null ? 0 : localHolder[0].currentElementElapsedMs();
                System.err.println("=== HUNG ELEMENT ===");
                System.err.println("currentElement = " + elem);
                System.err.println("elapsed on this element = " + elapsedOnElement + " ms");
                System.err.println("====================");
                dumpThread(compileThreadHolder[0]);
                future.cancel(true);
                fail("pure/language/java compile hung past " + timeoutSec
                        + "s on element " + elem + " — see stack dump above.");
                return;
            }

            long elapsedMs = (System.nanoTime() - t0) / 1_000_000;
            System.out.println("pure/language/java compiled in " + elapsedMs + " ms, errors=" + result.errors().size());
            // Termination is the point here; we don't assert error-free since the
            // ported module may legitimately have unresolved references against
            // legend-pure-next core (the porting work is in flight).
        }
        finally
        {
            executor.shutdownNow();
        }
    }

    private static Path locateJavaModule()
    {
        Path cur = Path.of("").toAbsolutePath();
        while (cur != null)
        {
            Path candidate = cur.resolve("pure").resolve("language").resolve("java");
            if (Files.isDirectory(candidate))
            {
                return candidate;
            }
            cur = cur.getParent();
        }
        return null;
    }

    private static Path locateFixture()
    {
        Path cur = Path.of("").toAbsolutePath();
        while (cur != null)
        {
            Path candidate = cur.resolve("pure").resolve("specification").resolve("compiler")
                    .resolve("known_issues").resolve("deep_nested_if_resolver_hang.pure");
            if (Files.isRegularFile(candidate))
            {
                return candidate;
            }
            cur = cur.getParent();
        }
        return null;
    }

    /** Print the stack trace of the given thread. Used on watchdog timeout. */
    private static void dumpThread(Thread t)
    {
        if (t == null)
        {
            System.err.println("dumpThread: no thread reference captured");
            return;
        }
        ThreadMXBean mx = ManagementFactory.getThreadMXBean();
        ThreadInfo info = mx.getThreadInfo(t.getId(), 200);
        if (info == null)
        {
            System.err.println("dumpThread: thread " + t.getName() + " not in dump");
            return;
        }
        System.err.println("=== STACK DUMP for hung compile thread '" + info.getThreadName() + "' ===");
        System.err.println("State: " + info.getThreadState());
        for (StackTraceElement frame : info.getStackTrace())
        {
            System.err.println("\tat " + frame);
        }
        System.err.println("=== END STACK DUMP ===");
    }

    /**
     * Scaling test: each chain-depth value should compile within
     * {@link #COMPILE_TIMEOUT_SECONDS}. Run against a watchdog so a hang
     * fails the test instead of stalling the suite indefinitely.
     */
    @ParameterizedTest
    @ValueSource(ints = {2, 5, 8, 10, 12, 13, 14, 16})
    void deepChainCompilesWithinBudget(int nestedIfDepth) throws Exception
    {
        String source = buildSource(nestedIfDepth);
        var executor = Executors.newSingleThreadExecutor(r ->
        {
            Thread t = new Thread(r, "deep-chain-compile-" + nestedIfDepth);
            t.setDaemon(true);
            return t;
        });
        try
        {
            long t0 = System.nanoTime();
            Future<CompilationResult> future = executor.submit(() -> compile(source));
            CompilationResult result;
            try
            {
                result = future.get(COMPILE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            }
            catch (TimeoutException te)
            {
                future.cancel(true);
                fail("Compile of " + nestedIfDepth + "-deep chain hung past "
                        + COMPILE_TIMEOUT_SECONDS + "s — likely exponential resolver blowup.");
                return;
            }
            long elapsedMs = (System.nanoTime() - t0) / 1_000_000;
            System.out.println("nestedIfDepth=" + nestedIfDepth + " compiled in " + elapsedMs + " ms"
                    + ", errors=" + result.errors().size());
            assertTrue(result.errors().isEmpty(),
                    nestedIfDepth + "-deep chain produced compile errors: " + result.errors());
        }
        finally
        {
            executor.shutdownNow();
        }
    }
}
