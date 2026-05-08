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

package org.finos.legend.pure.truffle.runtime;

import org.finos.legend.pure.truffle.PureTruffleRuntime;
import org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.PackageableElement;
import org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.function.FunctionDefinition;
import org.finos.legend.pure.truffle.types.PureSequence;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.function.Consumer;

/**
 * Truffle-side analog of {@code CompilerBinaryBuilder}: compile every
 * {@code .pure} file under {@code sourceDir} via the truffle interpreter
 * (running compiler-pure), aggregate the resulting metamodel elements,
 * and write them to a {@code .pdb} archive via {@link TrufflePdbWriter}.
 *
 * <p>Unlike the bootstrap builder which uses the Java compiler, this driver
 * runs the Pure-language compiler ({@code meta::pure::compiler::compile})
 * end-to-end through Truffle. Output is a structurally-equivalent PDB
 * consumable by any reader that understands {@code m3.fbs}.</p>
 */
public final class TruffleCompilerBinaryBuilder
{
    // Mirror bootstrap: hand the entire orchestration (directory walk,
    // parse, per-file compile, package emission) to compile-pure's
    // `compileDir`. Keeping the platforms structurally identical is what
    // makes the output PDBs comparable — the only platform-specific code
    // is the runtime hosting the same Pure entry point.
    private static final String COMPILE_DIR_FN_PATH =
            "meta::pure::compiler::compileDir_String_1__Boolean_1__CompilationResult_1_";

    private TruffleCompilerBinaryBuilder()
    {
    }

    /**
     * Compile {@code sourceDir} against the given base PDBs and write to
     * {@code outputFile}. Each base PDB is loaded read-only to provide
     * cross-references; only freshly-compiled elements (not already present
     * in any base PDB) end up in the output.
     *
     * <p>The writer enforces required-property validation: any {@code [1]}
     * property that's null or any {@code [1..*]} that's empty aborts the
     * write. Surfaces compiler-pure gaps as build failures.</p>
     */
    public static void compile(List<Path> basePdbs, Path sourceDir, Path outputFile) throws IOException
    {
        compile(basePdbs, sourceDir, outputFile, b -> {});
    }

    /**
     * Variant that lets callers customise the {@link PureTruffleRuntime}
     * before it boots — used by the CLI to forward {@code --source-root}
     * and {@code --cpu-sampler*} options.
     */
    public static void compile(List<Path> basePdbs, Path sourceDir, Path outputFile,
                               Consumer<PureTruffleRuntime.Builder> runtimeCustomizer) throws IOException
    {
        if (basePdbs.isEmpty())
        {
            throw new IllegalArgumentException("At least one --base-pdb is required");
        }
        if (!Files.isDirectory(sourceDir))
        {
            throw new IllegalArgumentException("source dir does not exist: " + sourceDir);
        }

        System.out.println("Compiling Pure model from " + sourceDir
                + " (base: " + basePdbs + ") via truffle interpreter...");

        // 1. Load base PDBs and build the module registry. PDBs are listed
        // dependency-first by convention (e.g. core then compiler) — declare
        // each PDB depending on every PDB before it so the registry can
        // cascade-invalidate on unregister.
        List<TrufflePdbLoader> loaders = new ArrayList<>();
        TruffleModuleRegistry resolver = new TruffleModuleRegistry();
        List<String> priorNames = new ArrayList<>();
        for (Path p : basePdbs)
        {
            TrufflePdbLoader loader = new TrufflePdbLoader(p, deriveName(p), List.copyOf(priorNames));
            loaders.add(loader);
            resolver.register(loader);
            priorNames.add(loader.name());
        }
        for (TrufflePdbLoader loader : loaders)
        {
            loader.setResolver(resolver);
        }
        for (TrufflePdbLoader loader : loaders)
        {
            loader.preloadAll();
        }

        // 2. Boot the truffle runtime.
        PureTruffleRuntime.Builder runtimeBuilder = PureTruffleRuntime.builder()
                .withResolver(resolver)
                .withParserExtensions(List.of(
                        new TruffleCompiledGraphLanguageExtension(),
                        new TruffleCompilerStatsLanguageExtension(),
                        new org.finos.legend.pure.m3.extensions.error.ErrorLanguageExtension()));
        runtimeCustomizer.accept(runtimeBuilder);
        PureTruffleRuntime runtime = runtimeBuilder.build();

        Object compileDirObj = resolver.getElement(COMPILE_DIR_FN_PATH);
        if (!(compileDirObj instanceof FunctionDefinition compileDirFn))
        {
            throw new RuntimeException("compileDir FunctionDefinition not resolvable: "
                    + (compileDirObj == null ? "null" : compileDirObj.getClass().getName()));
        }

        try
        {
            // 3. Hand the whole walk-parse-compile-aggregate pipeline to
            // compile-pure's `compileDir` — same entry point the bootstrap
            // orchestrator uses. Keeps the platforms structurally identical:
            // every byte of orchestration logic lives in Pure, only the runtime
            // changes. Returns a `CompilationResult` whose `elements` already
            // includes the hierarchical Package set built by `buildPackages`.
            Object result;
            try
            {
                result = runtime.execute(compileDirFn, sourceDir.toAbsolutePath().toString(), Boolean.getBoolean("legend.pure.compileDebug"));
            }
            catch (Throwable t)
            {
                // compileDir's 3-line progress display parks the cursor on
                // line 1 (header) after each tickProgress3. When pass 1/2/3
                // throws (e.g. unguarded ->toOne() in a resolver) the Pure
                // side never reaches finishProgress3, so without intervention
                // the JVM's uncaught-exception handler writes straight on top
                // of the bar's last frame — looking like it ate the bar.
                // Mirror finishProgress3's behaviour: do not clear the 3 lines
                // (the user wants to see what was being processed at the
                // moment of the throw), just move the cursor below the
                // 3-line region so the exception text lands on a fresh line.
                System.out.print("\u001B[3B\r\n");
                System.out.flush();
                // Print the Pure call stack — PureException carries Truffle
                // Node locations and the polyglot stack records each Pure
                // frame with file:line:col. Without this, the JVM's default
                // uncaught-exception handler only renders Java frames, so
                // "toOne expected exactly 1 element, got 0" tells the user
                // nothing about *which* Pure call chain produced the error.
                String pureStack = PureStackFormatter.format(t);
                if (!pureStack.isEmpty())
                {
                    System.err.println(pureStack);
                }
                throw t;
            }
            if (result == null)
            {
                throw new RuntimeException("compileDir returned null");
            }

            List<String> allErrors = new ArrayList<>();
            Object errorsObj = invokeAccessor(result, "_errors");
            if (errorsObj instanceof PureSequence errSeq)
            {
                for (int i = 0; i < errSeq.size(); i++)
                {
                    allErrors.add(String.valueOf(errSeq.getBoxed(i)));
                }
            }
            if (!allErrors.isEmpty())
            {
                System.err.println("Compilation errors:");
                allErrors.forEach(e -> System.err.println("  " + e));
                throw new RuntimeException("Pure compilation failed with " + allErrors.size() + " error(s)");
            }

            // Filter Package elements that already live in the anchor (first
            // base PDB) — compile-pure's `buildPackages` expands paths to all
            // ancestors (`meta`, `meta::pure`, …) and core.pdb already owns
            // those, so re-emitting them would diverge from bootstrap's output.
            // Non-Package elements always pass through.
            LinkedHashMap<String, PackageableElement> elementsByPath = new LinkedHashMap<>();
            Object elementsObj = invokeAccessor(result, "_elements");
            if (elementsObj instanceof PureSequence elementsSeq)
            {
                TrufflePdbLoader anchor = loaders.isEmpty() ? null : loaders.get(0);
                for (int i = 0; i < elementsSeq.size(); i++)
                {
                    Object el = elementsSeq.getBoxed(i);
                    if (el instanceof PackageableElement pe)
                    {
                        String path = org.finos.legend.pure.truffle.runtime.helper._PackageableElement.path(pe);
                        if (pe instanceof org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.Package
                                && anchor != null && anchor.hasElement(path))
                        {
                            continue;
                        }
                        elementsByPath.put(path, pe);
                    }
                }
            }

            System.out.println("Compiled " + elementsByPath.size() + " elements");

            // 4. Write the aggregated elements to the output PDB.
            if (outputFile.getParent() != null)
            {
                Files.createDirectories(outputFile.getParent());
            }
            TrufflePdbWriter.write(new ArrayList<>(elementsByPath.values()), outputFile);
            System.out.println("Written: " + outputFile + " (" + Files.size(outputFile) + " bytes)");
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
     * must succeed. Throws if any {@code opt failed} events were recorded —
     * the caller (e.g. {@code pure-truffle compile}) propagates the throw
     * and exits non-zero. No-op under native-image AOT (no JIT events).
     */
    private static void failOnGraalCompilationFailures(PureTruffleRuntime runtime)
    {
        java.util.List<String> failures = runtime.graalCompilationFailures();
        if (failures.isEmpty())
        {
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("Graal compilation failures: ").append(failures.size())
                .append(" of ").append(runtime.graalCompilationAttempts()).append(" attempts. Top failures:\n");
        failures.stream().limit(10).forEach(line -> sb.append(line).append('\n'));
        sb.append("To diagnose, re-run with `-Dpolyglot.engine.CompilationFailureAction=Diagnose` ")
                .append("and inspect the produced graal_dumps/ directory.");
        throw new RuntimeException(sb.toString());
    }

    private static String deriveName(Path pdbPath)
    {
        String fileName = pdbPath.getFileName().toString();
        return fileName.endsWith(".pdb") ? fileName.substring(0, fileName.length() - 4) : fileName;
    }

    private static Object invokeAccessor(Object target, String accessor)
    {
        try
        {
            return target.getClass().getMethod(accessor).invoke(target);
        }
        catch (Exception e)
        {
            throw new RuntimeException("Failed to invoke " + accessor + " on " + target.getClass().getName(), e);
        }
    }
}
