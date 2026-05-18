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

    /**
     * Build the Truffle backend's own compilation graph (core.pdb +
     * compiler.pdb), independent of the IDE's edit graph. compileDir is
     * always available in this runtime, regardless of which module the user
     * is editing.
     */
    public TruffleBackend(Path pdbDir) throws IOException
    {
        Path corePdb = pdbDir.resolve("core.pdb");
        Path compilerPdb = pdbDir.resolve("compiler.pdb");
        if (!java.nio.file.Files.isRegularFile(corePdb))
        {
            throw new IOException("Truffle backend requires " + corePdb
                    + ". Run `just bootstrap::build` first.");
        }
        if (!java.nio.file.Files.isRegularFile(compilerPdb))
        {
            throw new IOException("Truffle backend requires " + compilerPdb
                    + ". Run `just bootstrap::build-compiler-pdb` first.");
        }
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
                TrufflePdbLoader coreLoader = new TrufflePdbLoader(corePdb);
                TrufflePdbLoader compilerLoader = new TrufflePdbLoader(compilerPdb);
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
                                new org.finos.legend.pure.truffle.runtime.TruffleErrorLanguageExtension()))
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
        PrintStream runStream = null;
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
                    List<String> thisCompileErrors = new ArrayList<>();
                    collectStrings(PureObj.read(compileResult, "errors"), thisCompileErrors);
                    compileErrors.addAll(thisCompileErrors);

                    // Only swap the in-memory registration when this compile
                    // succeeded. A failed compile (parse or resolver error)
                    // would otherwise unregister the previous good module
                    // and register either nothing or a partial elements list,
                    // making the NEXT F9 fail with a wall of "function X
                    // can't be found" errors that have nothing to do with
                    // the actual fix. Keeping the prior state lets the user
                    // see the real error and re-run after fixing it.
                    if (!thisCompileErrors.isEmpty())
                    {
                        continue;
                    }
                    String memModuleName = module.getName() + "-mem";
                    if (registry.module(memModuleName) != null)
                    {
                        registry.unregister(memModuleName);
                    }
                    Object elementsField = PureObj.read(compileResult, "elements");
                    if (elementsField instanceof PureSequence elements)
                    {
                        // Diagnostic: walk every element's transitive children
                        // looking for FunctionApplication/Invocation nodes
                        // whose `func` slot is null. If any exist, the
                        // compileDir output is broken — surface it BEFORE
                        // registration so the runtime walk's cryptic
                        // `_func() returned null for: or` is replaced with
                        // a concrete path + classifier the user can act on.
                        List<String> brokenFuncs = findUnresolvedFuncs(elements);
                        if (!brokenFuncs.isEmpty())
                        {
                            for (String b : brokenFuncs)
                            {
                                compileErrors.add(b);
                            }
                            continue;
                        }
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

            // POST-EVERYTHING validation: collect identityHashes of every FE
            // reachable from the registered elements. At runtime, if the
            // failing FE's identityHash isn't in this set, the runtime is
            // reading from a different object than what compileDir put in
            // module-mem. That'd narrow down where the alternate FE lives.
            java.util.Set<Integer> knownFeHashes = new java.util.HashSet<>();
            for (org.finos.legend.pure.truffle.runtime.TruffleModule mod : registry.modules())
            {
                if (!(mod instanceof TruffleInMemoryModule)) continue;
                for (String p : mod.elementPaths())
                {
                    Object el = mod.getElement(p);
                    if (el == null) continue;
                    collectFEHashes(el, knownFeHashes, new java.util.IdentityHashMap<>());
                    try
                    {
                        com.google.flatbuffers.FlatBufferBuilder b =
                                new com.google.flatbuffers.FlatBufferBuilder(1024);
                        org.finos.legend.pure.truffle.pdb.codec.GeneratedFlatBufferWriter w =
                                new org.finos.legend.pure.truffle.pdb.codec.GeneratedFlatBufferWriter(b, true);
                        w.dispatchWrite(el);
                    }
                    catch (IllegalArgumentException | IllegalStateException ex)
                    {
                        throw new RuntimeException("[post-compile " + mod.name() + "/" + p + "] " + ex.getMessage());
                    }
                }
            }
            org.finos.legend.pure.truffle.builder.PureASTBuilder.KNOWN_FE_HASHES = knownFeHashes;
            System.err.println("[diag] tracked " + knownFeHashes.size() + " FE identityHashes from module-mem");

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
                // autoFlush=true so each println lands in runBuf at the
                // newline — otherwise an exception mid-execution leaves the
                // last println(s) trapped in the PrintStream's internal buffer
                // and the user never sees their debug output.
                runStream = new PrintStream(runBuf, true);
                System.setOut(runStream);
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
            // Flush any pending output before restoring stdout, so a partial
            // println caught mid-character on the exception path still drains.
            if (runStream != null)
            {
                runStream.flush();
            }
            System.setOut(original);
        }
        // Surface the full cause chain to the IDE log so the user sees what
        // really broke instead of the wrapped one-liner.
        if (err != null)
        {
            err.printStackTrace(System.err);
            // Pure stack: PureException carries Truffle Node locations and the
            // polyglot stack records each Pure frame with file:line:col. Without
            // this the user only sees the Java stack — "toOne expected exactly
            // 1 element, got 0" tells them nothing about which Pure call chain
            // produced the error.
            String pureStack = org.finos.legend.pure.truffle.runtime.PureStackFormatter.format(err);
            if (!pureStack.isEmpty())
            {
                System.err.println(pureStack);
                // Wrap the error so the Pure stack reaches the IDE's error
                // panel (which renders ExecutionResult.error.getMessage()),
                // not just stderr.
                err = new RuntimeException(err.getMessage() + pureStack, err);
            }
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
     * True iff the module's elements are already served by a static PDB
     * loader (e.g. compiler-pure → compiler.pdb), in which case recompiling
     * the source would be redundant.
     *
     * <p>Crucially, this must NOT return true when the elements are served
     * by a {@link TruffleInMemoryModule} that we registered on a previous
     * Run — those represent user-editable source and must be re-compiled
     * so edits to welcome.pure (etc.) actually take effect. Sampling
     * {@code registry.getElement(path)} alone can't distinguish the two;
     * we have to look at the module type via {@code moduleOfPath}.</p>
     */
    private boolean alreadyInResolver(LocalModule module)
    {
        for (String path : module.elementPaths())
        {
            org.finos.legend.pure.truffle.runtime.TruffleModule owner = registry.moduleOfPath(path);
            return owner instanceof TrufflePdbLoader;
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

    private static void collectFEHashes(Object node,
                                         java.util.Set<Integer> sink,
                                         java.util.IdentityHashMap<Object, Boolean> seen)
    {
        if (!(node instanceof org.finos.legend.pure.truffle.runtime.dynobj.PureDynamicObject pdo)) return;
        if (seen.put(pdo, Boolean.TRUE) != null) return;
        String t = pdo.classInfo.purePath;
        if (t != null && (t.endsWith("Application") || t.endsWith("Invocation")))
        {
            sink.add(System.identityHashCode(pdo));
        }
        String[] names = pdo.classInfo.nameBySlot();
        for (int slot = 0; slot < names.length; slot++)
        {
            if (names[slot] == null) continue;
            Object val;
            try { val = pdo.readSlot(slot); } catch (Throwable ignore) { continue; }
            if (val instanceof org.finos.legend.pure.truffle.runtime.dynobj.PureDynamicObject child)
            {
                collectFEHashes(child, sink, seen);
            }
            else if (val instanceof PureSequence seq)
            {
                for (int i = 0, n = seq.size(); i < n; i++)
                {
                    Object item = seq.getBoxed(i);
                    if (item instanceof org.finos.legend.pure.truffle.runtime.dynobj.PureDynamicObject child)
                    {
                        collectFEHashes(child, sink, seen);
                    }
                }
            }
        }
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

    // Walk every element transitively and look for FunctionApplication-like
    // nodes whose `func` slot is null after compile. compileDir is supposed
    // to resolve every call site to a PackageableFunctionPointer (or similar)
    // and set the `func` slot — a null here means the resolver dropped it,
    // and the downstream Truffle lazy-walk will crash with an opaque
    // `_func() returned null` error long after compile reported success.
    //
    // We pre-flight by reusing the actual PDB writer's validation. Writing
    // to a discarded in-memory FlatBuffer (not a file) exercises every
    // multiplicity-[1]/[1..*] check + the FunctionExpression `func` check
    // the writer codegen emits, on the same accessor path that the
    // serializer uses. Ground truth — if the writer accepts the elements,
    // they're valid; if it rejects, we get an actionable error message
    // that pinpoints the broken element + property + source location.
    private static List<String> findUnresolvedFuncs(PureSequence elements)
    {
        List<String> errors = new ArrayList<>();
        for (int i = 0, n = elements.size(); i < n; i++)
        {
            Object el = elements.getBoxed(i);
            if (el == null) continue;
            try
            {
                com.google.flatbuffers.FlatBufferBuilder b =
                        new com.google.flatbuffers.FlatBufferBuilder(1024);
                org.finos.legend.pure.truffle.pdb.codec.GeneratedFlatBufferWriter w =
                        new org.finos.legend.pure.truffle.pdb.codec.GeneratedFlatBufferWriter(b, true);
                w.dispatchWrite(el);
            }
            catch (IllegalArgumentException | IllegalStateException ex)
            {
                errors.add(ex.getMessage());
            }
        }
        return errors;
    }
}
