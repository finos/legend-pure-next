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
import org.finos.legend.pure.m3.extensions.compiledgraph.CompiledGraphLanguageExtension;
import org.finos.legend.pure.m3.extensions.compilerstats.CompilerStatsLanguageExtension;
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
            case "compile-via-pure" -> compileViaPure(rest);
            case "execute" -> execute(rest);
            case "diff-pdb" -> diffPdb(rest);
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
        System.err.println("Usage: pure-bootstrap <command> [options]");
        System.err.println();
        System.err.println("Commands:");
        System.err.println("  compile-spec --m3-ttl <file> <sourceDir...> <output.pdb>   Compile specification Pure sources into core.pdb");
        System.err.println("  compile --base-pdb <file> --source <dir> --output <file>   Compile Pure sources against a base PDB (Java compiler)");
        System.err.println("  compile-via-pure --base-pdb <file>... --source <dir> --output <file>   Compile Pure sources by running compiler-pure on the Java runtime");
        System.err.println();
        System.err.println("  All compile commands auto-discover module.json by walking up from the source dir.");
        System.err.println("  execute --pdb <file>... --function <path> [--args <arg>...]   Execute a Pure function");
        System.err.println("  diff-pdb [--deep] <a.pdb> <b.pdb>                          Compare two PDB archives (path-set + per-element byte hash; --deep walks typed properties)");
    }

    private static void diffPdb(String[] args) throws Exception
    {
        boolean deep = false;
        Path corePdb = null;
        List<String> positional = new ArrayList<>();
        for (int i = 0; i < args.length; i++)
        {
            String arg = args[i];
            if ("--deep".equals(arg)) deep = true;
            else if ("--core".equals(arg) && i + 1 < args.length) corePdb = Path.of(args[++i]);
            else positional.add(arg);
        }
        if (positional.size() != 2)
        {
            System.err.println("Usage: pure-bootstrap diff-pdb [--deep] [--core <core.pdb>] <a.pdb> <b.pdb>");
            System.exit(1);
        }
        Path a = Path.of(positional.get(0));
        Path b = Path.of(positional.get(1));
        boolean clean;
        if (deep)
        {
            clean = org.finos.legend.pure.m3.module.pdbModule.diff.PdbDeepDiffer
                    .diff(a, b, corePdb, System.out).isClean();
        }
        else
        {
            clean = org.finos.legend.pure.m3.module.pdbModule.diff.PdbDiffer
                    .diff(a, b, System.out).isClean();
        }
        // Exit 0 if PDBs are identical, 1 otherwise — useful for CI gates.
        System.exit(clean ? 0 : 1);
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
            System.err.println("Usage: pure-bootstrap compile-spec --m3-ttl <file> <sourceDir...> <output.pdb>");
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
            System.err.println("Usage: pure-bootstrap compile --base-pdb <file> --source <dir> --output <file>");
            System.exit(1);
        }

        CompilerBinaryBuilder.compile(Path.of(basePdb), Path.of(source), Path.of(output));
    }

    /**
     * {@code compile-via-pure} — run the Pure-language compiler on the Java
     * runtime to produce a PDB. Mirrors the Truffle CLI's {@code compile}
     * subcommand structurally (multi-{@code --base-pdb}, {@code --source},
     * {@code --output}) but runs through {@link PureRuntimeCompilerBinaryBuilder}.
     */
    private static void compileViaPure(String[] args) throws Exception
    {
        List<String> basePdbs = new ArrayList<>();
        String source = null;
        String output = null;

        for (int i = 0; i < args.length; i++)
        {
            switch (args[i])
            {
                case "--base-pdb" -> basePdbs.add(args[++i]);
                case "--source" -> source = args[++i];
                case "--output" -> output = args[++i];
                default -> throw new IllegalArgumentException("Unknown option: " + args[i]);
            }
        }

        if (basePdbs.isEmpty() || source == null || output == null)
        {
            System.err.println("Usage: pure-bootstrap compile-via-pure --base-pdb <file>... --source <dir> --output <file>");
            System.err.println("  Pass at least core.pdb plus an existing compiler.pdb so the");
            System.err.println("  Pure compile_PureFile_… function is on the resolver.");
            System.exit(1);
        }

        List<Path> basePaths = new ArrayList<>();
        for (String b : basePdbs)
        {
            basePaths.add(Path.of(b));
        }
        PureRuntimeCompilerBinaryBuilder.compile(basePaths, Path.of(source), Path.of(output));
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
            System.err.println("Usage: pure-bootstrap execute --pdb <file>... --function <path> [--args <arg>...]");
            System.exit(1);
        }

        // Load PDB modules — each one carries its identity in its embedded manifest.
        List<PDBModule> modules = new ArrayList<>();
        List<String> loadedNames = new ArrayList<>();
        for (String pdbPath : pdbPaths)
        {
            PDBModule module = new PDBModule(Path.of(pdbPath), PDBModule.Mode.EXECUTION);
            modules.add(module);
            loadedNames.add(module.getName());
        }
        // Validate that every declared dep is among the loaded modules.
        for (PDBModule module : modules)
        {
            for (String dep : module.getDependencies())
            {
                if (!loadedNames.contains(dep))
                {
                    throw new IllegalArgumentException("Module '" + module.getName()
                            + "' declares dependency '" + dep + "' but it was not loaded (loaded: " + loadedNames + ")");
                }
            }
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
                .withParserExtensions(List.of(new CompiledGraphLanguageExtension(), new CompilerStatsLanguageExtension()))
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
