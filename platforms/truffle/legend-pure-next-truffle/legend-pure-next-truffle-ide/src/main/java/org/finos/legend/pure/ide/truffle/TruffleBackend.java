// Copyright 2026 Goldman Sachs
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0

package org.finos.legend.pure.ide.truffle;

import meta.pure.metamodel.function.FunctionDefinition;
import meta.pure.metamodel.valuespecification.ValueSpecification;
import org.eclipse.collections.api.list.MutableList;
import org.finos.legend.pure.ide.backend.PureBackend;
import org.finos.legend.pure.m3.PureModel;
import org.finos.legend.pure.m3.module.localModule.LocalModule;
import org.finos.legend.pure.truffle.PureTruffleRuntime;
import org.finos.legend.pure.truffle.runtime.TruffleCompiledGraphLanguageExtension;
import org.finos.legend.pure.truffle.runtime.TruffleCompilerStatsLanguageExtension;
import org.finos.legend.pure.truffle.runtime.TruffleModuleRegistry;
import org.finos.legend.pure.truffle.runtime.TrufflePdbLoader;
import org.finos.legend.pure.truffle.runtime.dynobj.PureObj;
import org.finos.legend.pure.truffle.types.PureSequence;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Truffle backend for the Pure IDE.
 *
 * <p>Compile + execute both run through Truffle here. On every {@link #execute}
 * call:</p>
 * <ol>
 *   <li>Each editable module's source dir is compiled via
 *       {@code meta::pure::compiler::compileDir} invoked through
 *       {@link PureTruffleRuntime#execute}. The runtime's resolver
 *       (preloaded with {@code core.pdb} + {@code compiler.pdb}) supplies
 *       cross-references.</li>
 *   <li>The aggregated {@code CompilationResult.elements} is searched for a
 *       {@code PackageableElement} whose simple name matches the
 *       Java-direct {@link FunctionDefinition}'s {@code _name()}.</li>
 *   <li>The Truffle-compiled function ({@link PureDynamicObject}) is
 *       executed via {@link PureTruffleRuntime#execute}.</li>
 * </ol>
 *
 * <p>The Java-direct {@code PureModel} + {@code FunctionDefinition} passed in
 * are only used for the function's identity (name lookup) — Truffle and
 * Java-direct produce incompatible function representations
 * ({@code UserDefinedFunctionImpl} vs {@code PureDynamicObject}), so the
 * function object itself cannot be reused across backends.</p>
 *
 * <h3>Lifecycle</h3>
 * One {@link PureTruffleRuntime} is built at backend construction and reused
 * for every execute call. After ~3s JIT warm-up on the first call subsequent
 * invocations benefit from Graal's adaptive optimization.
 */
public final class TruffleBackend implements PureBackend
{
    private static final String COMPILE_DIR_FN_PATH =
            "meta::pure::compiler::compileDir_String_1__Boolean_1__CompilationResult_1_";

    /**
     * Discard sink for compileDir's 3-line progress-bar stdout. compileDir
     * is a CLI-facing entry point and always prints; rather than capture and
     * filter the output, we route it to /dev/null and pull the structured
     * {@code CompilationResult.statistics} below.
     */
    private static final PrintStream NULL_OUT = new PrintStream(java.io.OutputStream.nullOutputStream());

    // Pinning rationale: PureTruffleRuntime enters its polyglot context on
    // the constructing thread and Truffle's AST builder uses thread-local
    // language state via PureLanguage.get(). Calling runtime.execute from a
    // different thread (e.g. the LSP server's CompletableFuture.supplyAsync
    // worker) hits a null PureContext and aborts AST-build. Route every
    // Truffle interaction (build + execute) through one executor thread so
    // the context stays valid for the runtime's lifetime.
    private final ExecutorService executor;
    private final PureTruffleRuntime runtime;
    private final TruffleModuleRegistry registry;

    public TruffleBackend(Path corePdb, Path compilerPdb) throws IOException
    {
        this.executor = Executors.newSingleThreadExecutor(r ->
        {
            Thread t = new Thread(r, "pure-truffle-backend");
            t.setDaemon(true);
            return t;
        });
        try
        {
            Object[] built = executor.submit(() ->
            {
                TrufflePdbLoader coreLoader = new TrufflePdbLoader(
                        corePdb, "core", List.of());
                TrufflePdbLoader compilerLoader = new TrufflePdbLoader(
                        compilerPdb, "compiler", List.of("core"));
                TruffleModuleRegistry reg = new TruffleModuleRegistry();
                reg.register(coreLoader);
                reg.register(compilerLoader);
                coreLoader.setResolver(reg);
                compilerLoader.setResolver(reg);
                coreLoader.preloadAll();
                compilerLoader.preloadAll();
                PureTruffleRuntime rt = PureTruffleRuntime.builder()
                        .withResolver(reg)
                        .withParserExtensions(List.of(
                                new TruffleCompiledGraphLanguageExtension(),
                                new TruffleCompilerStatsLanguageExtension(),
                                new org.finos.legend.pure.m3.extensions.error.ErrorLanguageExtension()))
                        .withCompileImmediately(false)
                        .build();
                return new Object[]{reg, rt};
            }).get();
            this.registry = (TruffleModuleRegistry) built[0];
            this.runtime = (PureTruffleRuntime) built[1];
        }
        catch (InterruptedException | ExecutionException e)
        {
            executor.shutdownNow();
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            if (cause instanceof IOException ioe) { throw ioe; }
            if (cause instanceof RuntimeException re) { throw re; }
            throw new RuntimeException("Failed to build Truffle backend", cause);
        }
    }

    @Override
    public String name()
    {
        return "truffle";
    }

    @Override
    public ExecutionResult execute(MutableList<LocalModule> editableModules,
                                   PureModel model,
                                   org.finos.legend.pure.m3.module.CompilationResult compileResult,
                                   FunctionDefinition function,
                                   ValueSpecification... args)
    {
        // compileResult is the Java-direct stats hand-off from the LSP layer.
        // Truffle doesn't use it: it runs its own compileDir and pulls stats
        // out of the Truffle-side CompilationResult.statistics field below.
        try
        {
            return executor.submit(() -> doExecute(editableModules, function, args)).get();
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
            return new ExecutionResult("", null, null, e);
        }
        catch (ExecutionException e)
        {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            return new ExecutionResult("", null, null, cause);
        }
    }

    private ExecutionResult doExecute(MutableList<LocalModule> editableModules,
                                      FunctionDefinition function,
                                      ValueSpecification... args)
    {
        // compileDir prints a 3-line progress bar via tickProgress3 — useful
        // on a CLI, noise in an IDE. Discard the compile-phase stdout entirely
        // and pull statistics structurally from CompilationResult.statistics
        // (PureSequence + PureDynamicObject fields), which is what the user
        // actually wants to see in the Compile tab. The run-phase println
        // output is captured into runBuf and surfaced in the Output tab.
        ByteArrayOutputStream runBuf = new ByteArrayOutputStream();
        PrintStream original = System.out;
        System.setOut(NULL_OUT);
        Object result = null;
        Throwable err = null;
        CompileStats compileStats = null;
        try
        {
            Object compileDirFn = registry.getElement(COMPILE_DIR_FN_PATH);
            if (compileDirFn == null)
            {
                throw new IllegalStateException(
                        "Truffle resolver missing " + COMPILE_DIR_FN_PATH
                                + " — was compiler.pdb loaded?");
            }

            // Compile each editable module's source dir via Truffle. The
            // function name is the simple form (e.g. "go__Any_MANY_"); we
            // match against the elements' _name to find the Truffle-side
            // equivalent of the Java-direct FunctionDefinition.
            //
            // The FunctionDefinition interface doesn't declare _name(), but
            // every runtime instance is also a PackageableElement (the
            // generated Impl classes implement both) — cast to access it.
            String targetName = ((meta.pure.metamodel.PackageableElement) function)._name();
            List<String> compileErrors = new ArrayList<>();
            // Stats from the last (typically only) editable module compile.
            // Multiple editable modules would each have their own stats; the
            // IDE currently surfaces a single panel, so the last wins. Easy
            // to widen to a List<CompileStats> later if needed.
            Object lastCompileResult = null;
            for (LocalModule module : editableModules)
            {
                // Skip modules whose content is already loaded in the Truffle
                // resolver as a PDB (e.g. compiler-pure → compiler.pdb).
                // Recompiling those would be 50+ files of pure work per Run.
                if (alreadyInResolver(module))
                {
                    continue;
                }
                for (Path sourceFolder : module.sourceFolders())
                {
                    Object compileResult = runtime.execute(
                            compileDirFn, sourceFolder.toAbsolutePath().toString(), Boolean.FALSE);
                    lastCompileResult = compileResult;
                    collectStrings(PureObj.read(compileResult, "errors"), compileErrors);

                    // Replace any previous in-memory registration for this
                    // module so the resolver always serves the freshest
                    // compile (subsequent Runs after an edit see new code).
                    String memModuleName = module.getName() + "-mem";
                    if (registry.module(memModuleName) != null)
                    {
                        registry.unregister(memModuleName);
                    }
                    Object elementsField = PureObj.read(compileResult, "elements");
                    if (elementsField instanceof PureSequence elements)
                    {
                        // Depend on every already-registered module so the
                        // user's elements can reference core / compiler /
                        // any other base PDB without cycle errors.
                        List<String> deps = new ArrayList<>();
                        for (org.finos.legend.pure.truffle.runtime.TruffleModule existing : registry.modules())
                        {
                            deps.add(existing.name());
                        }
                        registry.register(new TruffleInMemoryModule(memModuleName, deps, elements));
                    }
                }
            }
            // Pull structured stats off the CompilationResult — much cleaner
            // than parsing the progress-bar/stats output that compileDir
            // emits unconditionally for CLI users.
            if (lastCompileResult != null)
            {
                compileStats = readCompileStats(lastCompileResult);
            }

            String targetPath = ((meta.pure.metamodel.PackageableElement) function)._package() != null
                    ? qualifiedPath((meta.pure.metamodel.PackageableElement) function)
                    : targetName;
            Object truffleFn = registry.getElement(targetPath);
            if (!compileErrors.isEmpty())
            {
                err = new RuntimeException("Truffle compile errors:\n  "
                        + String.join("\n  ", compileErrors));
            }
            else if (truffleFn == null)
            {
                err = new RuntimeException(
                        "Function " + targetPath + " not found in Truffle resolver after compile");
            }
            else
            {
                // Switch to the run-phase buffer so go()'s println output is
                // captured separately from the compileDir progress + stats.
                System.setOut(new PrintStream(runBuf));
                result = args.length == 0
                        ? runtime.execute(truffleFn)
                        : runtime.execute(truffleFn, (Object[]) args);
            }
        }
        catch (RuntimeException e)
        {
            err = e;
        }
        finally
        {
            System.setOut(original);
        }
        // Surface the full cause chain to the IDE log so the user sees what
        // really broke instead of the wrapped one-liner.
        if (err != null)
        {
            err.printStackTrace(System.err);
        }
        return new ExecutionResult(
                runBuf.toString(StandardCharsets.UTF_8),
                compileStats,
                result,
                err);
    }

    /**
     * Read {@code CompilationResult.statistics} (a PureDynamicObject) off
     * the Truffle-compiled result and translate every field into the
     * backend-agnostic {@link CompileStats}.
     *
     * <p>The Pure-side schema declares each numeric field as
     * {@code Integer[1]}; Truffle round-trips those as boxed {@code Long} or
     * single-element {@link PureSequence} depending on call path — {@link
     * #asLong} handles both. Same idea for {@code String[1]} via
     * {@link #asString}.</p>
     */
    private static CompileStats readCompileStats(Object compileResult)
    {
        Object stats = PureObj.read(compileResult, "statistics");
        if (stats == null) { return null; }
        List<CompileStats.ElementStats> elements = new ArrayList<>();
        Object elementStatistics = PureObj.read(stats, "elementStatistics");
        if (elementStatistics instanceof PureSequence seq)
        {
            for (int i = 0, n = seq.size(); i < n; i++)
            {
                Object es = seq.getBoxed(i);
                if (es == null) { continue; }
                elements.add(new CompileStats.ElementStats(
                        asString(PureObj.read(es, "elementPath")),
                        asString(PureObj.read(es, "elementType")),
                        asLong(PureObj.read(es, "totalMillis")),
                        asLong(PureObj.read(es, "inferenceRollbacks")),
                        asLong(PureObj.read(es, "candidateEvaluations"))));
            }
        }
        return new CompileStats(
                asLong(PureObj.read(stats, "totalMillis")),
                asLong(PureObj.read(stats, "parsingMillis")),
                asLong(PureObj.read(stats, "firstPassMillis")),
                asLong(PureObj.read(stats, "secondPassMillis")),
                asLong(PureObj.read(stats, "thirdPassMillis")),
                asLong(PureObj.read(stats, "elementCount")),
                asLong(PureObj.read(stats, "sourceFileCount")),
                asLong(PureObj.read(stats, "inferenceRollbackCount")),
                asLong(PureObj.read(stats, "candidateEvaluationCount")),
                elements);
    }

    private static long asLong(Object v)
    {
        if (v instanceof Number n) { return n.longValue(); }
        if (v instanceof PureSequence s && s.size() == 1) { return asLong(s.getBoxed(0)); }
        return 0L;
    }

    private static String asString(Object v)
    {
        if (v == null) { return null; }
        if (v instanceof CharSequence cs) { return cs.toString(); }
        if (v instanceof PureSequence s && s.size() == 1) { return asString(s.getBoxed(0)); }
        return v.toString();
    }

    @Override
    public void close()
    {
        // Close the runtime on its owning thread (polyglot context leaves
        // need to happen on the same thread that entered).
        try
        {
            executor.submit(runtime::close).get();
        }
        catch (Exception ignored)
        {
        }
        executor.shutdownNow();
    }

    /**
     * Sample one element from the module and check if Truffle's registry can
     * already resolve it. If yes, the module's source is redundant with a
     * loaded PDB and we skip recompiling it.
     */
    private boolean alreadyInResolver(LocalModule module)
    {
        for (String path : module.elementPaths())
        {
            return registry.getElement(path) != null;
        }
        return false;
    }

    /**
     * Build the qualified Pure path for a Java-direct PackageableElement so we
     * can look up its Truffle equivalent in the registry. Mirrors the
     * compiler-pure naming convention: package chain joined by {@code ::}
     * then {@code ::} + element name.
     */
    private static String qualifiedPath(meta.pure.metamodel.PackageableElement pe)
    {
        StringBuilder sb = new StringBuilder();
        meta.pure.metamodel.Package p = pe._package();
        // Walk the package chain root-first, skipping the "::" root sentinel.
        java.util.ArrayList<String> stack = new java.util.ArrayList<>();
        while (p != null)
        {
            String n = p._name();
            if (n != null && !"::".equals(n))
            {
                stack.add(n);
            }
            p = p._package();
        }
        for (int i = stack.size() - 1; i >= 0; i--)
        {
            if (sb.length() > 0) { sb.append("::"); }
            sb.append(stack.get(i));
        }
        if (sb.length() > 0) { sb.append("::"); }
        sb.append(pe._name());
        return sb.toString();
    }

    private static void collectStrings(Object maybeSeq, List<String> sink)
    {
        if (!(maybeSeq instanceof PureSequence seq))
        {
            return;
        }
        for (int i = 0, n = seq.size(); i < n; i++)
        {
            Object s = seq.getBoxed(i);
            sink.add(s == null ? "" : s.toString());
        }
    }
}
