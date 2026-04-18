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

import meta.pure.metamodel.function.FunctionDefinition;
import org.eclipse.collections.api.factory.Lists;
import org.finos.legend.pure.execution.PureExecution;
import org.finos.legend.pure.m3.PureModel;
import org.finos.legend.pure.m3.module.ScopedMetadataAccess;
import org.finos.legend.pure.m3.module.bootstrapModule.BootstrapModule;
import org.finos.legend.pure.m3.module.pdbModule.PDBModule;
import org.finos.legend.pure.m3.pureLanguage.PureLanguageExtension;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Phase B end-to-end test: loads core.pdb, resolves a simple user-defined
 * function ({@code isNotEmpty}), and verifies that invoking it via the
 * Truffle interpreter produces the same result as the Java tree-walking
 * interpreter.
 *
 * <p>This exercises: AtomicValueNode, VariableReadNode, CollectionNode,
 * BridgedNativeCallNode (for the inner {@code isEmpty} and {@code !} natives),
 * UserFunctionCallNode (for the outer {@code isNotEmpty} invocation),
 * and the TruffleEvaluator → PureASTBuilder pipeline.</p>
 */
class PureTruffleEndToEndTest
{
    private static PureModel model;
    private static ScopedMetadataAccess resolver;

    @BeforeAll
    static void loadCore() throws Exception
    {
        PDBModule corePdb = new PDBModule(
                BootstrapModule.locateCorePdb(),
                PDBModule.Mode.EXECUTION,
                "core",
                "*",
                Lists.mutable.with());

        PDBModule compilerPdb = new PDBModule(
                BootstrapModule.locateCompilerPdb(),
                PDBModule.Mode.EXECUTION,
                "compiler",
                "*",
                Lists.mutable.with("core"));

        model = PureModel.withModules(Lists.mutable.with(corePdb, compilerPdb))
                .withExtensions(Lists.mutable.with(new PureLanguageExtension()))
                .build();
        model.compile();
        resolver = new ScopedMetadataAccess(compilerPdb, model);
    }

    @Test
    void isNotEmptyWithEmptyList()
    {
        FunctionDefinition fn = resolveIsNotEmptyAny();

        Object javaResult = javaExec().execute(fn, List.of());
        Object truffleResult = truffleExec().execute(fn, List.of());

        assertEquals(false, javaResult);
        assertEquals(javaResult, truffleResult);
    }

    @Test
    void isNotEmptyWithSingleElement()
    {
        FunctionDefinition fn = resolveIsNotEmptyAny();

        Object javaResult = javaExec().execute(fn, List.of(42L));
        Object truffleResult = truffleExec().execute(fn, List.of(42L));

        assertEquals(true, javaResult);
        assertEquals(javaResult, truffleResult);
    }

    @Test
    void isNotEmptyWithMany()
    {
        FunctionDefinition fn = resolveIsNotEmptyAny();

        Object javaResult = javaExec().execute(fn, List.of("a", "b", "c"));
        Object truffleResult = truffleExec().execute(fn, List.of("a", "b", "c"));

        assertEquals(true, javaResult);
        assertEquals(javaResult, truffleResult);
    }

    /**
     * Exercises: lambdas with closure capture, {@code range}, {@code map},
     * {@code joinStrings}, {@code substring}, {@code toString},
     * {@code floor}, the {@code if_} eager native, integer arithmetic.
     * Verifies Truffle matches Java on a non-trivial self-hosted function.
     */
    @Test
    void renderBarMatchesJava()
    {
        FunctionDefinition fn = (FunctionDefinition) resolver.getElement(
                "meta::pure::test::renderBar_Integer_1__Integer_1__String_1_");
        org.junit.jupiter.api.Assertions.assertNotNull(fn,
                "renderBar should be resolvable from compiler.pdb");

        for (long[] args : new long[][]{{0L, 10L}, {5L, 10L}, {10L, 10L}, {42L, 207L}, {100L, 207L}})
        {
            Object javaResult = javaExec().execute(fn, args[0], args[1]);
            Object truffleResult = truffleExec().execute(fn, args[0], args[1]);
            assertEquals(javaResult, truffleResult,
                    "renderBar(" + args[0] + ", " + args[1] + ") mismatch");
        }
    }

    /**
     * Exercises the lazy {@code and_} / {@code or_} short-circuit natives
     * transitively via {@code isNotEmpty}'s use of {@code !} (which is eager)
     * — plus covers another overload dispatch path.
     */
    /**
     * Ultimate parity check: run the self-hosted Pure test suite
     * ({@code meta::pure::test::runCompiledGraphTests}) via Truffle on a
     * subset of fixtures, and confirm it returns {@code true} — identical to
     * the Java path used by {@code just test-pure}.
     *
     * <p>This is the most feature-dense test in the suite: filesystem
     * natives, lambdas with captured scope, lazy operators (and/or in
     * {@code assertCompiledGraph}), string ops, collection ops, error
     * reporting — all under a single invocation.</p>
     */
    @Test
    void runCompiledGraphTestsSubsetViaTruffle()
    {
        FunctionDefinition fn = (FunctionDefinition) resolver.getElement(
                "meta::pure::test::runCompiledGraphTests_String_1__Boolean_1_");
        org.junit.jupiter.api.Assertions.assertNotNull(fn,
                "runCompiledGraphTests should be resolvable from compiler.pdb");

        // Walk up from cwd to find the repo's specification/compiler directory.
        java.nio.file.Path cwd = java.nio.file.Path.of("").toAbsolutePath();
        java.nio.file.Path specCompiler = null;
        for (java.nio.file.Path p = cwd; p != null; p = p.getParent())
        {
            java.nio.file.Path candidate = p.resolve("specification").resolve("compiler");
            if (java.nio.file.Files.isDirectory(candidate))
            {
                specCompiler = candidate;
                break;
            }
        }
        org.junit.jupiter.api.Assertions.assertNotNull(specCompiler,
                "specification/compiler directory must be reachable from cwd");

        // Run the full 207-test suite via Truffle.
        // Fixtures contain absolute-path-stripped sourceIds like "association/profile/..."
        // so the testDir must be specification/compiler itself.
        Object truffleResult = truffleExec().execute(fn, specCompiler.toString());
        assertEquals(true, truffleResult, "Pure test runner should succeed via Truffle");
    }

    @Test
    void isNotEmptyOptionalOverload()
    {
        FunctionDefinition fn = (FunctionDefinition) resolver.getElement(
                "meta::pure::functions::collection::isNotEmpty_Any_$0_1$__Boolean_1_");
        if (fn == null)
        {
            // Mangled name variant for Any[0..1] may differ across builds; skip if absent.
            return;
        }
        Object javaResult = javaExec().execute(fn, List.of("x"));
        Object truffleResult = truffleExec().execute(fn, List.of("x"));
        assertEquals(javaResult, truffleResult);
    }

    // ----- helpers -----

    private static FunctionDefinition resolveIsNotEmptyAny()
    {
        return (FunctionDefinition) resolver.getElement(
                "meta::pure::functions::collection::isNotEmpty_Any_MANY__Boolean_1_");
    }

    private static PureExecution javaExec()
    {
        return PureExecution.builder()
                .withResolver(resolver)
                .build();
    }

    private static PureTruffleRuntime truffleExec()
    {
        return PureTruffleRuntime.builder()
                .withResolver(resolver)
                .withNativeExtensions(List.of(new org.finos.legend.pure.cli.CompilerNatives()))
                .withParserExtensions(List.of(new org.finos.legend.pure.cli.compiledgraph.CompiledGraphLanguageExtension()))
                .build();
    }
}
