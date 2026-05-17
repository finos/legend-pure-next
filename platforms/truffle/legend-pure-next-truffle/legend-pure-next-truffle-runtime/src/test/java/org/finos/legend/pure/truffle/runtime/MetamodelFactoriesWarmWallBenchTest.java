// Copyright 2026 Goldman Sachs
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0

package org.finos.legend.pure.truffle.runtime;

import org.finos.legend.pure.truffle.PureLanguage;
import org.finos.legend.pure.truffle.PureTruffleRuntime;
import org.finos.legend.pure.truffle.parser.TrufflePureParser;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Warm-wall benchmark for the {@code metamodel_factories.pure} compile.
 *
 * <p>The 3.96s baseline cited in the migration memory came from a manual
 * {@code pure-truffle compile --source metamodel_factories.pure} invocation
 * after each round of perf work. This in-process test gives us a programmatic
 * equivalent: 1 warm-up compile, then 5 measurement runs, reporting min/median
 * wall. Useful to track perf deltas across the dynobj migration without
 * rebuilding the fat jar each time.</p>
 *
 * <p>{@code @Disabled} by default — enable manually when measuring. Running it
 * in CI would add ~30s to every test run with no asserted outcome (it just
 * prints numbers).</p>
 */
@Disabled("Sandbox bench — depends on the untracked `pure/language/` workspace "
        + "for `metamodel_factories.pure`, so it fails on fresh clones / CI. "
        + "Enable locally when measuring.")
public class MetamodelFactoriesWarmWallBenchTest
{
    private static PureTruffleRuntime runtime;
    private static TruffleMetadataAccess resolver;
    private static Object compileFn;
    private static TrufflePureParser pureParser;
    private static String metamodelFactoriesSource;
    private static String testName;

    @BeforeAll
    static void setup() throws IOException
    {
        Path buildDir = locateBuildDir();
        TrufflePdbLoader coreLoader = new TrufflePdbLoader(buildDir.resolve("core.pdb"));
        TrufflePdbLoader compilerLoader = new TrufflePdbLoader(buildDir.resolve("compiler.pdb"));
        TruffleModuleRegistry registry = new TruffleModuleRegistry();
        registry.register(coreLoader);
        registry.register(compilerLoader);
        resolver = registry;
        coreLoader.setResolver(resolver);
        compilerLoader.setResolver(resolver);
        coreLoader.preloadAll();
        compilerLoader.preloadAll();

        PureTruffleRuntime.Builder runtimeBuilder = PureTruffleRuntime.builder()
                .withResolver(resolver)
                .withParserExtensions(List.of(
                        new TruffleCompiledGraphLanguageExtension(),
                        new TruffleCompilerStatsLanguageExtension(),
                        new TruffleErrorLanguageExtension()))
                .withCompileImmediately(false);
        if (Boolean.getBoolean("pure.bench.profiler"))
        {
            runtimeBuilder
                    .withPolyglotOption("pureprofiler", "true")
                    .withPolyglotOption("pureprofiler.OutputFile", "/tmp/pure-profiler.txt")
                    .withPolyglotOption("pureprofiler.Top", "40");
        }
        runtime = runtimeBuilder.build();

        compileFn = resolver.getElement("meta::pure::compiler::compile_PureFile_1__CompilationResult_1_");
        pureParser = PureLanguage.get(null).pureParser();

        // Locate metamodel_factories.pure
        Path mmf = locateMetamodelFactories();
        testName = "metamodel_factories";
        metamodelFactoriesSource = Files.readString(mmf, StandardCharsets.UTF_8);
        System.out.printf("[bench] Loaded %s (%d bytes)%n", mmf, metamodelFactoriesSource.length());
    }

    @AfterAll
    static void teardown()
    {
        if (runtime != null) runtime.close();
    }

    @Test
    void bench()
    {
        // Parse ONCE up front to a PDO PureFile and reuse it across all measured runs.
        // Post-TrufflePureParser the parse already produces PDOs — no ProtocolTranslator copy.
        Object truffleFile = pureParser.parse(testName, metamodelFactoriesSource);

        // Single warm-up to trigger AST build + Graal compilation of the
        // hot metamodel-walking paths. Subsequent runs measure the
        // already-warm wall clock.
        long warmStart = System.nanoTime();
        compileOnce(truffleFile);
        long warmNs = System.nanoTime() - warmStart;
        System.out.printf("[bench] warm-up: %.2f s%n", warmNs / 1e9);

        long[] runs = new long[5];
        for (int i = 0; i < runs.length; i++)
        {
            long t0 = System.nanoTime();
            compileOnce(truffleFile);
            runs[i] = System.nanoTime() - t0;
            System.out.printf("[bench] run %d: %.2f s%n", i + 1, runs[i] / 1e9);
        }

        java.util.Arrays.sort(runs);
        double minS = runs[0] / 1e9;
        double medianS = runs[runs.length / 2] / 1e9;
        double maxS = runs[runs.length - 1] / 1e9;
        System.out.printf("[bench] WALL min=%.2fs  median=%.2fs  max=%.2fs%n", minS, medianS, maxS);
        System.out.printf("[bench] vs 3.96s baseline: %+.2fs (%+.0f%%)%n",
                medianS - 3.96, (medianS - 3.96) / 3.96 * 100);
    }

    private void compileOnce(Object truffleFile)
    {
        Object result = runtime.execute(compileFn, truffleFile);
        if (result == null)
        {
            throw new RuntimeException("compile returned null");
        }
    }

    private static Path locateBuildDir()
    {
        Path current = Path.of("").toAbsolutePath();
        while (current != null)
        {
            Path candidate = current.resolve("shared");
            if (Files.isDirectory(candidate)
                    && Files.exists(candidate.resolve("core.pdb"))
                    && Files.exists(candidate.resolve("compiler.pdb")))
            {
                return candidate;
            }
            current = current.getParent();
        }
        throw new RuntimeException("Cannot locate shared/core.pdb + shared/compiler.pdb");
    }

    private static Path locateMetamodelFactories()
    {
        Path current = Path.of("").toAbsolutePath();
        while (current != null)
        {
            Path candidate = current.resolve("pure/language/java/pure/metamodel_factories.pure");
            if (Files.isRegularFile(candidate)) return candidate;
            current = current.getParent();
        }
        throw new RuntimeException("Cannot locate pure/language/java/pure/metamodel_factories.pure");
    }
}
