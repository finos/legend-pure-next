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

package org.finos.legend.pure.cli;

import meta.pure.metamodel.function.FunctionDefinition;
import org.eclipse.collections.api.factory.Lists;
import org.finos.legend.pure.execution.PureExecution;
import org.finos.legend.pure.m3.PureModel;
import org.finos.legend.pure.m3.SpecificationBinaryBuilder;
import org.finos.legend.pure.m3.module.ScopedMetadataAccess;
import org.finos.legend.pure.m3.module.pdbModule.PDBModule;
import org.finos.legend.pure.m3.pureLanguage.PureLanguageExtension;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class PureCLI
{
    public static void main(String[] args) throws Exception
    {
        if (args.length == 0)
        {
            printUsage();
            System.exit(1);
        }

        String command = args[0];
        String[] rest = Arrays.copyOfRange(args, 1, args.length);

        switch (command)
        {
            case "compile-spec" -> compileSpec(rest);
            case "compile" -> compile(rest);
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
        System.err.println("Usage: pure-cli <command> [options]");
        System.err.println();
        System.err.println("Commands:");
        System.err.println("  compile-spec --m3-ttl <file> <sourceDir...> <output.pdb>   Compile specification Pure sources into core.pdb");
        System.err.println("  compile --base-pdb <file> --source <dir> --output <file>   Compile Pure sources against a base PDB");
        System.err.println("  execute --pdb <file>... --function <path> [--args <arg>...]   Execute a Pure function");
    }

    private static void compileSpec(String[] args) throws Exception
    {
        Path m3TtlPath = null;
        List<String> positional = new ArrayList<>();
        for (int i = 0; i < args.length; i++)
        {
            if ("--m3-ttl".equals(args[i]))
            {
                m3TtlPath = Path.of(args[++i]);
            }
            else
            {
                positional.add(args[i]);
            }
        }
        if (m3TtlPath == null || positional.size() < 2)
        {
            System.err.println("Usage: pure-cli compile-spec --m3-ttl <file> <sourceDir...> <output.pdb>");
            System.exit(1);
        }
        List<Path> sourceDirs = new ArrayList<>();
        for (int i = 0; i < positional.size() - 1; i++)
        {
            sourceDirs.add(Path.of(positional.get(i)));
        }
        Path output = Path.of(positional.get(positional.size() - 1));
        SpecificationBinaryBuilder.compile(m3TtlPath, sourceDirs, output);
    }

    private static void compile(String[] args) throws Exception
    {
        String basePdb = null;
        String source = null;
        String output = null;

        for (int i = 0; i < args.length; i++)
        {
            switch (args[i])
            {
                case "--base-pdb" -> basePdb = args[++i];
                case "--source" -> source = args[++i];
                case "--output" -> output = args[++i];
                default -> throw new IllegalArgumentException("Unknown option: " + args[i]);
            }
        }

        if (basePdb == null || source == null || output == null)
        {
            System.err.println("Usage: pure-cli compile --base-pdb <file> --source <dir> --output <file>");
            System.exit(1);
        }

        CompilerBinaryBuilder.compile(Path.of(basePdb), Path.of(source), Path.of(output));
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
            System.err.println("Usage: pure-cli execute --pdb <file>... --function <path> [--args <arg>...]");
            System.exit(1);
        }

        // Load PDB modules
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

        // Resolve function from last module
        PDBModule lastModule = modules.get(modules.size() - 1);
        ScopedMetadataAccess resolver = new ScopedMetadataAccess(lastModule, model);

        PureExecution execution = PureExecution.builder()
                .withResolver(resolver)
                .withNativeExtensions(Lists.mutable.with(new CompilerNatives()))
                .withParserExtensions(List.of(new org.finos.legend.pure.cli.compiledgraph.CompiledGraphLanguageExtension()))
                .build();

        FunctionDefinition fd = (FunctionDefinition) lastModule.getElement(function);
        if (fd == null)
        {
            // Try searching all modules
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

        Object result;
        if (fnArgs.isEmpty())
        {
            result = execution.execute(fd);
        }
        else
        {
            result = execution.execute(fd, fnArgs.toArray());
        }

        if (result != null)
        {
            System.out.println(result);
        }
    }
}
