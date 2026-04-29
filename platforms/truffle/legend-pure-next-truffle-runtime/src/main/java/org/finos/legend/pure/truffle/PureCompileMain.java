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

import org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.function.FunctionDefinition;
import org.eclipse.collections.api.factory.Lists;
import org.finos.legend.pure.m3.PureModel;
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
        System.err.println("Usage: pure-compile <command> [options]");
        System.err.println();
        System.err.println("Commands:");
        System.err.println("  compile --base-pdb <file>... --source <dir> --output <file>   Compile Pure sources against base PDB(s)");
        System.err.println("  execute --pdb <file>... --function <path> [--args <arg>...]   Execute a Pure function");
        System.err.println();
        System.err.println("Runs the Pure compiler / executor through the GraalVM Truffle interpreter.");
    }

    private static void compile(String[] args) throws Exception
    {
        List<String> basePdbPaths = new ArrayList<>();
        String source = null;
        String output = null;

        for (int i = 0; i < args.length; i++)
        {
            switch (args[i])
            {
                case "--base-pdb" -> basePdbPaths.add(args[++i]);
                case "--source" -> source = args[++i];
                case "--output" -> output = args[++i];
                default -> throw new IllegalArgumentException("Unknown option: " + args[i]);
            }
        }

        if (basePdbPaths.isEmpty() || source == null || output == null)
        {
            System.err.println("Usage: pure-compile compile --base-pdb <file>... --source <dir> --output <file>");
            System.exit(1);
        }

        List<Path> basePdbs = new ArrayList<>();
        for (String p : basePdbPaths)
        {
            basePdbs.add(Path.of(p));
        }
        org.finos.legend.pure.truffle.runtime.TruffleCompilerBinaryBuilder.compile(
                basePdbs, Path.of(source), Path.of(output));
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

        // Build truffle PDB loaders for all modules
        java.util.List<org.finos.legend.pure.truffle.runtime.TrufflePdbLoader> loaders = new java.util.ArrayList<>();
        for (int mi = 0; mi < pdbPaths.size(); mi++)
        {
            loaders.add(new org.finos.legend.pure.truffle.runtime.TrufflePdbLoader(java.nio.file.Path.of(pdbPaths.get(mi))));
        }
        // Composite resolver: later modules take priority
        org.finos.legend.pure.truffle.runtime.TruffleMetadataAccess resolver = new org.finos.legend.pure.truffle.runtime.TruffleMetadataAccess()
        {
            @Override public Object getElement(String p)
            {
                for (int li = loaders.size() - 1; li >= 0; li--)
                {
                    Object e = loaders.get(li).getElement(p);
                    if (e != null) return e;
                }
                return null;
            }
            @Override public boolean hasElement(String p)
            {
                for (var l : loaders) if (l.hasElement(p)) return true;
                return false;
            }
            @Override public java.util.Set<String> elementPaths()
            {
                java.util.Set<String> all = new java.util.LinkedHashSet<>();
                for (var l : loaders) all.addAll(l.elementPaths());
                return all;
            }
        };
        // Wire composite resolver into each loader for cross-module FBW resolution
        for (var loader : loaders) loader.setResolver(resolver);
        for (var loader : loaders) loader.preloadAll();

        PureTruffleRuntime runtime = PureTruffleRuntime.builder()
                .withResolver(resolver)
                .withParserExtensions(List.of(
                        new org.finos.legend.pure.truffle.runtime.TruffleCompiledGraphLanguageExtension(),
                        new org.finos.legend.pure.m3.extensions.error.ErrorLanguageExtension()))
                .build();

        FunctionDefinition fd = (FunctionDefinition) resolver.getElement(function);
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
