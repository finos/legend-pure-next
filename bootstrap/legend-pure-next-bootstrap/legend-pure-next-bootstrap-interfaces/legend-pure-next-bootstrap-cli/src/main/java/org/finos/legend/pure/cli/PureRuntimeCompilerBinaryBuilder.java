// Copyright 2024 Goldman Sachs
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

package org.finos.legend.pure.cli;

import org.eclipse.collections.api.factory.Lists;
import org.eclipse.collections.api.list.MutableList;
import org.finos.legend.pure.execution.InterpretedExecution;
import org.finos.legend.pure.m3.LanguageExtension;
import org.finos.legend.pure.m3.module.Module;
import org.finos.legend.pure.m3.module.ModuleRegistry;
import org.finos.legend.pure.m3.module.TestElementFilter;
import org.finos.legend.pure.m3.module.pdbModule.PdbModule;
import org.finos.legend.pure.m3.pureLanguage.PureLanguageExtension;
import org.finos.legend.pure.runtime.PureRuntime;
import org.finos.legend.pure.runtime.compilation.PureCompilation;
import platform.pure.metamodel.function.FunctionWithParameters;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * {@code pure-bootstrap compile-via-pure}: runs
 * {@code platform::pure::compiler::archive::compileModule}, what every host's {@code compile} runs, on the
 * bootstrap's interpreter. {@link CompilerBinaryBuilder}, the Java compiler, is what it is compared with.
 */
public final class PureRuntimeCompilerBinaryBuilder
{
    private static final String COMPILE_MODULE =
            "platform::pure::compiler::archive::compileModule_String_MANY__String_1__String_1__CompilerExtension_MANY__String_MANY_";

    private PureRuntimeCompilerBinaryBuilder()
    {
    }

    /**
     * Compile {@code sourceDirs} against the given base PDBs (among them {@code core.pdb} and {@code compiler.pdb})
     * and write the result into {@code outputDir}, with filename derived from the module manifest's {@code name}.
     */
    public static void compile(List<Path> basePdbs, List<Path> sourceDirs, Path outputDir, TestElementFilter.Mode mode) throws IOException
    {
        if (basePdbs.isEmpty())
        {
            throw new IllegalArgumentException("At least one --base-pdb is required (need core.pdb plus an existing compiler.pdb to host the compiler)");
        }
        MutableList<Module> modules = Lists.mutable.empty();
        for (Path p : basePdbs)
        {
            modules.add(new PdbModule(p, PdbModule.Mode.EXECUTION));
        }
        MutableList<LanguageExtension> extensions = Lists.mutable.with(new PureLanguageExtension());
        ModuleRegistry registry = new ModuleRegistry(modules);
        registry.attach(extensions);

        // 2. Build the bootstrap runtime. The Pure compiler (compiler.pdb) is the compilation
        // strategy and runs through the interpreted execution; the language extensions parse
        // the `###CompiledGraph`, `###CompilerStats`, … sections of the canonical compiler-pure sources.
        PureRuntime runtime = PureRuntime.builder()
                .withRegistry(registry)
                .withLanguageExtensions(List.of(
                        new org.finos.legend.pure.m3.extensions.compiledgraph.CompiledGraphLanguageExtension(),
                        new org.finos.legend.pure.m3.extensions.compilerstats.CompilerStatsLanguageExtension(),
                        new org.finos.legend.pure.m3.extensions.testfile.TestFileLanguageExtension(),
                        new org.finos.legend.pure.m3.extensions.error.ErrorLanguageExtension()))
                .withCompilation(PureCompilation.builder())
                .withExecution(InterpretedExecution.builder()
                        .withNativeExtensions(Lists.mutable.with(new CompilerNatives())))
                .build();

        runtime.execute((FunctionWithParameters) registry.getElement(COMPILE_MODULE),
                Lists.mutable.withAll(sourceDirs).collect(dir -> dir.toAbsolutePath().normalize().toString()),
                outputDir.toAbsolutePath().toString(),
                mode.name().toLowerCase(Locale.ROOT),
                runtime.compilerExtensions());
    }
}
