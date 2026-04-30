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
import org.junit.jupiter.api.BeforeAll;
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
                            // Mirror bootstrap PdbRoundTripTest exactly: discover
                            // every spec with a ###CompiledGraph section. Error
                            // specs (###Error) are filtered at runtime in
                            // runRoundTrip — same as PdbRoundTripTest does — so
                            // the discovered count matches.
                            if (content.contains("###CompiledGraph"))
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

    /**
     * One JUnit test per compiler spec — each shows up independently in
     * CI. Within a single spec we still buffer all issues (parse, compile,
     * write/read) and throw once with the full list, so a partial failure
     * surfaces every problem rather than just the first.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("discoverCompilerSpecTests")
    public void roundTripCompilerSpec(String testName, String resourcePath) throws Exception
    {
        String content = Files.readString(Path.of(resourcePath), StandardCharsets.UTF_8);
        List<String> issues = new ArrayList<>();
        try
        {
            runRoundTrip(testName, content, issues);
        }
        catch (Throwable t)
        {
            issues.add("unexpected: " + t.getClass().getSimpleName() + " " + t.getMessage());
        }
        if (!issues.isEmpty())
        {
            throw new AssertionError("[" + testName + "] " + issues.size() + " issue(s):\n  "
                    + String.join("\n  ", issues));
        }
    }

    /**
     * Run one spec round-trip, appending any failures to {@code issues}.
     * Discovery (mirroring PdbRoundTripTest) only includes files with a
     * ###CompiledGraph section. A spec that ALSO declares ###Error is
     * malformed (compile-success and compile-error are mutually exclusive
     * for round-trip) — surface that as a hard failure.
     */
    private static void runRoundTrip(String testName, String pureSource, List<String> issues) throws IOException
    {
        if (pureSource.contains("###Error"))
        {
            issues.add("malformed spec: contains both ###CompiledGraph and ###Error — these are mutually exclusive");
            return;
        }

        meta.pure.protocol.PureFile bootstrapFile = null;
        try
        {
            bootstrapFile = pureParser.parse(testName, pureSource);
        }
        catch (Exception e)
        {
            issues.add("parse failed: " + e.getClass().getSimpleName() + " " + e.getMessage());
        }
        if (bootstrapFile != null && bootstrapFile._sections().isEmpty())
        {
            issues.add("no sections to compile");
        }

        Object truffleFile = null;
        if (bootstrapFile != null && !bootstrapFile._sections().isEmpty())
        {
            try
            {
                truffleFile = new ProtocolTranslator(resolver).translate(bootstrapFile);
                if (truffleFile == null)
                {
                    issues.add("ProtocolTranslator returned null");
                }
            }
            catch (Exception e)
            {
                issues.add("ProtocolTranslator threw: " + e.getClass().getSimpleName() + " " + e.getMessage());
            }
        }

        Object result = null;
        if (truffleFile != null)
        {
            try
            {
                result = runtime.execute(compileFn, truffleFile);
            }
            catch (Exception e)
            {
                issues.add("truffle compile threw: " + e.getClass().getSimpleName() + " " + e.getMessage());
            }
            if (result == null && issues.stream().noneMatch(s -> s.startsWith("truffle compile threw")))
            {
                issues.add("truffle compile returned null");
            }
        }

        // Surface compilation-level errors the Pure compiler reports.
        if (result != null)
        {
            Object errorsObj = invokeAccessor(result, "_errors");
            if (errorsObj instanceof PureSequence errorsSeq && errorsSeq.size() > 0)
            {
                for (int i = 0; i < errorsSeq.size(); i++)
                {
                    issues.add("compile error: " + errorsSeq.getBoxed(i));
                }
            }
        }

        // Walk the PackageableElement output and run the writer/reader
        // round-trip if the compile produced anything usable.
        Path tmpPdb = null;
        if (result != null && issues.isEmpty())
        {
            Object elementsObj = invokeAccessor(result, "_elements");
            if (!(elementsObj instanceof PureSequence elementsSeq))
            {
                issues.add("_elements() returned " + (elementsObj == null ? "null" : elementsObj.getClass().getName()));
            }
            else if (elementsSeq.size() == 0)
            {
                issues.add("compile produced no elements");
            }
            else
            {
                List<PackageableElement> elements = new ArrayList<>();
                for (int i = 0; i < elementsSeq.size(); i++)
                {
                    Object el = elementsSeq.getBoxed(i);
                    if (el instanceof PackageableElement pe) elements.add(pe);
                }
                if (elements.isEmpty())
                {
                    issues.add("no PackageableElements in result");
                }
                else
                {
                    tmpPdb = Files.createTempFile("truffle-compile-roundtrip-", ".pdb");
                    try
                    {
                        TrufflePdbWriter.write(elements, tmpPdb, /*validateRequired=*/ false);
                        if (Files.size(tmpPdb) == 0) issues.add("Round-tripped PDB is empty");
                        TrufflePdbLoader rt = new TrufflePdbLoader(tmpPdb);
                        int matched = 0;
                        for (PackageableElement original : elements)
                        {
                            String path = org.finos.legend.pure.truffle.runtime.helper._PackageableElement.path(original);
                            Object reloaded = rt.getElement(path);
                            if (reloaded == null)
                            {
                                issues.add("MISSING: " + path);
                                continue;
                            }
                            String expectedType = baseTypeName(original.getClass().getSimpleName());
                            String actualType = baseTypeName(reloaded.getClass().getSimpleName());
                            if (!expectedType.equals(actualType))
                            {
                                issues.add("TYPE: " + path + " expected=" + expectedType + " actual=" + actualType);
                            }
                            else
                            {
                                matched++;
                            }
                        }
                        if (matched == 0) issues.add("zero elements matched after round-trip (of " + elements.size() + ")");
                    }
                    catch (Exception e)
                    {
                        issues.add("write/read threw: " + e.getClass().getSimpleName() + " " + e.getMessage());
                    }
                }
            }
        }

        if (tmpPdb != null) Files.deleteIfExists(tmpPdb);
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
