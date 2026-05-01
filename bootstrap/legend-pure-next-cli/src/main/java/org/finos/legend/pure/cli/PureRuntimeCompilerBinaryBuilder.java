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
import org.finos.legend.pure.m3.module.ScopedMetadataAccess;
import org.finos.legend.pure.m3.module.pdbModule.PDBModule;
import org.finos.legend.pure.m3.module.pdbModule.archive.CompressedArchiveWriter;
import org.finos.legend.pure.m3.pureLanguage.PureLanguageExtension;

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
    // The whole walk-read-parse-compile-aggregate pipeline lives in Pure
    // (see {@code meta::pure::compiler::compileDir}) — uses the native
    // {@code directoryTree} and {@code readFile}, then per-file
    // {@code parse} + {@code compile}, then aggregates into a single
    // {@code CompilationResult}. Java just invokes this one function and
    // serializes the resulting elements. We resolve the 2-arg overload so
    // we can pass the debug flag (matching Truffle's path); pass {@code false}
    // for normal runs and flip to {@code true} when debugging compiler-pure.
    private static final String COMPILE_DIR_FN_PATH =
            "meta::pure::compiler::compileDir_String_1__Boolean_1__CompilationResult_1_";

    private PureRuntimeCompilerBinaryBuilder()
    {
    }

    /**
     * Compile {@code sourceDir} against the given base PDBs (typically
     * {@code core.pdb} + {@code compiler.pdb}) and write the result to
     * {@code outputFile}. Each {@code .pure} file under {@code sourceDir}
     * is parsed into a {@code PureFile} and passed through
     * {@code meta::pure::compiler::compile} via the bootstrap
     * {@link PureExecution} runtime; the resulting elements are aggregated
     * and serialized via {@link CompressedArchiveWriter}.
     */
    public static void compile(List<Path> basePdbs, Path sourceDir, Path outputFile) throws IOException
    {
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
        System.out.println("  Output: " + outputFile);

        // 1. Load base PDBs as PDB modules. Earlier PDBs are dependencies of
        // later ones (so the resolver can find e.g. `Class` in core.pdb when
        // walking compiler.pdb wrappers).
        MutableList<Module> modules = Lists.mutable.empty();
        List<String> priorNames = new ArrayList<>();
        for (Path p : basePdbs)
        {
            String name = deriveName(p);
            modules.add(new PDBModule(p, PDBModule.Mode.EXECUTION, name, "*",
                    Lists.mutable.withAll(priorNames)));
            priorNames.add(name);
        }
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
                        new org.finos.legend.pure.m3.extensions.error.ErrorLanguageExtension()))
                .build();

        FunctionWithParameters compileDirFn = (FunctionWithParameters) resolver.getElement(COMPILE_DIR_FN_PATH);
        if (compileDirFn == null)
        {
            throw new RuntimeException(COMPILE_DIR_FN_PATH + " not found. "
                    + "Pass an up-to-date compiler.pdb (one that includes the compileDir helper) "
                    + "as --base-pdb.");
        }

        // 3. Pure does the full walk-read-parse-compile-aggregate via the
        // `directoryTree` and `readFile` natives. Java just hands it the
        // source dir and gets a single CompilationResult back.
        long t0 = System.currentTimeMillis();
        System.out.println("  Calling compileDir on " + sourceDir.toAbsolutePath() + " ...");
        Object result = execution.execute(compileDirFn, sourceDir.toAbsolutePath().toString(), false);
        System.out.println("  compileDir done in " + (System.currentTimeMillis() - t0) + " ms");
        if (!(result instanceof DynamicInstance compResult))
        {
            throw new RuntimeException("compileDir did not return a CompilationResult (got "
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
            // Report but DO NOT abort — we want to see how far the compile
            // gets across the whole tree, not stop at the first file with
            // errors. Each error is already reported live by compileDir's
            // per-file `println('      ERR ...')`; this final summary
            // restates the count for visibility.
            System.err.println("  " + allErrors.size() + " compilation error(s) — continuing to write the PDB so the partial output can be inspected.");
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

        List<PackageableElement> elements = new ArrayList<>(elementsByPath.values());
        System.out.println("  Compiled " + elements.size() + " elements");

        // 4. Serialize. CompressedArchiveWriter wants a Module to satisfy
        // its archive-metadata expectations; the last input PDB module is
        // a fine stand-in (its name becomes the local-module name in the
        // produced archive header, matching the existing builders).
        if (outputFile.getParent() != null)
        {
            Files.createDirectories(outputFile.getParent());
        }
        new CompressedArchiveWriter().write(elements, extensions, lastModule, outputFile);
        System.out.println("    Written: " + outputFile + " (" + Files.size(outputFile) + " bytes)");
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
}
