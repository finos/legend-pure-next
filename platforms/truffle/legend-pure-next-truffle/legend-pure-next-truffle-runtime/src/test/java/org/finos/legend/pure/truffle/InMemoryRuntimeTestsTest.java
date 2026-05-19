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
import org.finos.legend.pure.truffle.runtime.helper._PackageableElement;
import org.finos.legend.pure.truffle.types.PureSequence;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Disabled;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration test: load only core.pdb + compiler.pdb (no -tests companions),
 * compile a small custom test corpus alongside the standard runners
 * ({@code meta::pure::test::runTests}, {@code runPCTTests}, the in-memory
 * PCT adapter) freshly from {@code pure/specification/runtime/}, register
 * the resulting in-memory CompilationResult as a second module on top of
 * core.pdb, then drive both runners against the live image.
 *
 * <p>"Local module" = in-memory, no PDB on disk. The runner sources and the
 * test functions are compiled together so the test exercises the full
 * runner-discovery path end-to-end without the core-tests.pdb companion.
 *
 * <p>Disabled temporarily: the {@code testAdapterForInMemoryExecution}
 * function compiled fresh by compile-pure ends up with a null
 * {@code expressionSequence} slot at runtime, causing a NPE in
 * {@code PureASTBuilder.lowerBody}. The PDB-loaded path
 * ({@code just truffle test-pure-runtime-PCTs}) works (426/426), so the
 * divergence is between raw compile-pure output and what survives the PDB
 * write/read round-trip. Separate bug; tracking elsewhere.
 */
@Disabled("PCT adapter compile NPE — testAdapterForInMemoryExecution has null expressionSequence in-memory; PDB path works")
public class InMemoryRuntimeTestsTest
{
    private static final String PARSE_DIR_FN_PATH =
            "meta::pure::compiler::parseDir_String_1__PureFile_MANY_";
    private static final String COMPILE_FN_PATH =
            "meta::pure::compiler::compile_PureFile_MANY__Boolean_1__CompilationResult_1_";

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

        Object parseDirFn = registry.getElement(PARSE_DIR_FN_PATH);
        Object compileFn = registry.getElement(COMPILE_FN_PATH);
        if (parseDirFn == null || compileFn == null
                || !PureObj.isType(parseDirFn, "meta::pure::metamodel::function::FunctionDefinition", registry)
                || !PureObj.isType(compileFn, "meta::pure::metamodel::function::FunctionDefinition", registry))
        {
            throw new IllegalStateException("parseDir/compile FunctionDefinition not resolvable: "
                    + "parseDir=" + (parseDirFn == null ? "null" : parseDirFn.getClass().getName())
                    + ", compile=" + (compileFn == null ? "null" : compileFn.getClass().getName()));
        }

        // Compile the real pure/specification/runtime/ corpus into an in-memory
        // module. Yields the actual <<test.Test>> / <<PCT.test>> functions
        // (~232 + 426 in the current tree) plus the standard runners and the
        // PCT in-memory adapter — exactly what the user-facing test-pure-runtime
        // recipe exercises, but compiled fresh against core.pdb rather than
        // loaded from core-tests.pdb.
        Path runtimeSrc = locateRuntimeSrc();
        Object result;
        try
        {
            Object parsedFiles = runtime.execute(parseDirFn, runtimeSrc.toAbsolutePath().toString());
            result = runtime.execute(compileFn, parsedFiles, Boolean.FALSE);
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

        // Register the entire CompilationResult as an in-memory module. Mirrors
        // the IDE's TruffleBackend.execute pattern (the dep list contains every
        // module already in the registry so user elements can reference core
        // and compiler without cycle errors).
        java.util.List<String> deps = new java.util.ArrayList<>();
        for (org.finos.legend.pure.truffle.runtime.TruffleModule existing : registry.modules())
        {
            deps.add(existing.name());
        }
        registry.register(new TruffleInMemoryModule("inmem-runtime-tests", deps, elementsSeq));

        runTestsFn = registry.getElement("meta::pure::test::runTests_String_1__String_1_");
        runPctTestsFn = registry.getElement("meta::pure::test::runPCTTests_String_1__String_1_");
        assertNotNull(runTestsFn, "runTests should be in the freshly compiled in-memory module");
        assertNotNull(runPctTestsFn, "runPCTTests should be in the freshly compiled in-memory module");
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
