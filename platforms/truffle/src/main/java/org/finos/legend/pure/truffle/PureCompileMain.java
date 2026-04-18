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
import org.finos.legend.pure.cli.CompilerNatives;
import org.finos.legend.pure.cli.compiledgraph.CompiledGraphLanguageExtension;
import org.finos.legend.pure.m3.PureModel;
import org.finos.legend.pure.m3.module.ScopedMetadataAccess;
import org.finos.legend.pure.m3.module.pdbModule.PDBModule;
import org.finos.legend.pure.m3.pureLanguage.PureLanguageExtension;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Standalone entry point for running Pure functions through the Truffle
 * interpreter. JVM-mode today; Native Image packaging comes in Phase F.
 *
 * <p>Mirrors the {@code execute} subcommand of {@code pure-cli}:</p>
 * <pre>
 *   pure-compile execute --pdb core.pdb --pdb compiler.pdb \
 *       --function meta::pure::some::fn_... \
 *       [--args arg1 arg2 ...]
 * </pre>
 */
public final class PureCompileMain
{
    private PureCompileMain()
    {
    }

    public static void main(String[] args) throws Exception
    {
        if (args.length == 0 || "--help".equals(args[0]) || "-h".equals(args[0]))
        {
            printUsage();
            System.exit(args.length == 0 ? 1 : 0);
        }

        String command = args[0];
        String[] rest = Arrays.copyOfRange(args, 1, args.length);
        switch (command)
        {
            case "execute" -> execute(rest);
            default ->
            {
                System.err.println("Unknown command: " + command);
                printUsage();
                System.exit(1);
            }
        }
    }

    private static void printUsage()
    {
        System.err.println("Usage: pure-compile <command> [options]");
        System.err.println();
        System.err.println("Commands:");
        System.err.println("  execute --pdb <file>... --function <path> [--args <arg>...]");
        System.err.println();
        System.err.println("Runs the given Pure function through the GraalVM Truffle interpreter.");
    }

    private static void execute(String[] args) throws Exception
    {
        List<String> pdbPaths = new ArrayList<>();
        String function = null;
        List<String> fnArgs = new ArrayList<>();

        for (int i = 0; i < args.length; i++)
        {
            switch (args[i])
            {
                case "--pdb" -> pdbPaths.add(args[++i]);
                case "--function" -> function = args[++i];
                case "--args" ->
                {
                    while (i + 1 < args.length && !args[i + 1].startsWith("--"))
                    {
                        fnArgs.add(args[++i]);
                    }
                }
                default -> throw new IllegalArgumentException("Unknown option: " + args[i]);
            }
        }

        if (pdbPaths.isEmpty() || function == null)
        {
            System.err.println("Usage: pure-compile execute --pdb <file>... --function <path> [--args <arg>...]");
            System.exit(1);
        }

        List<PDBModule> modules = new ArrayList<>();
        List<String> moduleNames = new ArrayList<>();
        for (int i = 0; i < pdbPaths.size(); i++)
        {
            String name = "pdb" + i;
            moduleNames.add(name);
            PDBModule module = new PDBModule(
                    Path.of(pdbPaths.get(i)),
                    PDBModule.Mode.EXECUTION,
                    name,
                    "*",
                    Lists.mutable.withAll(moduleNames.subList(0, i)));
            modules.add(module);
        }

        PureModel model = PureModel.withModules(Lists.mutable.withAll(modules))
                .withExtensions(Lists.mutable.with(new PureLanguageExtension()))
                .build();
        model.compile();

        PDBModule lastModule = modules.get(modules.size() - 1);
        ScopedMetadataAccess resolver = new ScopedMetadataAccess(lastModule, model);

        PureTruffleRuntime runtime = PureTruffleRuntime.builder()
                .withResolver(resolver)
                .withNativeExtensions(Lists.mutable.with(new CompilerNatives()))
                .withParserExtensions(List.of(new CompiledGraphLanguageExtension()))
                .build();

        FunctionDefinition fd = (FunctionDefinition) lastModule.getElement(function);
        if (fd == null)
        {
            for (PDBModule mod : modules)
            {
                fd = (FunctionDefinition) mod.getElement(function);
                if (fd != null) break;
            }
        }
        if (fd == null)
        {
            System.err.println("Function not found: " + function);
            System.exit(1);
        }

        Object result = runtime.execute(fd, fnArgs.toArray());
        if (result != null)
        {
            System.out.println(result);
        }
    }
}
