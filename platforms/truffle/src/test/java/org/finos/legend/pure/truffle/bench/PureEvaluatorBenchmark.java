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

package org.finos.legend.pure.truffle.bench;

import meta.pure.metamodel.function.FunctionDefinition;
import org.eclipse.collections.api.factory.Lists;
import org.finos.legend.pure.cli.compiledgraph.CompiledGraphLanguageExtension;
import org.finos.legend.pure.cli.CompilerNatives;
import org.finos.legend.pure.execution.PureExecution;
import org.finos.legend.pure.m3.PureModel;
import org.finos.legend.pure.m3.module.MetadataAccess;
import org.finos.legend.pure.m3.module.ScopedMetadataAccess;
import org.finos.legend.pure.m3.module.pdbModule.PDBModule;
import org.finos.legend.pure.m3.pureLanguage.PureLanguageExtension;
import org.finos.legend.pure.truffle.PureTruffleRuntime;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * JMH benchmark comparing the Java tree-walking evaluator
 * ({@link PureExecution}) against the Truffle interpreter
 * ({@link PureTruffleRuntime}) on the three parity-oracle suites.
 *
 * <p>Run with: {@code mvn -Pbench test} or directly via
 * {@code java -jar target/benchmarks.jar}.</p>
 */
@BenchmarkMode(Mode.SingleShotTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Fork(value = 1, jvmArgs = {"-Xmx2g"})
@Warmup(iterations = 3, batchSize = 1)
@Measurement(iterations = 5, batchSize = 1)
@State(Scope.Benchmark)
public class PureEvaluatorBenchmark
{
    public static void main(String[] args) throws RunnerException
    {
        Options opt = new OptionsBuilder()
                .include(PureEvaluatorBenchmark.class.getSimpleName())
                .build();
        new Runner(opt).run();
    }

    private PureExecution javaExecution;
    private PureTruffleRuntime truffleRuntime;
    private FunctionDefinition runFunctionTests;
    private FunctionDefinition runPCTTests;
    private FunctionDefinition runCompiledGraphTests;

    @Setup(Level.Trial)
    public void setup() throws Exception
    {
        Path corePdb = Path.of("../../build/core.pdb");
        Path compilerPdb = Path.of("../../build/compiler.pdb");

        PDBModule core = new PDBModule(corePdb, PDBModule.Mode.EXECUTION, "core", "*", Lists.mutable.empty());
        PDBModule compiler = new PDBModule(compilerPdb, PDBModule.Mode.EXECUTION, "compiler", "*", Lists.mutable.with("core"));
        PureModel model = PureModel.withModules(Lists.mutable.with(core, compiler))
                .withExtensions(Lists.mutable.with(new PureLanguageExtension()))
                .build();
        model.compile();

        ScopedMetadataAccess resolver = new ScopedMetadataAccess(compiler, model);

        javaExecution = PureExecution.builder()
                .withResolver(resolver)
                .withNativeExtensions(Lists.mutable.with(new CompilerNatives()))
                .withParserExtensions(List.of(new CompiledGraphLanguageExtension()))
                .build();

        truffleRuntime = PureTruffleRuntime.builder()
                .withResolver(resolver)
                .withNativeExtensions(Lists.mutable.with(new CompilerNatives()))
                .withParserExtensions(List.of(new CompiledGraphLanguageExtension()))
                .build();

        runFunctionTests = resolve(compiler, "meta::pure::test::runFunctionTests_String_1__Boolean_1_");
        runPCTTests = resolve(compiler, "meta::pure::test::runPCTTests_String_1__Boolean_1_");
        runCompiledGraphTests = resolve(compiler, "meta::pure::test::runCompiledGraphTests_String_1__Boolean_1_");
    }

    private static FunctionDefinition resolve(PDBModule module, String path)
    {
        FunctionDefinition fd = (FunctionDefinition) module.getElement(path);
        if (fd == null)
        {
            throw new RuntimeException("Function not found: " + path);
        }
        return fd;
    }

    @Benchmark
    public Object javaFunctionTests()
    {
        return javaExecution.execute(runFunctionTests, "meta::pure::functions");
    }

    @Benchmark
    public Object truffleFunctionTests()
    {
        return truffleRuntime.execute(runFunctionTests, "meta::pure::functions");
    }

    @Benchmark
    public Object javaPCTTests()
    {
        return javaExecution.execute(runPCTTests, "meta::pure::functions");
    }

    @Benchmark
    public Object trufflePCTTests()
    {
        return truffleRuntime.execute(runPCTTests, "meta::pure::functions");
    }
}
