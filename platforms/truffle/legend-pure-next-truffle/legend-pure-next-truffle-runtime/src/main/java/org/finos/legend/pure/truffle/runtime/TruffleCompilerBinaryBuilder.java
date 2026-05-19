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

import org.finos.legend.pure.m3.module.ModuleManifest;
import org.finos.legend.pure.m3.module.TestElementFilter;
import org.finos.legend.pure.truffle.PureTruffleRuntime;
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
    // Mirror bootstrap: hand the entire orchestration to compile-pure as a
    // two-step chain — `parseDir` walks/reads/parses the directory and
    // returns `PureFile[*]`, then `compile` runs the three passes over those
    // pre-parsed files. Keeping the platforms structurally identical is what
    // makes the output PDBs comparable — the only platform-specific code is
    // the runtime hosting the same Pure entry points.
    private static final String PARSE_DIR_FN_PATH =
            "meta::pure::compiler::parseDir_String_1__PureFile_MANY_";
    private static final String COMPILE_FN_PATH =
            "meta::pure::compiler::compile_PureFile_MANY__Boolean_1__CompilationResult_1_";

    private TruffleCompilerBinaryBuilder()
    {
    }

    /**
     * Compile {@code sourceDir} against the given base PDBs and write to
     * {@code outputDir} (filename derived from the module manifest), with
     * optional tests-companion via {@code mode}. Each base PDB is loaded
     * read-only to provide cross-references; only freshly-compiled elements
     * (not already present in any base PDB) end up in the output.
     *
     * <p>The writer enforces required-property validation: any {@code [1]}
     * property that's null or any {@code [1..*]} that's empty aborts the
     * write. Surfaces compiler-pure gaps as build failures.</p>
     */
    public static void compile(List<Path> basePdbs, Path sourceDir, Path outputDir, TestElementFilter.Mode mode) throws IOException
    {
        compile(basePdbs, sourceDir, outputDir, mode, b -> {});
    }

    /**
     * Variant that lets callers customise the {@link PureTruffleRuntime}
     * before it boots — used by the CLI to forward {@code --source-root}
     * and {@code --cpu-sampler*} options.
     */
    public static void compile(List<Path> basePdbs, Path sourceDir, Path outputDir,
                               TestElementFilter.Mode mode,
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
        ModuleManifest manifest = ModuleManifest.locate(sourceDir);
        Path outputFile = outputDir.resolve(manifest.name() + ".pdb");

        System.out.println("Compiling Pure model from " + sourceDir
                + " (base: " + basePdbs + ") via truffle interpreter...");
        System.out.println("  Manifest: module='" + manifest.name() + "', deps=" + manifest.dependencies());
        System.out.println("  Output dir: " + outputDir);
        System.out.println("  Tests mode: " + mode);

        // 1. Load base PDBs — each carries its identity (name + dependencies)
        // in its embedded manifest, so the registry can cascade-invalidate on
        // unregister without us having to fabricate dep chains here.
        List<TrufflePdbLoader> loaders = new ArrayList<>();
        TruffleModuleRegistry resolver = new TruffleModuleRegistry();
        for (Path p : basePdbs)
        {
            TrufflePdbLoader loader = new TrufflePdbLoader(p);
            loaders.add(loader);
            resolver.register(loader);
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
                        new TruffleTestFileLanguageExtension(),
                        new TruffleReverseIndexLanguageExtension(),
                        new TruffleErrorLanguageExtension()));
        runtimeCustomizer.accept(runtimeBuilder);
        PureTruffleRuntime runtime = runtimeBuilder.build();

        // Post-loader-flip the resolver may return a PureDynamicObject for
        // these functions; runtime.execute accepts Object.
        Object parseDirFn = resolver.getElement(PARSE_DIR_FN_PATH);
        Object compileFn = resolver.getElement(COMPILE_FN_PATH);
        if (parseDirFn == null || compileFn == null
                || !org.finos.legend.pure.truffle.runtime.dynobj.PureObj.isType(parseDirFn,
                        "meta::pure::metamodel::function::FunctionDefinition", resolver)
                || !org.finos.legend.pure.truffle.runtime.dynobj.PureObj.isType(compileFn,
                        "meta::pure::metamodel::function::FunctionDefinition", resolver))
        {
            throw new RuntimeException("parseDir/compile FunctionDefinition not resolvable: "
                    + "parseDir=" + (parseDirFn == null ? "null" : parseDirFn.getClass().getName())
                    + ", compile=" + (compileFn == null ? "null" : compileFn.getClass().getName()));
        }

        try
        {
            // 3. Chain compile-pure's `parseDir` + `compile` — same entry
            // points the bootstrap orchestrator uses. Keeps the platforms
            // structurally identical: every byte of orchestration logic lives
            // in Pure, only the runtime changes. Returns a `CompilationResult`
            // whose `elements` already includes the hierarchical Package set
            // built by `buildPackages`.
            Object result;
            try
            {
                Object parsedFiles = runtime.execute(parseDirFn, sourceDir.toAbsolutePath().toString());
                result = runtime.execute(compileFn, parsedFiles, Boolean.getBoolean("legend.pure.compileDebug"));
            }
            catch (Throwable t)
            {
                // compile's 3-line progress display parks the cursor on
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
                throw new RuntimeException("compile returned null");
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
            // Accepts both typed XPDBHelper (instanceof PackageableElement) and
            // PureDynamicObject (PureObj.pureTypeOf returns Pure path) since
            // TrufflePdbWriter.write now accepts Iterable<?>.
            LinkedHashMap<String, Object> elementsByPath = new LinkedHashMap<>();
            Object elementsObj = invokeAccessor(result, "_elements");
            if (elementsObj instanceof PureSequence elementsSeq)
            {
                TrufflePdbLoader anchor = loaders.isEmpty() ? null : loaders.get(0);
                for (int i = 0; i < elementsSeq.size(); i++)
                {
                    Object el = elementsSeq.getBoxed(i);
                    if (el == null) continue;
                    String pureType = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.pureTypeOf(el);
                    if (pureType == null) continue;
                    String path = org.finos.legend.pure.truffle.runtime.helper._PackageableElement.path(el);
                    boolean isPkg = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.pureTypeIs(el,
                            "meta::pure::metamodel::Package");
                    if (isPkg && anchor != null && anchor.hasElement(path))
                    {
                        continue;
                    }
                    elementsByPath.put(path, el);
                }
            }

            System.out.println("Compiled " + elementsByPath.size() + " elements");

            // 4. Extract the reverse reference index from the Pure
            //    {@code CompilationResult.context.referencedBy}. Mirrors
            //    the bootstrap-CLI path so Truffle-compiled PDBs carry the
            //    same reverse-index payload as the Java compiler does.
            java.util.Map<String, java.util.Set<String>> referencedBy = extractReferencedBy(result);

            // 5. Write the aggregated elements to one or two PDBs depending on mode.
            if (outputFile.getParent() != null)
            {
                Files.createDirectories(outputFile.getParent());
            }
            writeFiltered(new ArrayList<>(elementsByPath.values()), manifest, outputFile, mode, referencedBy);
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
     * must succeed — *except* for the HotSpot code-installation size ceiling,
     * which no engine option can lift and which gracefully degrades the
     * affected lambda to Tier-1. All failures are still printed for
     * visibility, but only actionable ones cause the throw. The caller
     * (e.g. {@code pure-truffle compile}) propagates the throw and exits
     * non-zero. No-op under native-image AOT (no JIT events).
     */
    private static void failOnGraalCompilationFailures(PureTruffleRuntime runtime)
    {
        java.util.List<String> all = runtime.graalCompilationFailures();
        java.util.List<String> actionable = runtime.graalActionableCompilationFailures();
        if (all.isEmpty())
        {
            return;
        }
        int tolerated = all.size() - actionable.size();
        if (tolerated > 0)
        {
            System.err.println("[graal-tripwire] tolerated " + tolerated
                    + " unavoidable code-size bailout(s); affected lambdas stay Tier-1.");
        }
        if (actionable.isEmpty())
        {
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("Graal compilation failures: ").append(actionable.size())
                .append(" of ").append(runtime.graalCompilationAttempts()).append(" attempts. Top failures:\n");
        actionable.stream().limit(10).forEach(line -> sb.append(line).append('\n'));
        sb.append("To diagnose, re-run with `-Dpolyglot.engine.CompilationFailureAction=Diagnose` ")
                .append("and inspect the produced graal_dumps/ directory.");
        throw new RuntimeException(sb.toString());
    }

    /**
     * Read a property via {@link
     * org.finos.legend.pure.truffle.runtime.dynobj.PureObj#read} — works for
     * both typed XPDBHelpers and {@code PureDynamicObject}s. The previous
     * reflection-on-`_X()` path failed on PDO targets since the typed
     * `<X>` interface (which declared the default getter) is no longer
     * generated and {@code PureDynamicObject} only implements
     * {@code PropertyAccessor}.
     */
    private static Object invokeAccessor(Object target, String accessor)
    {
        String propName = accessor.startsWith("_") ? accessor.substring(1) : accessor;
        return org.finos.legend.pure.truffle.runtime.dynobj.PureObj.read(target, propName);
    }

    /**
     * @return {@code true} when {@code element} (a PDO or typed helper) is
     *         test-related: any stereotype on
     *         {@link TestElementFilter#TEST_PROFILE_PATH}, or one of
     *         {@link TestElementFilter#PCT_TEST_STEREOTYPES} on
     *         {@link TestElementFilter#PCT_PROFILE_PATH}. Other PCT
     *         stereotypes (e.g. {@code <<PCT.function>>}) stay in lean.
     */
    private static boolean isTestElement(Object element)
    {
        Object stereotypes = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.read(element, "stereotypes");
        if (stereotypes == null)
        {
            return false;
        }
        Iterable<?> iter;
        if (stereotypes instanceof PureSequence seq)
        {
            if (seq.size() == 0) return false;
            List<Object> list = new ArrayList<>(seq.size());
            for (int i = 0; i < seq.size(); i++)
            {
                list.add(seq.getBoxed(i));
            }
            iter = list;
        }
        else if (stereotypes instanceof Iterable<?> i)
        {
            iter = i;
        }
        else
        {
            return false;
        }
        for (Object s : iter)
        {
            if (s == null) continue;
            Object profile = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.read(s, "profile");
            if (profile == null) continue;
            String profilePath = org.finos.legend.pure.truffle.runtime.helper._PackageableElement.path(profile);
            if (TestElementFilter.TEST_PROFILE_PATH.equals(profilePath))
            {
                return true;
            }
            if (TestElementFilter.PCT_PROFILE_PATH.equals(profilePath))
            {
                Object value = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.read(s, "value");
                if (value instanceof String name && TestElementFilter.PCT_TEST_STEREOTYPES.contains(name))
                {
                    return true;
                }
            }
        }
        return false;
    }

    private static void writeFiltered(
            List<Object> elements,
            ModuleManifest manifest,
            Path outputFile,
            TestElementFilter.Mode mode,
            java.util.Map<String, java.util.Set<String>> referencedBy) throws IOException
    {
        switch (mode)
        {
            case WITH -> writePdb("full", elements, manifest, TestElementFilter.withTestsPath(outputFile), referencedBy);
            case NONE ->
            {
                List<Object> lean = elements.stream()
                        .filter(e -> !isTestElement(e))
                        .toList();
                java.util.Set<String> leanPaths = elementPaths(lean);
                writePdb("lean (" + lean.size() + "/" + elements.size() + ")",
                        lean, manifest, outputFile,
                        org.finos.legend.pure.m3.module.pdbModule.archive.ReverseIndexSection.filter(referencedBy, leanPaths::contains));
            }
            case ONLY ->
            {
                List<Object> tests = elements.stream()
                        .filter(TruffleCompilerBinaryBuilder::isTestElement)
                        .toList();
                java.util.Set<String> testPaths = elementPaths(tests);
                writePdb("tests-only (" + tests.size() + "/" + elements.size() + ")",
                        tests, TestElementFilter.testsManifest(manifest),
                        TestElementFilter.testsOnlyPath(outputFile),
                        org.finos.legend.pure.m3.module.pdbModule.archive.ReverseIndexSection.filter(referencedBy, testPaths::contains));
            }
            case SPLIT ->
            {
                List<Object> lean = new ArrayList<>(elements.size());
                List<Object> tests = new ArrayList<>();
                for (Object e : elements)
                {
                    (isTestElement(e) ? tests : lean).add(e);
                }
                java.util.Set<String> leanPaths = elementPaths(lean);
                java.util.Set<String> testPaths = elementPaths(tests);
                writePdb("lean (" + lean.size() + "/" + elements.size() + ")",
                        lean, manifest, outputFile,
                        org.finos.legend.pure.m3.module.pdbModule.archive.ReverseIndexSection.filter(referencedBy, leanPaths::contains));
                writePdb("tests-only (" + tests.size() + "/" + elements.size() + ")",
                        tests, TestElementFilter.testsManifest(manifest),
                        TestElementFilter.testsOnlyPath(outputFile),
                        org.finos.legend.pure.m3.module.pdbModule.archive.ReverseIndexSection.filter(referencedBy, testPaths::contains));
            }
        }
    }

    private static java.util.Set<String> elementPaths(List<Object> elements)
    {
        java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>();
        for (Object e : elements)
        {
            String p = org.finos.legend.pure.truffle.runtime.helper._PackageableElement.path(e);
            if (p != null)
            {
                out.add(p);
            }
        }
        return out;
    }

    private static void writePdb(String label, List<Object> elements, ModuleManifest manifest, Path target,
                                 java.util.Map<String, java.util.Set<String>> referencedBy) throws IOException
    {
        java.util.List<org.finos.legend.pure.m3.module.pdbModule.archive.PDBArchiveSection> extraSections = new ArrayList<>();
        org.finos.legend.pure.m3.module.pdbModule.archive.PDBArchiveSection riSection =
                org.finos.legend.pure.m3.module.pdbModule.archive.ReverseIndexSection.serialize(referencedBy);
        if (riSection != null)
        {
            extraSections.add(riSection);
        }
        TrufflePdbWriter.write(elements, manifest, target, true, extraSections);
        System.out.println("Written: " + target + " [" + label + ", " + Files.size(target) + " bytes, "
                + (referencedBy == null ? 0 : referencedBy.size()) + " ref targets]");
    }

    /**
     * Extract the reverse reference index from the Pure
     * {@code CompilationResult.context.referencedBy}. The Pure value is a
     * {@link org.finos.legend.pure.truffle.ast.natives.collection.MapImpl}
     * whose values are {@code List<String>} PDOs with a {@code values: String[*]}
     * field (a PureSequence). Returns an empty map on any shape mismatch.
     */
    private static java.util.Map<String, java.util.Set<String>> extractReferencedBy(Object compResult)
    {
        java.util.LinkedHashMap<String, java.util.Set<String>> out = new java.util.LinkedHashMap<>();
        Object ctxObj = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.read(compResult, "context");
        if (ctxObj == null)
        {
            return out;
        }
        Object refObj = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.read(ctxObj, "referencedBy");
        if (!(refObj instanceof org.finos.legend.pure.truffle.ast.natives.collection.MapImpl mapImpl))
        {
            return out;
        }
        for (java.util.Map.Entry<Object, Object> entry : mapImpl.getMap().entrySet())
        {
            if (!(entry.getKey() instanceof String targetPath))
            {
                continue;
            }
            Object listPdo = entry.getValue();
            Object valuesObj = listPdo == null ? null
                    : org.finos.legend.pure.truffle.runtime.dynobj.PureObj.read(listPdo, "values");
            java.util.LinkedHashSet<String> callers = new java.util.LinkedHashSet<>();
            if (valuesObj instanceof PureSequence seq)
            {
                for (int i = 0; i < seq.size(); i++)
                {
                    if (seq.getBoxed(i) instanceof String s)
                    {
                        callers.add(s);
                    }
                }
            }
            else if (valuesObj instanceof String s)
            {
                callers.add(s);
            }
            out.put(targetPath, callers);
        }
        return out;
    }
}
