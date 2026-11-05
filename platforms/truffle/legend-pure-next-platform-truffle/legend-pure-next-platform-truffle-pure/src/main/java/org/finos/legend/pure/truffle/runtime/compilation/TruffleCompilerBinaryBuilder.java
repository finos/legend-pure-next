// Copyright 2026 Goldman Sachs
// ©2026 JP Morgan Chase & Co. All rights reserved.
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

package org.finos.legend.pure.truffle.runtime.compilation;

import org.eclipse.collections.impl.list.mutable.ListAdapter;
import org.finos.legend.pure.truffle.execution.PureStackFormatter;
import org.finos.legend.pure.truffle.compiler.module.ModuleRegistry;
import org.finos.legend.pure.truffle.compiler.module.pdbModule.PdbModule;
import org.finos.legend.pure.truffle.compiler.module.TestElementFilter;
import org.finos.legend.pure.truffle.runtime.PureRuntime;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * {@code pure-truffle compile}: boots the Truffle runtime on the base PDBs and runs
 * {@code platform::pure::compiler::pdb::archive::compileModule}, what every host's {@code compile} runs,
 * so that a difference between their PDBs is a difference between the hosts.
 */
public final class TruffleCompilerBinaryBuilder
{
    private static final String COMPILE_MODULE_FN_PATH =
            "platform::pure::compiler::pdb::archive::compileModule_String_MANY__String_1__String_1__CompilerExtension_MANY__String_MANY_";

    private TruffleCompilerBinaryBuilder()
    {
    }

    /**
     * Compile the {@code .pure} files under {@code sourceDirs} (in order) against the given base PDBs and write to
     * {@code outputDir} (filename derived from the module manifest), with optional tests-companion via {@code mode}.
     *
     * <p>{@code languages} are the extensions the module's sections parse and compile with — {@code ###Pure}
     * is always added — and an argument rather than something a customizer may forget. {@code runtimeCustomizer} lets the
     * CLI forward {@code --source-root} and {@code --cpu-sampler*} options.</p>
     */
    public static void compile(List<Path> basePdbs, List<Path> sourceDirs, Path outputDir,
                               TestElementFilter.Mode mode,
                               org.eclipse.collections.api.list.ListIterable<org.finos.legend.pure.truffle.grammar.Language> languages,
                               Consumer<PureRuntime.Builder> runtimeCustomizer) throws IOException
    {
        if (basePdbs.isEmpty())
        {
            throw new IllegalArgumentException("At least one --base-pdb is required");
        }
        if (sourceDirs.isEmpty())
        {
            throw new IllegalArgumentException("At least one --source is required");
        }
        for (Path sourceDir : sourceDirs)
        {
            if (!Files.isDirectory(sourceDir))
            {
                throw new IllegalArgumentException("source dir does not exist: " + sourceDir);
            }
        }
        // 1. Load base PDBs — each carries its identity (name + dependencies)
        // in its embedded manifest, so the registry can cascade-invalidate on
        // unregister without us having to fabricate dep chains here.
        List<PdbModule> loaders = new ArrayList<>();
        ModuleRegistry resolver = new ModuleRegistry();
        for (Path p : basePdbs)
        {
            PdbModule loader = new PdbModule(p);
            loaders.add(loader);
            resolver.register(loader);
        }
        for (PdbModule loader : loaders)
        {
            loader.setResolver(resolver);
        }
        // 2. Boot the truffle runtime. Section parsers (CompiledGraph /
        // CompilerStats / TestFile / ReverseIndex / Error) come from
        // compiler-pure's testSectionParsers() via TrufflePureParser's
        // Pure-side registry. The LANGUAGES (###Pure, ###Diagram, …) are the
        // caller's argument; their compiler passes reach compile() below via
        // compilerExtensions().
        PureRuntime.Builder runtimeBuilder = PureRuntime.builder()
                .withRegistry(resolver)
                .withLanguages(languages)
                .withNatives(org.finos.legend.pure.truffle.execution.natives.NativeRegistry.createDefault());
        runtimeCustomizer.accept(runtimeBuilder);
        PureRuntime runtime = runtimeBuilder.build();
        // Decoding needs the languages' schema, which build() installs: preload after it.
        for (PdbModule loader : loaders)
        {
            loader.preloadAll();
        }

        Object compileModule = resolver.getElement(COMPILE_MODULE_FN_PATH);
        if (compileModule == null)
        {
            throw new IllegalStateException("compileModule not loaded: compiler.pdb must be among the base PDBs");
        }
        try
        {
            try
            {
                runtime.execute(compileModule,
                        ListAdapter.adapt(sourceDirs).collect(dir -> dir.toAbsolutePath().normalize().toString()),
                        outputDir.toAbsolutePath().toString(),
                        mode.name().toLowerCase(java.util.Locale.ROOT),
                        runtime.compilerExtensions());
            }
            catch (Throwable t)
            {
                // Print the Pure call stack — PureException carries Truffle Node locations and the polyglot
                // stack records each Pure frame with file:line:col. Without this, the JVM's default
                // uncaught-exception handler only renders Java frames.
                String pureStack = PureStackFormatter.format(t);
                if (!pureStack.isEmpty())
                {
                    System.err.println(pureStack);
                }
                throw t;
            }
        }
        finally
        {
            // Closing the polyglot context flushes any attached engine tools
            // (cpusampler, etc.) so their reports actually print, and any
            // in-flight Graal compilations write `opt failed` events into
            // the captured err buffer before we scan it below.
            runtime.close();
            failOnGraalCompilationFailures(runtime);
        }
    }

    /**
     * Tripwire: every Graal compilation Truffle attempted during compilation
     * must succeed — *except* for the HotSpot code-installation size ceiling,
     * which no engine option can lift and which gracefully degrades the
     * affected lambda to Tier-1. All failures are still printed for
     * visibility, but only actionable ones cause the throw. The caller
     * (e.g. {@code pure-truffle compile}) propagates the throw and exits
     * non-zero. No-op under native-image AOT (no JIT events).
     */
    private static void failOnGraalCompilationFailures(PureRuntime runtime)
    {
        java.util.List<String> all = runtime.graalCompilationFailures();
        java.util.List<String> actionable = runtime.graalActionableCompilationFailures();
        if (all.isEmpty())
        {
            return;
        }
        int tolerated = all.size() - actionable.size();
        if (tolerated > 0)
        {
            System.err.println("[graal-tripwire] tolerated " + tolerated
                    + " unavoidable code-size bailout(s); affected lambdas stay Tier-1.");
        }
        if (actionable.isEmpty())
        {
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("Graal compilation failures: ").append(actionable.size())
                .append(" of ").append(runtime.graalCompilationAttempts()).append(" attempts. Top failures:\n");
        actionable.stream().limit(10).forEach(line -> sb.append(line).append('\n'));
        sb.append("To diagnose, re-run with `-Dpolyglot.engine.CompilationFailureAction=Diagnose` ")
                .append("and inspect the produced graal_dumps/ directory.");
        throw new RuntimeException(sb.toString());
    }
}
