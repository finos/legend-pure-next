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

package org.finos.legend.pure.truffle;

import org.finos.legend.pure.truffle.runtime.TruffleCompiledGraphLanguageExtension;
import org.finos.legend.pure.truffle.runtime.TruffleCompilerStatsLanguageExtension;
import org.finos.legend.pure.truffle.runtime.TruffleTestFileLanguageExtension;
import org.finos.legend.pure.truffle.runtime.TruffleErrorLanguageExtension;
import org.finos.legend.pure.truffle.runtime.TruffleInMemoryModule;
import org.finos.legend.pure.truffle.runtime.TruffleModuleRegistry;
import org.finos.legend.pure.truffle.runtime.TrufflePdbLoader;
import org.finos.legend.pure.truffle.runtime.TruffleReverseIndexLanguageExtension;
import org.finos.legend.pure.truffle.runtime.dynobj.PureObj;
import org.finos.legend.pure.truffle.types.PureSequence;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration test: load only core.pdb + compiler.pdb (no -tests companions),
 * freshly compile {@code pure/specification/runtime/} via compile-pure-on-truffle,
 * keep only test-tagged elements (mirroring {@code TestElementFilter.isTestElement}'s
 * core.pdb/core-tests.pdb split) and register them as an in-memory module on
 * top of core. Drives both runners ({@code runTests}, {@code runPCTTests}) and
 * asserts no <<test.Test>> / <<PCT.test>> failure — i.e. the freshly compiled
 * test elements behave identically to their core-tests.pdb equivalents.
 *
 * <p>"In-memory module" = no PDB on disk. The full test corpus is compiled
 * end-to-end through the same pipeline a regular {@code test-pure-runtime}
 * run would exercise, with the post-pass-3 pointer/identity canonicalization
 * happening at {@link TruffleInMemoryModule} construction (via
 * {@link org.finos.legend.pure.truffle.runtime.helper.PointerGraphResolver}).
 */
public class TrufflePurePCTAndFuncFromMemoryTest
{
    // Multi-root parseDir: stamps sourceIds relative to each input root so
    // {@code runtime/functions/asserts/assertError.pure} gets sourceId
    // {@code asserts/assertError.pure} — matching the PDB build convention
    // (SpecificationBinaryBuilder splits the runtime corpus into
    // functions/, test/, etc.). Without this, stack-trace assertions
    // hardcoded to the short form (e.g. testFullPureStackTrace) fail.
    private static final String PARSE_DIRS_FN_PATH =
            "meta::pure::compiler::parseDirs_String_MANY__PureFile_MANY_";
    // 3-arg silent variant — tests pull errors/stats structurally off the
    // result; the live progress bar + stats summary would flood surefire output.
    private static final String COMPILE_FN_PATH =
            "meta::pure::compiler::compile_PureFile_MANY__Boolean_1__Boolean_1__CompilationResult_1_";

    private static PureTruffleRuntime runtime;
    private static TruffleModuleRegistry registry;
    private static Object runTestsFn;
    private static Object runPctTestsFn;

    @BeforeAll
    static void setupOnce() throws IOException
    {
        Path sharedDir = locateSharedDir();
        Path corePdb = sharedDir.resolve("core.pdb");
        Path compilerPdb = sharedDir.resolve("compiler.pdb");

        TrufflePdbLoader coreLoader = new TrufflePdbLoader(corePdb);
        TrufflePdbLoader compilerLoader = new TrufflePdbLoader(compilerPdb);

        registry = new TruffleModuleRegistry();
        registry.register(coreLoader);
        registry.register(compilerLoader);
        coreLoader.setResolver(registry);
        compilerLoader.setResolver(registry);
        coreLoader.preloadAll();
        compilerLoader.preloadAll();

        runtime = PureTruffleRuntime.builder()
                .withResolver(registry)
                .withParserExtensions(List.of(
                        new TruffleCompiledGraphLanguageExtension(),
                        new TruffleCompilerStatsLanguageExtension(),
                        new TruffleTestFileLanguageExtension(),
                        new TruffleReverseIndexLanguageExtension(),
                        new TruffleErrorLanguageExtension()))
                .build();

        Object parseDirsFn = registry.getElement(PARSE_DIRS_FN_PATH);
        Object compileFn = registry.getElement(COMPILE_FN_PATH);
        if (parseDirsFn == null || compileFn == null
                || !PureObj.isType(parseDirsFn, "meta::pure::metamodel::function::FunctionDefinition", registry)
                || !PureObj.isType(compileFn, "meta::pure::metamodel::function::FunctionDefinition", registry))
        {
            throw new IllegalStateException("parseDirs/compile FunctionDefinition not resolvable: "
                    + "parseDirs=" + (parseDirsFn == null ? "null" : parseDirsFn.getClass().getName())
                    + ", compile=" + (compileFn == null ? "null" : compileFn.getClass().getName()));
        }

        // Compile the real pure/specification/runtime/ corpus into an in-memory
        // module. Yields the actual <<test.Test>> / <<PCT.test>> functions
        // (~232 + 426 in the current tree) plus the standard runners and the
        // PCT in-memory adapter — exactly what the user-facing test-pure-runtime
        // recipe exercises, but compiled fresh against core.pdb rather than
        // loaded from core-tests.pdb.
        // Compile-pure needs the full runtime corpus to type-check (test
        // functions reference helper classes defined in featureTests/ and
        // library functions in functions/ — without those in scope, type
        // inference fails). After compile, we'll filter result.elements to
        // ONLY the {@code <<test.Test>>} / {@code <<PCT.test>>} elements
        // before constructing the in-memory module; the discarded library +
        // helper elements come from core.pdb via the resolver, so the
        // registry's uniqueness invariant is preserved. Slot refs from the
        // kept test functions to discarded helpers are canonicalized to
        // core.pdb's instances by PointerGraphResolver during module
        // construction.
        Path runtimeSrc = locateRuntimeSrc();
        java.util.List<String> sourceRoots = java.util.List.of(
                runtimeSrc.resolve("functions").toAbsolutePath().toString(),
                runtimeSrc.resolve("featureTests").toAbsolutePath().toString(),
                runtimeSrc.resolve("ui").toAbsolutePath().toString(),
                runtimeSrc.resolve("test").toAbsolutePath().toString()
        );
        Object result;
        try
        {
            Object parsedFiles = runtime.execute(parseDirsFn, sourceRoots);
            // debug=false, silent=true
            result = runtime.execute(compileFn, parsedFiles, Boolean.FALSE, Boolean.TRUE);
        }
        catch (Throwable t)
        {
            // Surface the Pure-level stack so we can see *which* atomic value
            // / what source location compileAtomicValue chokes on. Compile-pure
            // bugs that fire mid-pass are otherwise invisible — the JVM stack
            // ends at the native CastNode, not in the Pure source that built
            // the broken VS.
            String pureStack = org.finos.legend.pure.truffle.runtime.PureStackFormatter.format(t);
            if (!pureStack.isEmpty())
            {
                System.err.println(pureStack);
            }
            throw t;
        }
        if (result == null)
        {
            throw new RuntimeException("compile returned null");
        }
        Object errorsObj = PureObj.read(result, "errors");
        if (errorsObj instanceof PureSequence errSeq && errSeq.size() > 0)
        {
            StringBuilder sb = new StringBuilder("compile produced errors:\n");
            for (int i = 0; i < errSeq.size(); i++)
            {
                sb.append("  ").append(errSeq.getBoxed(i)).append('\n');
            }
            throw new RuntimeException(sb.toString());
        }

        Object elementsObj = PureObj.read(result, "elements");
        if (!(elementsObj instanceof PureSequence elementsSeq) || elementsSeq.size() == 0)
        {
            throw new IllegalStateException("compile.elements is empty");
        }

        // Keep only test-tagged elements (mirrors functionTestRunner.pure +
        // pctTestRunner.pure): {@code <<test.Test>>} (profile.name == 'test' &&
        // stereotype.value == 'Test') or {@code <<PCT.test>>}
        // (profile.name == 'PCT' && stereotype.value == 'test'). Everything
        // else (helpers, profiles, library) comes from core.pdb via the
        // resolver — the registry's uniqueness invariant enforces no overlap.
        PureSequence testElements = filterTestStereotyped(elementsSeq);
        if (testElements.size() == 0)
        {
            throw new IllegalStateException("no test-tagged elements after filtering compile result");
        }

        // Register the filtered test elements as an in-memory module. The
        // dep list contains every module already in the registry so test
        // elements can reference core/compiler without cycle errors.
        java.util.List<String> deps = new java.util.ArrayList<>();
        for (org.finos.legend.pure.truffle.runtime.TruffleModule existing : registry.modules())
        {
            deps.add(existing.name());
        }
        registry.register(new TruffleInMemoryModule("inmem-runtime-tests", deps, testElements, registry));

        runTestsFn = registry.getElement("meta::pure::test::runTests_String_1__String_1_");
        runPctTestsFn = registry.getElement("meta::pure::test::runPCTTests_String_1__String_1_");
        assertNotNull(runTestsFn, "runTests not resolvable through registry");
        assertNotNull(runPctTestsFn, "runPCTTests not resolvable through registry");
    }

    @AfterAll
    static void tearDown()
    {
        if (runtime != null)
        {
            runtime.close();
        }
    }

    @Test
    void runFunctionTests()
    {
        Object output = runtime.execute(runTestsFn, "meta::pure::functions");
        String line = String.valueOf(output);
        assertTrue(line.startsWith("OK:") && line.contains("<<test.Test>> tests passed"),
                () -> "runTests should report OK; got: " + line);
    }

    @Test
    void runPCTTests()
    {
        Object output = runtime.execute(runPctTestsFn, "meta::pure::functions");
        String line = String.valueOf(output);
        assertTrue(line.startsWith("OK:") && line.contains("<<PCT.test>> tests passed"),
                () -> "runPCTTests should report OK; got: " + line);
    }

    /**
     * Mirror of {@code TestElementFilter.isTestElement} (the bootstrap's
     * split rule for core.pdb vs core-tests.pdb): an element is test-related
     * if it carries any stereotype on {@code meta::pure::profiles::test}
     * (Test, TestDependency, BeforePackage, AfterPackage, ToFix) OR the
     * {@code test} / {@code adapter} stereotype on
     * {@code meta::pure::test::pct::PCT}. Everything else is library code
     * — bootstrap puts it in core.pdb, so the in-memory module must NOT
     * re-declare it.
     */
    private static final String TEST_PROFILE_PATH = "meta::pure::profiles::test";
    private static final String PCT_PROFILE_PATH = "meta::pure::test::pct::PCT";
    private static final java.util.Set<String> PCT_TEST_STEREOTYPES = java.util.Set.of("test", "adapter");

    private static PureSequence filterTestStereotyped(PureSequence elements)
    {
        java.util.List<Object> kept = new java.util.ArrayList<>();
        for (int i = 0; i < elements.size(); i++)
        {
            Object el = elements.getBoxed(i);
            if (el != null && isTestElement(el))
            {
                kept.add(el);
            }
        }
        return new org.finos.legend.pure.truffle.types.ObjectSequence(kept.toArray());
    }

    private static boolean isTestElement(Object element)
    {
        Object stereos = PureObj.read(element, "stereotypes");
        if (!(stereos instanceof PureSequence seq)) return false;
        for (int i = 0; i < seq.size(); i++)
        {
            Object s = seq.getBoxed(i);
            if (s == null) continue;
            String profilePath;
            String stereoName;
            String ptrPath = org.finos.legend.pure.truffle.runtime.helper._PackageableElement.pointerPath(s);
            if (ptrPath != null)
            {
                profilePath = ptrPath;
                Object name = PureObj.read(s, "element");
                stereoName = name instanceof String str ? str : null;
            }
            else
            {
                Object profile = PureObj.read(s, "profile");
                profilePath = profile != null
                        ? org.finos.legend.pure.truffle.runtime.helper._PackageableElement.path(profile, registry)
                        : null;
                Object value = PureObj.read(s, "value");
                stereoName = value instanceof String str ? str : null;
            }
            if (TEST_PROFILE_PATH.equals(profilePath))
            {
                return true;
            }
            if (PCT_PROFILE_PATH.equals(profilePath) && PCT_TEST_STEREOTYPES.contains(stereoName))
            {
                return true;
            }
        }
        return false;
    }

    private static Path locateRuntimeSrc()
    {
        Path cur = Path.of("").toAbsolutePath();
        while (cur != null)
        {
            Path candidate = cur.resolve("pure").resolve("specification").resolve("runtime");
            if (Files.isDirectory(candidate))
            {
                return candidate;
            }
            cur = cur.getParent();
        }
        throw new IllegalStateException("Cannot locate pure/specification/runtime/ walking up from "
                + Path.of("").toAbsolutePath());
    }

    private static Path locateSharedDir()
    {
        Path cur = Path.of("").toAbsolutePath();
        while (cur != null)
        {
            Path candidate = cur.resolve("shared");
            if (Files.isDirectory(candidate)
                    && Files.exists(candidate.resolve("core.pdb"))
                    && Files.exists(candidate.resolve("compiler.pdb")))
            {
                return candidate;
            }
            cur = cur.getParent();
        }
        throw new IllegalStateException("Cannot locate shared/core.pdb + shared/compiler.pdb walking up from "
                + Path.of("").toAbsolutePath());
    }

}
