// Copyright 2026 Goldman Sachs
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0

package org.finos.legend.pure.ide.truffle;

import org.finos.legend.pure.ide.PureIDEMain;

import java.util.List;

/**
 * Pure IDE entry point with the Truffle execute backend.
 *
 * <p>Wraps {@link PureIDEMain#run(List, java.util.function.BiFunction)} — the
 * bootstrap launcher boots the HTTP UI + LSP WebSocket server, resolves
 * {@code core.pdb} and {@code compiler.pdb}, and hands both to the backend
 * factory so this module can load the exact same files into a long-lived
 * {@code PureTruffleRuntime}.</p>
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
 *     [path-to-core.pdb] [path-to-compiler.pdb] [welcome-path]}
 */
public final class PureIDETruffleMain
{
    public static void main(String[] args) throws Exception
    {
        PureIDEMain.run(List.of(args), (corePdb, compilerPdb) ->
        {
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
}
