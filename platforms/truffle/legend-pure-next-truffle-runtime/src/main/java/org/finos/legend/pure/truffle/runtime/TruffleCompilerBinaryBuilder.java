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

import org.finos.legend.pure.next.parser.PureParser;
import org.finos.legend.pure.truffle.PureLanguage;
import org.finos.legend.pure.truffle.PureTruffleRuntime;
import org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.PackageableElement;
import org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.function.FunctionDefinition;
import org.finos.legend.pure.truffle.types.PureSequence;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.stream.Stream;

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
    private static final String COMPILE_FN_PATH =
            "meta::pure::compiler::compile_PureFile_1__CompilationResult_1_";

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

        // 1. Load base PDBs and build a composite resolver.
        List<TrufflePdbLoader> loaders = new ArrayList<>();
        for (Path p : basePdbs)
        {
            loaders.add(new TrufflePdbLoader(p));
        }
        TruffleMetadataAccess resolver = compositeResolver(loaders);
        for (TrufflePdbLoader loader : loaders)
        {
            loader.setResolver(resolver);
        }
        for (TrufflePdbLoader loader : loaders)
        {
            loader.preloadAll();
        }

        // 2. Boot the truffle runtime.
        PureTruffleRuntime runtime = PureTruffleRuntime.builder()
                .withResolver(resolver)
                .withParserExtensions(List.of(
                        new TruffleCompiledGraphLanguageExtension(),
                        new org.finos.legend.pure.m3.extensions.error.ErrorLanguageExtension()))
                .build();

        Object compileObj = resolver.getElement(COMPILE_FN_PATH);
        if (!(compileObj instanceof FunctionDefinition))
        {
            throw new RuntimeException("compile FunctionDefinition not resolvable: "
                    + (compileObj == null ? "null" : compileObj.getClass().getName()));
        }
        FunctionDefinition compileFn = (FunctionDefinition) compileObj;
        PureParser parser = PureLanguage.get(null).pureParser();

        // 3. For each .pure file: parse → translate → compile → collect elements.
        // Keyed by full path so duplicate definitions across files take the
        // last-seen version (matches bootstrap behavior). Errors from any
        // file abort the whole build.
        LinkedHashMap<String, PackageableElement> elementsByPath = new LinkedHashMap<>();
        List<String> allErrors = new ArrayList<>();
        int filesCompiled = 0;
        try (Stream<Path> walk = Files.walk(sourceDir))
        {
            List<Path> sources = walk.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".pure"))
                    .sorted()
                    .toList();
            for (Path src : sources)
            {
                String content = Files.readString(src, StandardCharsets.UTF_8);
                String sourceId = sourceDir.relativize(src).toString().replace('\\', '/');
                if (sourceId.endsWith(".pure"))
                {
                    sourceId = sourceId.substring(0, sourceId.length() - ".pure".length());
                }

                meta.pure.protocol.PureFile bootstrapFile = parser.parse(sourceId, content);
                Object truffleFile = new ProtocolTranslator(resolver).translate(bootstrapFile);
                Object result = runtime.execute(compileFn, truffleFile);
                if (result == null)
                {
                    throw new RuntimeException("compile returned null for " + src);
                }

                // CompilationResult lives in the truffle PDB namespace; access
                // its fields reflectively to avoid a static dependency on a
                // package the runtime module doesn't import directly.
                Object errorsObj = invokeAccessor(result, "_errors");
                if (errorsObj instanceof PureSequence errSeq)
                {
                    for (int i = 0; i < errSeq.size(); i++)
                    {
                        allErrors.add("[" + sourceId + "] " + errSeq.getBoxed(i));
                    }
                }

                Object elementsObj = invokeAccessor(result, "_elements");
                if (elementsObj instanceof PureSequence elementsSeq)
                {
                    for (int i = 0; i < elementsSeq.size(); i++)
                    {
                        Object el = elementsSeq.getBoxed(i);
                        if (el instanceof PackageableElement pe)
                        {
                            String path = org.finos.legend.pure.truffle.runtime.helper._PackageableElement.path(pe);
                            // Skip elements already in the base PDBs.
                            if (existsInBase(loaders, path))
                            {
                                continue;
                            }
                            elementsByPath.put(path, pe);
                        }
                    }
                }
                filesCompiled++;
            }
        }

        if (!allErrors.isEmpty())
        {
            System.err.println("Compilation errors:");
            allErrors.forEach(e -> System.err.println("  " + e));
            throw new RuntimeException("Pure compilation failed with " + allErrors.size() + " error(s)");
        }

        System.out.println("Compiled " + elementsByPath.size() + " elements from " + filesCompiled + " file(s)");

        // 4. Write the aggregated elements to the output PDB.
        if (outputFile.getParent() != null)
        {
            Files.createDirectories(outputFile.getParent());
        }
        TrufflePdbWriter.write(new ArrayList<>(elementsByPath.values()), outputFile);
        System.out.println("Written: " + outputFile + " (" + Files.size(outputFile) + " bytes)");
    }

    private static TruffleMetadataAccess compositeResolver(List<TrufflePdbLoader> loaders)
    {
        return new TruffleMetadataAccess()
        {
            @Override
            public Object getElement(String path)
            {
                // Later loaders take priority, mirroring PureCompileMain.execute.
                for (int i = loaders.size() - 1; i >= 0; i--)
                {
                    Object e = loaders.get(i).getElement(path);
                    if (e != null) return e;
                }
                return null;
            }

            @Override
            public boolean hasElement(String path)
            {
                for (TrufflePdbLoader l : loaders)
                {
                    if (l.hasElement(path)) return true;
                }
                return false;
            }

            @Override
            public java.util.Set<String> elementPaths()
            {
                java.util.Set<String> all = new java.util.LinkedHashSet<>();
                for (TrufflePdbLoader l : loaders) all.addAll(l.elementPaths());
                return all;
            }
        };
    }

    private static boolean existsInBase(List<TrufflePdbLoader> loaders, String path)
    {
        for (TrufflePdbLoader l : loaders)
        {
            if (l.hasElement(path)) return true;
        }
        return false;
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
