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

import meta.pure.metamodel.PackageableElement;
import meta.pure.metamodel.function.FunctionWithParameters;
import org.eclipse.collections.api.factory.Lists;
import org.eclipse.collections.api.list.MutableList;
import org.finos.legend.pure.execution.DynamicInstance;
import org.finos.legend.pure.execution.PureExecution;
import org.finos.legend.pure.m3.LanguageExtension;
import org.finos.legend.pure.m3.PureModel;
import org.finos.legend.pure.m3.module.Module;
import org.finos.legend.pure.m3.module.ModuleManifest;
import org.finos.legend.pure.m3.module.ScopedMetadataAccess;
import org.finos.legend.pure.m3.module.TestElementFilter;
import org.finos.legend.pure.m3.module.pdbModule.PDBModule;
import org.finos.legend.pure.m3.module.pdbModule.archive.CompressedArchiveWriter;
import org.finos.legend.pure.m3.module.pdbModule.archive.PDBArchiveSection;
import org.finos.legend.pure.m3.pureLanguage.PureLanguageExtension;
import org.finos.legend.pure.m3.pureLanguage.metadata.lazyFunctions.FunctionIndexEntry;
import org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.PureLanguageCompilerExtension;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.stream.Stream;

/**
 * Compile compiler-pure (or any Pure source tree) into a {@code .pdb} by
 * running the <strong>Pure-language compiler</strong>
 * ({@code meta::pure::compiler::compile}) on the bootstrap
 * <strong>Java runtime</strong> ({@link PureExecution}).
 *
 * <p>This is the third corner of the matrix:</p>
 * <ul>
 *   <li>{@link CompilerBinaryBuilder} — Java compiler implementation
 *       ({@code PureModel.compile()}). Tested via the bootstrap unit-test
 *       suite. Doesn't exercise compiler-pure at all.</li>
 *   <li>{@code TruffleCompilerBinaryBuilder} — Pure compiler running on the
 *       Truffle interpreter. Tested by the Truffle test suite + the
 *       {@code test-self-host-truffle} Justfile recipe.</li>
 *   <li><strong>this class</strong> — Pure compiler running on the Java
 *       runtime. Verifies that the bootstrap-Java executor can run
 *       compiler-pure end-to-end, producing a usable PDB.</li>
 * </ul>
 *
 * <p>The runtime needs an existing {@code compiler.pdb} as a base — that's
 * where the {@code compile_PureFile_…} function definition lives. Bootstrap
 * builds the seed {@code compiler.pdb} via {@link CompilerBinaryBuilder}.</p>
 */
public final class PureRuntimeCompilerBinaryBuilder
{
    // Two-step compile: Pure walks/reads/parses the directory via
    // {@code parseDir} (returns {@code PureFile[*]}), then {@code compile}
    // runs the three passes over those pre-parsed files. Java just chains
    // the two calls and serializes the resulting elements.
    private static final String PARSE_DIR_FN_PATH =
            "meta::pure::compiler::parseDir_String_1__PureFile_MANY_";
    private static final String COMPILE_FN_PATH =
            "meta::pure::compiler::compile_PureFile_MANY__Boolean_1__CompilationResult_1_";

    private PureRuntimeCompilerBinaryBuilder()
    {
    }

    /**
     * Compile {@code sourceDir} against the given base PDBs (typically
     * {@code core.pdb} + {@code compiler.pdb}) and write the result into
     * {@code outputDir}, with filename derived from the module manifest's
     * {@code name}. Each {@code .pure} file under {@code sourceDir} is
     * parsed into a {@code PureFile} and passed through
     * {@code meta::pure::compiler::compile} via the bootstrap
     * {@link PureExecution} runtime; the resulting elements are aggregated,
     * optionally partitioned by test-stereotype, and serialized via
     * {@link CompressedArchiveWriter}.
     */
    public static void compile(List<Path> basePdbs, Path sourceDir, Path outputDir, TestElementFilter.Mode mode) throws IOException
    {
        ModuleManifest manifest = ModuleManifest.locate(sourceDir);
        Path outputFile = outputDir.resolve(manifest.name() + ".pdb");
        if (basePdbs.isEmpty())
        {
            throw new IllegalArgumentException("At least one --base-pdb is required (need core.pdb plus an existing compiler.pdb to host the compile function).");
        }
        if (!Files.isDirectory(sourceDir))
        {
            throw new IllegalArgumentException("source dir does not exist: " + sourceDir);
        }

        System.out.println();
        System.out.println("Pure Compiler Binary Builder (Java runtime + Pure compiler)");
        System.out.println("===========================================================");
        System.out.println("  Inputs: " + sourceDir);
        for (Path p : basePdbs)
        {
            System.out.println("          " + p);
        }
        System.out.println("  Output dir: " + outputDir);
        System.out.println("  Tests mode: " + mode);

        // 1. Load base PDBs — each carries its identity in its embedded manifest.
        MutableList<Module> modules = Lists.mutable.empty();
        List<String> loadedNames = new ArrayList<>();
        for (Path p : basePdbs)
        {
            PDBModule mod = new PDBModule(p, PDBModule.Mode.EXECUTION);
            modules.add(mod);
            loadedNames.add(mod.getName());
        }
        verifyDependencies(manifest, loadedNames);
        MutableList<LanguageExtension> extensions = Lists.mutable.with(new PureLanguageExtension());
        PureModel model = PureModel.withModules(modules).withExtensions(extensions).build();
        model.compile();

        Module lastModule = modules.get(modules.size() - 1);
        ScopedMetadataAccess resolver = new ScopedMetadataAccess(lastModule, model);

        // 2. Build the bootstrap Java runtime. The CompiledGraphLanguageExtension
        // is what `compile_PureFile_…` uses to parse the `###CompiledGraph`
        // and `###Pure` sections; passing it lets us compile any of the
        // canonical compiler-pure sources.
        PureExecution execution = PureExecution.builder()
                .withResolver(resolver)
                .withNativeExtensions(Lists.mutable.with(new CompilerNatives()))
                .withParserExtensions(List.of(
                        new org.finos.legend.pure.m3.extensions.compiledgraph.CompiledGraphLanguageExtension(),
                        new org.finos.legend.pure.m3.extensions.compilerstats.CompilerStatsLanguageExtension(),
                        new org.finos.legend.pure.m3.extensions.testfile.TestFileLanguageExtension(),
                        new org.finos.legend.pure.m3.extensions.error.ErrorLanguageExtension()))
                .build();

        FunctionWithParameters parseDirFn = (FunctionWithParameters) resolver.getElement(PARSE_DIR_FN_PATH);
        FunctionWithParameters compileFn = (FunctionWithParameters) resolver.getElement(COMPILE_FN_PATH);
        if (parseDirFn == null || compileFn == null)
        {
            throw new RuntimeException(PARSE_DIR_FN_PATH + " or " + COMPILE_FN_PATH + " not found. "
                    + "Pass an up-to-date compiler.pdb as --base-pdb.");
        }

        // 3. Pure walks + reads + parses via `parseDir`, then compiles via
        // `compile(PureFile[*])`. Both run through the bootstrap Java runtime;
        // Java just chains the two calls and gets a CompilationResult back.
        // parseDir is silent; compile prints its own progress + stats summary.
        Object parsedFiles = execution.execute(parseDirFn, sourceDir.toAbsolutePath().toString());
        Object result = execution.execute(compileFn, parsedFiles, false);
        if (!(result instanceof DynamicInstance compResult))
        {
            throw new RuntimeException("compile did not return a CompilationResult (got "
                    + (result == null ? "null" : result.getClass().getName()) + ")");
        }

        List<String> allErrors = new ArrayList<>();
        Object errorsObj = compResult.get("errors");
        if (errorsObj instanceof List<?> errs)
        {
            for (Object e : errs)
            {
                allErrors.add(String.valueOf(e));
            }
        }
        if (!allErrors.isEmpty())
        {
            // Abort on errors. Mirrors the Java-compiler path
            // (CompilerBinaryBuilder line 78): if compilation produced any
            // errors, do not attempt PDB write — the partial output would
            // contain unresolved FunctionExpressions that the FlatBuffer
            // serializer rejects with `null _func()` IllegalStateException,
            // masking the real compile diagnostic.
            //
            // Each error has already been reported live by compile's
            // per-file `println` chain; this is the abort step.
            throw new RuntimeException("Pure compilation failed with " + allErrors.size() + " error(s)");
        }

        // `CompilationResult.elements` already contains only source-compiled
        // elements (compiler.pure's `pass2.values`); base-PDB elements enter
        // the resolver's element map via lookups but never the result list.
        // Dedupe by path defensively in case the same source element appears
        // twice (unlikely, but cheap insurance).
        LinkedHashMap<String, PackageableElement> elementsByPath = new LinkedHashMap<>();
        Object elementsObj = compResult.get("elements");
        if (!(elementsObj instanceof List<?> els))
        {
            throw new RuntimeException("CompilationResult.elements was " +
                    (elementsObj == null ? "null" : elementsObj.getClass().getName()));
        }
        Module anchor = modules.isEmpty() ? null : modules.get(0);
        for (Object el : els)
        {
            if (!(el instanceof PackageableElement pe))
            {
                continue;
            }
            String path = elementPath(pe);
            if (path == null)
            {
                continue;
            }
            // Mirror the Java compiler's package-emission rule: skip Package
            // elements that already exist in the anchor (core) PDB. Compile-
            // pure expands every element's package path to all ancestors —
            // `meta::pure::compiler::helper::class` brings in `meta::pure`,
            // `meta`, etc. — and those ancestors live in core.pdb, so
            // re-emitting them would diverge from the bootstrap output.
            // Non-Package elements always pass through.
            if (pe instanceof meta.pure.metamodel.Package
                    && anchor != null && anchor.hasElement(path))
            {
                continue;
            }
            elementsByPath.put(path, pe);
        }

        MutableList<PackageableElement> elements = Lists.mutable.withAll(elementsByPath.values());
        System.out.println("  Compiled " + elements.size() + " elements");

        // 4. Build the function index from the compiled elements. Mirrors what
        //    the Java compiler's PureLanguageCompilerExtension does in its
        //    `preThirdPass` (populating the LocalModule's PureLanguageMetadata),
        //    but we compute it directly because compile-via-pure produces a
        //    plain list of elements without a LocalModule. Without this section
        //    function-name lookups in self-host PDBs would fall back to a
        //    linear scan; the byte-level diff against the Java-compiled
        //    compiler.pdb would also flag the absence as a divergence.
        PureLanguageCompilerExtension compilerExt = new PureLanguageCompilerExtension();
        MutableList<FunctionIndexEntry> functionEntries = Lists.mutable.empty();
        compilerExt.buildFunctionIndex(elements, resolver)
                .forEachValue(byArity -> byArity.forEachValue(functionEntries::addAllIterable));
        PureLanguageExtension pureLangExt = (PureLanguageExtension) extensions.get(0);
        System.out.println("  Function index: " + functionEntries.size() + " entries");

        // 5. Serialize. CompressedArchiveWriter wants a Module to satisfy
        // its archive-metadata expectations; the last input PDB module is
        // a fine stand-in (its name becomes the local-module name in the
        // produced archive header, matching the existing builders).
        if (outputFile.getParent() != null)
        {
            Files.createDirectories(outputFile.getParent());
        }
        // Extract the reverse reference index from the Pure
        // {@code CompilationResult.context.referencedBy} — a PureMap
        // keyed by target path with List<String> caller-path values.
        // Mirrors the Java side's `result.referencedBy()` so both
        // compilers produce structurally identical PDBs.
        java.util.Map<String, java.util.Set<String>> referencedBy =
                extractReferencedBy(compResult);
        writeFiltered(elements, extensions, lastModule, manifest, functionEntries, pureLangExt, outputFile, mode,
                referencedBy);
    }

    private static void writeFiltered(
            MutableList<PackageableElement> elements,
            MutableList<LanguageExtension> extensions,
            Module lastModule,
            ModuleManifest manifest,
            MutableList<FunctionIndexEntry> functionEntries,
            PureLanguageExtension pureLangExt,
            Path outputFile,
            TestElementFilter.Mode mode,
            java.util.Map<String, java.util.Set<String>> referencedBy) throws IOException
    {
        switch (mode)
        {
            case WITH ->
                    writePdb("full", elements, extensions, lastModule, manifest,
                            pureLangExt.archiveSections(functionEntries),
                            TestElementFilter.withTestsPath(outputFile), referencedBy);
            case NONE ->
            {
                MutableList<PackageableElement> lean = elements.reject(TestElementFilter::isTestElement);
                java.util.Set<String> leanPaths = pathSet(lean);
                writePdb("lean (" + lean.size() + "/" + elements.size() + ")",
                        lean, extensions, lastModule, manifest,
                        pureLangExt.archiveSections(filterEntries(functionEntries, leanPaths)),
                        outputFile,
                        org.finos.legend.pure.m3.module.pdbModule.archive.ReverseIndexSection.filter(referencedBy, leanPaths::contains));
            }
            case ONLY ->
            {
                MutableList<PackageableElement> tests = elements.select(TestElementFilter::isTestElement);
                java.util.Set<String> testPaths = pathSet(tests);
                writePdb("tests-only (" + tests.size() + "/" + elements.size() + ")",
                        tests, extensions, lastModule, TestElementFilter.testsManifest(manifest),
                        pureLangExt.archiveSections(filterEntries(functionEntries, testPaths)),
                        TestElementFilter.testsOnlyPath(outputFile),
                        org.finos.legend.pure.m3.module.pdbModule.archive.ReverseIndexSection.filter(referencedBy, testPaths::contains));
            }
            case SPLIT ->
            {
                MutableList<PackageableElement> lean = elements.reject(TestElementFilter::isTestElement);
                MutableList<PackageableElement> tests = elements.select(TestElementFilter::isTestElement);
                java.util.Set<String> leanPaths = pathSet(lean);
                java.util.Set<String> testPaths = pathSet(tests);
                writePdb("lean (" + lean.size() + "/" + elements.size() + ")",
                        lean, extensions, lastModule, manifest,
                        pureLangExt.archiveSections(filterEntries(functionEntries, leanPaths)),
                        outputFile,
                        org.finos.legend.pure.m3.module.pdbModule.archive.ReverseIndexSection.filter(referencedBy, leanPaths::contains));
                writePdb("tests-only (" + tests.size() + "/" + elements.size() + ")",
                        tests, extensions, lastModule, TestElementFilter.testsManifest(manifest),
                        pureLangExt.archiveSections(filterEntries(functionEntries, testPaths)),
                        TestElementFilter.testsOnlyPath(outputFile),
                        org.finos.legend.pure.m3.module.pdbModule.archive.ReverseIndexSection.filter(referencedBy, testPaths::contains));
            }
        }
    }

    /**
     * Keep only function-index entries whose full path is in
     * {@code keepPaths}. Used to scope the function index per PDB so a
     * lean PDB doesn't carry test-only function entries.
     */
    private static List<FunctionIndexEntry> filterEntries(
            MutableList<FunctionIndexEntry> all, java.util.Set<String> keepPaths)
    {
        List<FunctionIndexEntry> out = new java.util.ArrayList<>();
        for (FunctionIndexEntry e : all)
        {
            if (keepPaths.contains(e.fullPath()))
            {
                out.add(e);
            }
        }
        return out;
    }

    private static java.util.Set<String> pathSet(MutableList<PackageableElement> elements)
    {
        java.util.Set<String> set = new java.util.HashSet<>();
        for (PackageableElement e : elements)
        {
            String p = elementPath(e);
            if (p != null) set.add(p);
        }
        return set;
    }

    private static void writePdb(
            String label,
            MutableList<PackageableElement> elements,
            MutableList<LanguageExtension> extensions,
            Module lastModule,
            ModuleManifest manifest,
            List<PDBArchiveSection> additionalSections,
            Path target,
            java.util.Map<String, java.util.Set<String>> referencedBy) throws IOException
    {
        List<PDBArchiveSection> sections = new java.util.ArrayList<>(additionalSections);
        PDBArchiveSection riSection =
                org.finos.legend.pure.m3.module.pdbModule.archive.ReverseIndexSection.serialize(referencedBy);
        if (riSection != null) sections.add(riSection);
        new CompressedArchiveWriter().write(elements, extensions, lastModule, manifest, sections, target);
        System.out.println("    Written: " + target + " [" + label + ", " + Files.size(target) + " bytes, "
                + (referencedBy == null ? 0 : referencedBy.size()) + " ref targets]");
    }

    private static void verifyDependencies(ModuleManifest manifest, List<String> availableNames)
    {
        for (String dep : manifest.dependencies())
        {
            if (!availableNames.contains(dep))
            {
                throw new RuntimeException("Manifest '" + manifest.name() + "' declares dependency '"
                        + dep + "' but no module of that name was loaded (available: " + availableNames + ")");
            }
        }
    }

    private static String elementPath(PackageableElement pe)
    {
        // Reuse the bootstrap M3 helper that walks the package chain.
        return org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._PackageableElement.path(pe);
    }

    private static String deriveName(Path pdbPath)
    {
        String fileName = pdbPath.getFileName().toString();
        return fileName.endsWith(".pdb") ? fileName.substring(0, fileName.length() - 4) : fileName;
    }

    /**
     * Extract the reverse reference index from the Pure
     * {@code CompilationResult.context.referencedBy}. The Pure value is a
     * {@code PureMap<String, List<String>>} where each value is a
     * {@code DynamicInstance} of {@code meta::pure::functions::collection::List}
     * with a {@code values: String[*]} field. Returns an empty map on any
     * shape mismatch so a missing index doesn't fail the PDB write.
     */
    private static java.util.Map<String, java.util.Set<String>> extractReferencedBy(DynamicInstance compResult)
    {
        java.util.LinkedHashMap<String, java.util.Set<String>> out = new java.util.LinkedHashMap<>();
        Object ctxObj = compResult.get("context");
        if (!(ctxObj instanceof DynamicInstance ctx))
        {
            return out;
        }
        Object refObj = ctx.get("referencedBy");
        if (!(refObj instanceof org.finos.legend.pure.execution.PureMap pm))
        {
            return out;
        }
        for (java.util.Map.Entry<meta.pure.metamodel.valuespecification.ValueSpecification,
                meta.pure.metamodel.valuespecification.ValueSpecification> entry : pm.getMap().entrySet())
        {
            Object key = org.finos.legend.pure.execution._E_ValueSpecification.unwrap(entry.getKey());
            Object val = org.finos.legend.pure.execution._E_ValueSpecification.unwrap(entry.getValue());
            if (!(key instanceof String targetPath) || !(val instanceof DynamicInstance listDi))
            {
                continue;
            }
            Object valuesObj = listDi.get("values");
            java.util.LinkedHashSet<String> callers = new java.util.LinkedHashSet<>();
            if (valuesObj instanceof List<?> vs)
            {
                for (Object v : vs)
                {
                    if (v instanceof String s)
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
