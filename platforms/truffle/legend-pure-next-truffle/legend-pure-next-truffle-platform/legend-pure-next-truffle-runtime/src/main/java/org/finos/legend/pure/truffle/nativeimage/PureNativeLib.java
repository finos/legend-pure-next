// Copyright 2026 Goldman Sachs
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

package org.finos.legend.pure.truffle.nativeimage;

import org.eclipse.collections.api.factory.Lists;
import org.finos.legend.pure.m3.PureModel;
import org.finos.legend.pure.m3.module.pdbModule.PDBModule;
import org.finos.legend.pure.m3.pureLanguage.PureLanguageExtension;
import org.finos.legend.pure.truffle.PureTruffleRuntime;
import org.finos.legend.pure.truffle.runtime.TrufflePdbLoader;
import org.finos.legend.pure.truffle.runtime.TruffleModuleRegistry;
import org.graalvm.nativeimage.IsolateThread;
import org.graalvm.nativeimage.c.function.CEntryPoint;
import org.graalvm.nativeimage.c.type.CCharPointer;
import org.graalvm.nativeimage.c.type.CTypeConversion;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Native-library entry points for embedding Pure in a host process (JVM,
 * Python, native CLI, etc.). Built as a shared library via the
 * {@code native-lib} Maven profile — produces {@code libpure-truffle.dylib}
 * / {@code .so} / {@code .dll} alongside the standalone executable.
 *
 * <p>Two embedded PDBs ship with the library: {@code core.pdb} and
 * {@code compiler.pdb}. They're loaded into a process-wide singleton on
 * first call so that subsequent invocations skip the FB load. Additional
 * PDBs may be passed by the caller per-invocation (e.g. application-specific
 * domain models).</p>
 *
 * <h3>Exported C ABI</h3>
 * <pre>
 *   int  pure_compile(IsolateThread*, const char* sourceDir,
 *                     const char* outputPdb, const char* extraPdbsCsv);
 *   char* pure_execute(IsolateThread*, const char* functionPath,
 *                     const char* extraPdbsCsv, const char* argsCsv);
 *   void pure_free_string(IsolateThread*, char*);
 * </pre>
 *
 * <p>All string parameters are UTF-8 null-terminated. {@code extraPdbsCsv}
 * may be the empty string. Caller must call {@code pure_free_string} on the
 * pointer returned by {@code pure_execute} once done.</p>
 */
public final class PureNativeLib
{
    private PureNativeLib() {}

    /** Embedded PDB resource names (under {@code src/main/resources/}). */
    private static final String EMBEDDED_CORE = "/embedded/core.pdb";
    private static final String EMBEDDED_COMPILER = "/embedded/compiler.pdb";

    /** Process-wide PDB cache. Populated on first {@link #ensureLoaded} call. */
    private static volatile Path embeddedCorePath;
    private static volatile Path embeddedCompilerPath;
    private static final Object LOAD_LOCK = new Object();

    /**
     * Compile a Pure source directory against the embedded {@code core} +
     * {@code compiler} PDBs and any additional caller-supplied PDBs.
     *
     * @return 0 on success; 1 on compilation error; 2 on I/O error.
     */
    @CEntryPoint(name = "pure_compile")
    public static int pureCompile(IsolateThread thread,
                                  CCharPointer sourceDir,
                                  CCharPointer outputDir,
                                  CCharPointer extraPdbsCsv)
    {
        try
        {
            ensureLoaded();
            String src = CTypeConversion.toJavaString(sourceDir);
            String out = CTypeConversion.toJavaString(outputDir);
            List<Path> basePdbs = new ArrayList<>();
            basePdbs.add(embeddedCorePath);
            basePdbs.add(embeddedCompilerPath);
            for (String p : splitCsv(CTypeConversion.toJavaString(extraPdbsCsv)))
            {
                basePdbs.add(Path.of(p));
            }
            // Native-image entry point — always builds the lean PDB shape.
            // Test-pdb production isn't a native-image use case (callers want
            // a self-contained core+compiler image to run user code against).
            // The "outputDir" C param holds the directory where <name>.pdb will
            // be written (filename derived from module manifest).
            org.finos.legend.pure.truffle.runtime.TruffleCompilerBinaryBuilder.compile(
                    basePdbs, Path.of(src), Path.of(out),
                    org.finos.legend.pure.m3.module.TestElementFilter.Mode.NONE,
                    b -> {});
            return 0;
        }
        catch (RuntimeException e)
        {
            System.err.println("[pure_compile] " + e.getMessage());
            return 1;
        }
        catch (Exception e)
        {
            System.err.println("[pure_compile] " + e.getClass().getName() + ": " + e.getMessage());
            return 2;
        }
    }

    /**
     * Execute a Pure function from the embedded PDBs (plus any caller-supplied
     * extras). Returns the string representation of the result, or
     * {@code WordFactory.nullPointer()} on error. Caller must call
     * {@link #freeString} when done.
     */
    @CEntryPoint(name = "pure_execute")
    public static CCharPointer pureExecute(IsolateThread thread,
                                           CCharPointer functionPath,
                                           CCharPointer extraPdbsCsv,
                                           CCharPointer argsCsv)
    {
        try
        {
            ensureLoaded();
            String fn = CTypeConversion.toJavaString(functionPath);
            List<String> pdbPaths = new ArrayList<>();
            pdbPaths.add(embeddedCorePath.toString());
            pdbPaths.add(embeddedCompilerPath.toString());
            for (String p : splitCsv(CTypeConversion.toJavaString(extraPdbsCsv)))
            {
                pdbPaths.add(p);
            }
            List<String> args = splitCsv(CTypeConversion.toJavaString(argsCsv));
            String result = executeWithPdbs(pdbPaths, fn, args);
            return CTypeConversion.toCString(result == null ? "" : result).get();
        }
        catch (RuntimeException e)
        {
            System.err.println("[pure_execute] " + e.getMessage());
            return org.graalvm.word.WordFactory.nullPointer();
        }
        catch (Exception e)
        {
            System.err.println("[pure_execute] " + e.getClass().getName() + ": " + e.getMessage());
            return org.graalvm.word.WordFactory.nullPointer();
        }
    }

    /** Free a CCharPointer previously returned by {@link #pureExecute}. */
    @CEntryPoint(name = "pure_free_string")
    public static void freeString(IsolateThread thread, CCharPointer ptr)
    {
        if (ptr.isNonNull())
        {
            org.graalvm.nativeimage.UnmanagedMemory.free(ptr);
        }
    }

    private static String executeWithPdbs(List<String> pdbPaths, String function, List<String> args)
            throws Exception
    {
        // Each PDB carries its own identity in its embedded manifest.
        List<PDBModule> modules = new ArrayList<>();
        for (String p : pdbPaths)
        {
            modules.add(new PDBModule(Path.of(p), PDBModule.Mode.EXECUTION));
        }
        PureModel model = PureModel.withModules(Lists.mutable.withAll(modules))
                .withExtensions(Lists.mutable.with(new PureLanguageExtension()))
                .build();
        model.compile();

        List<TrufflePdbLoader> loaders = new ArrayList<>();
        TruffleModuleRegistry resolver = new TruffleModuleRegistry();
        for (String p : pdbPaths)
        {
            TrufflePdbLoader loader = new TrufflePdbLoader(Path.of(p));
            resolver.register(loader);
            loaders.add(loader);
        }
        for (var l : loaders) l.setResolver(resolver);
        for (var l : loaders) l.preloadAll();

        // Section parsers come from compiler-pure's testSectionParsers() via
        // the Pure-side registry — no Java extensions needed.
        PureTruffleRuntime runtime = PureTruffleRuntime.builder()
                .withResolver(resolver)
                .build();
        try
        {
            Object fd = resolver.getElement(function);
            if (fd == null)
            {
                for (PDBModule mod : modules)
                {
                    fd = mod.getElement(function);
                    if (fd != null) break;
                }
            }
            if (fd == null)
            {
                throw new RuntimeException("Function not found: " + function);
            }
            Object result = runtime.execute(fd, args.toArray());
            return result == null ? null : String.valueOf(result);
        }
        finally
        {
            runtime.close();
        }
    }

    /**
     * Materialise the embedded {@code core.pdb} and {@code compiler.pdb}
     * resources to a temp directory the first time we're called. Subsequent
     * calls reuse the paths. Idempotent and thread-safe.
     */
    private static void ensureLoaded() throws IOException
    {
        if (embeddedCorePath != null) return;
        synchronized (LOAD_LOCK)
        {
            if (embeddedCorePath != null) return;
            Path tmp = Files.createTempDirectory("pure-native-lib-");
            tmp.toFile().deleteOnExit();
            Path core = extractResource(EMBEDDED_CORE, tmp.resolve("core.pdb"));
            Path comp = extractResource(EMBEDDED_COMPILER, tmp.resolve("compiler.pdb"));
            embeddedCorePath = core;
            embeddedCompilerPath = comp;
        }
    }

    private static Path extractResource(String resource, Path dest) throws IOException
    {
        try (InputStream in = PureNativeLib.class.getResourceAsStream(resource))
        {
            if (in == null)
            {
                throw new IOException("Embedded resource missing: " + resource
                        + " (was the native-lib build run with shared/core.pdb + shared/compiler.pdb copied into src/main/resources/embedded/?)");
            }
            Files.copy(in, dest);
            dest.toFile().deleteOnExit();
            return dest;
        }
    }

    private static List<String> splitCsv(String s)
    {
        if (s == null || s.isEmpty()) return List.of();
        List<String> out = new ArrayList<>();
        for (String t : s.split(","))
        {
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }
}
