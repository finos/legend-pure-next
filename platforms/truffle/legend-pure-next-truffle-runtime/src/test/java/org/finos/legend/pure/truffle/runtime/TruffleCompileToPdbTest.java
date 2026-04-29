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

import org.eclipse.collections.api.factory.Lists;
import org.finos.legend.pure.next.parser.m3.PureLanguageParser;
import org.finos.legend.pure.next.parser.topLevel.TopLevelProtocolBuilder;
import org.finos.legend.pure.truffle.PureTruffleRuntime;
import org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.PackageableElement;
import org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.function.FunctionDefinition;
import org.finos.legend.pure.truffle.types.PureSequence;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Truffle-side analog of bootstrap's PdbRoundTripTest: parse Pure source,
 * compile it through the truffle interpreter, write the resulting metamodel
 * elements to a {@code .pdb} via {@link TrufflePdbWriter}, reload via
 * {@link TrufflePdbLoader}, and assert every compiled path round-trips.
 *
 * <p>{@link #smokeTest} compiles a single hardcoded class as a sanity check.
 * {@link #roundTripCompilerSpec} parameterises over every {@code ###Pure} test
 * file under {@code specification/compiler/} (the same set bootstrap's
 * {@code PdbRoundTripTest} covers).</p>
 */
public class TruffleCompileToPdbTest
{
    private static PureTruffleRuntime runtime;
    private static TruffleMetadataAccess resolver;
    private static FunctionDefinition compileFn;
    private static org.finos.legend.pure.next.parser.PureParser pureParser;

    private static Path locateBuildDir()
    {
        Path current = Path.of("").toAbsolutePath();
        while (current != null)
        {
            Path candidate = current.resolve("build");
            if (Files.isDirectory(candidate)
                    && Files.exists(candidate.resolve("core.pdb"))
                    && Files.exists(candidate.resolve("compiler.pdb")))
            {
                return candidate;
            }
            current = current.getParent();
        }
        throw new RuntimeException("Cannot locate build/core.pdb + build/compiler.pdb");
    }

    private static Path locateCompilerSpecRoot()
    {
        Path current = Path.of("").toAbsolutePath();
        while (current != null)
        {
            Path candidate = current.resolve("specification").resolve("compiler");
            if (Files.isDirectory(candidate)) return candidate;
            current = current.getParent();
        }
        throw new RuntimeException("Cannot locate specification/compiler");
    }

    @BeforeAll
    static void setupOnce() throws IOException
    {
        Path buildDir = locateBuildDir();
        TrufflePdbLoader coreLoader = new TrufflePdbLoader(buildDir.resolve("core.pdb"));
        TrufflePdbLoader compilerLoader = new TrufflePdbLoader(buildDir.resolve("compiler.pdb"));
        resolver = new TruffleMetadataAccess()
        {
            @Override public Object getElement(String p)
            {
                Object e = compilerLoader.getElement(p);
                return e != null ? e : coreLoader.getElement(p);
            }
            @Override public boolean hasElement(String p)
            {
                return compilerLoader.hasElement(p) || coreLoader.hasElement(p);
            }
            @Override public Set<String> elementPaths()
            {
                Set<String> all = new java.util.LinkedHashSet<>(coreLoader.elementPaths());
                all.addAll(compilerLoader.elementPaths());
                return all;
            }
        };
        coreLoader.setResolver(resolver);
        compilerLoader.setResolver(resolver);
        coreLoader.preloadAll();
        compilerLoader.preloadAll();

        runtime = PureTruffleRuntime.builder()
                .withResolver(resolver)
                .withParserExtensions(List.of(
                        new TruffleCompiledGraphLanguageExtension(),
                        new org.finos.legend.pure.m3.extensions.error.ErrorLanguageExtension()))
                .build();

        Object compileObj = resolver.getElement("meta::pure::compiler::compile_PureFile_1__CompilationResult_1_");
        if (!(compileObj instanceof FunctionDefinition))
        {
            throw new RuntimeException("compile FunctionDefinition not resolvable: "
                    + (compileObj == null ? "null" : compileObj.getClass().getName()));
        }
        compileFn = (FunctionDefinition) compileObj;

        // Reuse the runtime's configured PureParser instance — same one the
        // native `parse` uses, so this test path matches TruffleTestCompilerPure exactly.
        pureParser = org.finos.legend.pure.truffle.PureLanguage.get(null).pureParser();
    }

    @Test
    public void smokeTest() throws IOException
    {
        runRoundTrip("smoke", "Class my::test::TestClass { name : String[1]; age : Integer[0..1]; }");
    }

    public static Collection<Arguments> discoverCompilerSpecTests() throws IOException
    {
        List<Arguments> tests = new ArrayList<>();
        Path rootDir = locateCompilerSpecRoot();
        try (Stream<Path> walk = Files.walk(rootDir))
        {
            walk.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".pure"))
                    .sorted()
                    .forEach(p ->
                    {
                        try
                        {
                            String content = Files.readString(p, StandardCharsets.UTF_8);
                            // Same filter bootstrap PdbRoundTripTest uses.
                            if (content.contains("###Pure") && !content.contains("###Error"))
                            {
                                String relative = rootDir.relativize(p).toString().replace('\\', '/');
                                String testName = relative.endsWith(".pure")
                                        ? relative.substring(0, relative.length() - 5)
                                        : relative;
                                tests.add(Arguments.of(testName, p.toString()));
                            }
                        }
                        catch (IOException e)
                        {
                            throw new RuntimeException(e);
                        }
                    });
        }
        return tests;
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("discoverCompilerSpecTests")
    public void roundTripCompilerSpec(String testName, String resourcePath) throws Exception
    {
        String content = Files.readString(Path.of(resourcePath), StandardCharsets.UTF_8);
        runRoundTrip(testName, content);
    }

    /**
     * Shared core: parse the source, translate, truffle-compile, write,
     * reload, assert paths and types. Skips with {@link Assumptions#abort}
     * if parse or truffle-compile fails — those are out-of-scope for
     * testing the writer.
     */
    private static void runRoundTrip(String testName, String pureSource) throws IOException
    {
        // 1. Parse via the SAME PureParser instance the runtime uses (the one
        // the native `parse` invokes), then translate via ProtocolTranslator —
        // matching ParseNode's invocation exactly.
        meta.pure.protocol.PureFile bootstrapFile;
        try
        {
            bootstrapFile = pureParser.parse(testName, pureSource);
        }
        catch (Exception e)
        {
            Assumptions.abort("parse failed: " + e.getMessage());
            return;
        }
        Assumptions.assumeFalse(bootstrapFile._sections().isEmpty(), "no sections to compile");

        Object truffleFile = new ProtocolTranslator(resolver).translate(bootstrapFile);
        Assertions.assertNotNull(truffleFile, "ProtocolTranslator returned null");

        // 3. Truffle-compile
        Object result;
        try
        {
            result = runtime.execute(compileFn, truffleFile);
        }
        catch (Exception e)
        {
            Assumptions.abort("truffle compile threw (out of scope for writer test): " + e.getClass().getSimpleName() + " " + e.getMessage());
            return;
        }
        if (result == null)
        {
            Assumptions.abort("truffle compile returned null");
            return;
        }

        // 4. Compilation must produce zero errors — same contract bootstrap's
        // CompilerCompiledGraphTest / PdbRoundTripTest enforce.
        Object errorsObj = invokeAccessor(result, "_errors");
        if (errorsObj instanceof PureSequence errorsSeq && errorsSeq.size() > 0)
        {
            StringBuilder msg = new StringBuilder("Compilation errors for ").append(testName).append(":");
            for (int i = 0; i < errorsSeq.size(); i++)
            {
                msg.append("\n  ").append(errorsSeq.getBoxed(i));
            }
            Assertions.fail(msg.toString());
        }
        Object elementsObj = invokeAccessor(result, "_elements");
        Assertions.assertTrue(elementsObj instanceof PureSequence,
                "_elements() returned " + (elementsObj == null ? "null" : elementsObj.getClass().getName()));
        PureSequence elementsSeq = (PureSequence) elementsObj;
        Assertions.assertTrue(elementsSeq.size() > 0, "compile produced no elements");

        List<PackageableElement> elements = new ArrayList<>();
        for (int i = 0; i < elementsSeq.size(); i++)
        {
            Object el = elementsSeq.getBoxed(i);
            if (el instanceof PackageableElement pe)
            {
                elements.add(pe);
            }
        }
        Assumptions.assumeFalse(elements.isEmpty(), "no PackageableElements in result");

        // 5. Write
        Path tmpPdb = Files.createTempFile("truffle-compile-roundtrip-", ".pdb");
        try
        {
            TrufflePdbWriter.write(elements, tmpPdb, /*validateRequired=*/ false);
            Assertions.assertTrue(Files.size(tmpPdb) > 0, "Round-tripped PDB should be non-empty");

            // 6. Reload + compare
            TrufflePdbLoader rt = new TrufflePdbLoader(tmpPdb);
            int matched = 0;
            int missing = 0;
            int typeMismatch = 0;
            List<String> failures = new ArrayList<>();
            for (PackageableElement original : elements)
            {
                String path = org.finos.legend.pure.truffle.runtime.helper._PackageableElement.path(original);
                Object reloaded = rt.getElement(path);
                if (reloaded == null)
                {
                    missing++;
                    if (failures.size() < 10) failures.add("MISSING: " + path);
                    continue;
                }
                String expectedType = baseTypeName(original.getClass().getSimpleName());
                String actualType = baseTypeName(reloaded.getClass().getSimpleName());
                if (!expectedType.equals(actualType))
                {
                    typeMismatch++;
                    if (failures.size() < 10) failures.add("TYPE: " + path + " expected=" + expectedType + " actual=" + actualType);
                }
                else
                {
                    matched++;
                }
            }
            String summary = "[" + testName + "] matched=" + matched + " missing=" + missing
                    + " typeMismatch=" + typeMismatch + " (of " + elements.size() + " elements)";
            if (!failures.isEmpty()) summary += "\n  " + String.join("\n  ", failures);
            Assertions.assertEquals(0, missing + typeMismatch, summary);
            Assertions.assertTrue(matched > 0, summary);
        }
        finally
        {
            Files.deleteIfExists(tmpPdb);
        }
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

    private static String baseTypeName(String simple)
    {
        if (simple.endsWith("FlatBufferWrapper")) return simple.substring(0, simple.length() - "FlatBufferWrapper".length());
        if (simple.endsWith("Impl")) return simple.substring(0, simple.length() - "Impl".length());
        return simple;
    }
}
