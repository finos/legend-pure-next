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
import org.finos.legend.pure.truffle.types.PureSequence;
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
import java.util.stream.Stream;

/**
 * Truffle-side analog of bootstrap's PdbRoundTripTest: parse Pure source,
 * compile it through the truffle interpreter, write the resulting metamodel
 * elements to a {@code .pdb} via {@link TrufflePdbWriter}, reload via
 * {@link TrufflePdbLoader}, and assert every compiled path round-trips.
 *
 * <p>{@link #smokeTest} compiles a single hardcoded class as a sanity check.
 * {@link #roundTripCompilerSpec} parameterises over every {@code ###Pure} test
 * file under {@code specification/compiler/tests/} (the same set bootstrap's
 * {@code PdbRoundTripTest} covers).</p>
 */
public class TruffleCompileToPdbTest
{
    private static PureTruffleRuntime runtime;
    private static TruffleMetadataAccess resolver;
    private static Object compileFn;
    private static Object manageFileSectionsFn;
    private static org.finos.legend.pure.truffle.parser.TrufflePureParser pureParser;

    private static Path locateBuildDir()
    {
        Path current = Path.of("").toAbsolutePath();
        while (current != null)
        {
            Path candidate = current.resolve("shared");
            if (Files.isDirectory(candidate)
                    && Files.exists(candidate.resolve("core.pdb"))
                    && Files.exists(candidate.resolve("compiler.pdb")))
            {
                return candidate;
            }
            current = current.getParent();
        }
        throw new RuntimeException("Cannot locate shared/core.pdb + shared/compiler.pdb");
    }

    private static Path locateCompilerSpecRoot()
    {
        Path current = Path.of("").toAbsolutePath();
        while (current != null)
        {
            Path candidate = current.resolve("pure").resolve("specification").resolve("compiler").resolve("tests");
            if (Files.isDirectory(candidate)) return candidate;
            current = current.getParent();
        }
        throw new RuntimeException("Cannot locate pure/specification/compiler/tests");
    }

    @BeforeAll
    static void setupOnce() throws IOException
    {
        Path buildDir = locateBuildDir();
        TrufflePdbLoader coreLoader = new TrufflePdbLoader(buildDir.resolve("core.pdb"));
        TrufflePdbLoader compilerLoader = new TrufflePdbLoader(buildDir.resolve("compiler.pdb"));
        TruffleModuleRegistry registry = new TruffleModuleRegistry();
        registry.register(coreLoader);
        registry.register(compilerLoader);
        resolver = registry;
        coreLoader.setResolver(resolver);
        compilerLoader.setResolver(resolver);
        coreLoader.preloadAll();
        compilerLoader.preloadAll();

        runtime = PureTruffleRuntime.builder()
                .withResolver(resolver)
                .withParserExtensions(List.of(
                        new TruffleCompiledGraphLanguageExtension(),
                        new TruffleCompilerStatsLanguageExtension(),
                        new TruffleTestFileLanguageExtension(),
                        new TruffleReverseIndexLanguageExtension(),
                        new TruffleErrorLanguageExtension()))
                .build();

        // 3-arg silent variant — tests pull errors/stats structurally off the
        // result; the live progress bar + stats summary would flood surefire output.
        compileFn = resolver.getElement("meta::pure::compiler::compile_PureFile_MANY__Boolean_1__Boolean_1__CompilationResult_1_");
        if (compileFn == null
                || !org.finos.legend.pure.truffle.runtime.dynobj.PureObj.isType(compileFn,
                "meta::pure::metamodel::function::FunctionDefinition", resolver))
        {
            throw new RuntimeException("compile FunctionDefinition not resolvable: "
                    + (compileFn == null ? "null" : compileFn.getClass().getName()));
        }
        manageFileSectionsFn = resolver.getElement("meta::pure::compiler::test::manageFileSections_PureFile_1__PureFile_MANY_");
        if (manageFileSectionsFn == null
                || !org.finos.legend.pure.truffle.runtime.dynobj.PureObj.isType(manageFileSectionsFn,
                "meta::pure::metamodel::function::FunctionDefinition", resolver))
        {
            throw new RuntimeException("manageFileSections FunctionDefinition not resolvable: "
                    + (manageFileSectionsFn == null ? "null" : manageFileSectionsFn.getClass().getName()));
        }

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

        // PDO PureFile straight from the parser — no protocol-Impl intermediate, no
        // ProtocolTranslator copy step.
        Object truffleFile = null;
        try
        {
            truffleFile = pureParser.parse(testName, pureSource);
        }
        catch (Exception e)
        {
            issues.add("parse failed: " + e.getClass().getSimpleName() + " " + e.getMessage());
        }
        if (truffleFile != null)
        {
            Object sections = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.read(truffleFile, "sections");
            boolean isEmpty = sectionsEmpty(sections);
            if (isEmpty)
            {
                issues.add("no sections to compile");
                truffleFile = null; // skip execute below
            }
        }

        Object result = null;
        if (truffleFile != null)
        {
            try
            {
                // Always split: turns a single test-source PureFile into one
                // PureFile per logical file (primary ###Pure + each ###File
                // chunk). Single-file specs collapse to a 1-element list.
                Object files = runtime.execute(manageFileSectionsFn, truffleFile);
                // debug=false, silent=true
                result = runtime.execute(compileFn, files, false, true);
            }
            catch (Exception e)
            {
                // Include the Pure-level frames — Truffle attaches them as
                // "Suppressed: Attached Guest Language Frames" and
                // PureStackFormatter turns them into readable `foo.pure:NcM`
                // lines. Without this the failure surface is just the Java
                // exception class + message; the Pure callsite is lost.
                issues.add("truffle compile threw: " + e.getClass().getSimpleName() + " " + e.getMessage()
                        + org.finos.legend.pure.truffle.runtime.PureStackFormatter.format(e));
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
                List<Object> elements = new ArrayList<>();
                for (int i = 0; i < elementsSeq.size(); i++)
                {
                    Object el = elementsSeq.getBoxed(i);
                    // Accept both typed XPDBHelper and PureDynamicObject elements.
                    if (org.finos.legend.pure.truffle.runtime.dynobj.PureObj.isType(el,
                            "meta::pure::metamodel::PackageableElement", resolver))
                    {
                        elements.add(el);
                    }
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
                        TrufflePdbWriter.write(elements,
                                new org.finos.legend.pure.m3.module.ModuleManifest("roundtrip", "*", java.util.List.of()),
                                tmpPdb, /*validateRequired=*/ false, resolver);
                        if (Files.size(tmpPdb) == 0) issues.add("Round-tripped PDB is empty");
                        TrufflePdbLoader rt = new TrufflePdbLoader(tmpPdb);
                        int matched = 0;
                        for (Object original : elements)
                        {
                            String path = org.finos.legend.pure.truffle.runtime.helper._PackageableElement.path(original, resolver);
                            Object reloaded = rt.getElement(path);
                            if (reloaded == null)
                            {
                                issues.add("MISSING: " + path);
                                continue;
                            }
                            // Compare Pure-level types via pureTypeOf, not Java class
                            // names — post-loader-flip both sides are PDOs whose
                            // Java simple name is "PureDynamicObject"; the Pure
                            // type is what actually identifies the metamodel class.
                            String expectedType = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.pureTypeOf(original);
                            String actualType = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.pureTypeOf(reloaded);
                            if (expectedType == null) expectedType = baseTypeName(original.getClass().getSimpleName());
                            if (actualType == null) actualType = baseTypeName(reloaded.getClass().getSimpleName());
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
        // accessor is `_X` form; strip leading underscore for PureObj.read
        // which expects the bare property name. Works for both typed XPDBHelper
        // and PureDynamicObject.
        String propName = accessor.startsWith("_") ? accessor.substring(1) : accessor;
        return org.finos.legend.pure.truffle.runtime.dynobj.PureObj.read(target, propName);
    }

    private static String baseTypeName(String simple)
    {
        if (simple.endsWith("FlatBufferWrapper")) return simple.substring(0, simple.length() - "FlatBufferWrapper".length());
        if (simple.endsWith("PDBHelper")) return simple.substring(0, simple.length() - "PDBHelper".length());
        return simple;
    }

    /** Empty-check on a PureFile.sections value (PureSequence | List | null). */
    private static boolean sectionsEmpty(Object sections)
    {
        if (sections == null) return true;
        if (sections instanceof PureSequence seq) return seq.size() == 0;
        if (sections instanceof java.util.List<?> list) return list.isEmpty();
        return false;
    }
}
