// Copyright 2026 Goldman Sachs
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0

package org.finos.legend.pure.ide.truffle;

import org.finos.legend.pure.ide.PureIDEMain;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Pure IDE entry point with the Truffle execute backend.
 *
 * <p>Wraps {@link PureIDEMain#run(List, java.util.function.Function)} — the
 * bootstrap launcher boots the HTTP UI + LSP WebSocket server and uses the
 * supplied factory to build the backend once {@code core.pdb} is resolved.
 * This module supplies a {@link TruffleBackend} that loads {@code core.pdb}
 * and {@code compiler.pdb} into a long-lived {@code PureTruffleRuntime}.</p>
 *
 * <h3>Runtime requirements</h3>
 * Truffle's Graal compiler needs GraalVM JDK + JVMCI flags:
 * <pre>
 *   -XX:+UnlockExperimentalVMOptions -XX:+EnableJVMCI -XX:+UseJVMCICompiler
 * </pre>
 * Without those, the polyglot engine builder rejects {@code engine.*} options
 * at construction.
 *
 * <h3>Usage</h3>
 * {@code java -cp ... org.finos.legend.pure.ide.truffle.PureIDETruffleMain
 *     [--compiler-pdb <path>] [path-to-core.pdb] [compiler-pure-path] [welcome-path]}
 *
 * <p>{@code --compiler-pdb} defaults to the first {@code shared/compiler.pdb}
 * found by walking upward from the current working directory.</p>
 */
public final class PureIDETruffleMain
{
    public static void main(String[] args) throws Exception
    {
        ParsedArgs parsed = parseArgs(args);
        PureIDEMain.run(parsed.passthroughArgs, corePdb ->
        {
            Path compilerPdb = resolveCompilerPdb(parsed.compilerPdb);
            System.out.println("Loading compiler.pdb from: " + compilerPdb);
            try
            {
                return new TruffleBackend(corePdb, compilerPdb);
            }
            catch (java.io.IOException e)
            {
                throw new RuntimeException("Failed to build Truffle backend", e);
            }
        });
    }

    private static Path resolveCompilerPdb(String explicit)
    {
        if (explicit != null)
        {
            return Path.of(explicit);
        }
        Path cwd = Path.of("").toAbsolutePath();
        for (Path dir = cwd; dir != null; dir = dir.getParent())
        {
            Path candidate = dir.resolve("shared").resolve("compiler.pdb");
            if (Files.isRegularFile(candidate))
            {
                return candidate;
            }
        }
        throw new IllegalStateException(
                "Cannot locate compiler.pdb. Pass --compiler-pdb <path> or run from a "
                        + "directory whose ancestors contain shared/compiler.pdb.");
    }

    private static ParsedArgs parseArgs(String[] args)
    {
        String compilerPdb = null;
        List<String> rest = new ArrayList<>();
        for (int i = 0; i < args.length; i++)
        {
            String a = args[i];
            if ("--compiler-pdb".equals(a))
            {
                if (i + 1 >= args.length)
                {
                    throw new IllegalArgumentException("--compiler-pdb requires a path");
                }
                compilerPdb = args[++i];
            }
            else if (a.startsWith("--compiler-pdb="))
            {
                compilerPdb = a.substring("--compiler-pdb=".length());
            }
            else
            {
                rest.add(a);
            }
        }
        return new ParsedArgs(compilerPdb, rest);
    }

    private record ParsedArgs(String compilerPdb, List<String> passthroughArgs) {}
}
