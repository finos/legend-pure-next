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

package org.finos.legend.pure.m3.specification;

import meta.pure.metamodel.PackageableElement;
import meta.pure.protocol.PureFile;
import org.eclipse.collections.api.factory.Lists;
import org.finos.legend.pure.m3.PureModel;
import org.finos.legend.pure.m3.extensions.compiledgraph.CompiledGraph;
import org.finos.legend.pure.m3.extensions.compiledgraph.CompiledGraphLanguageExtension;
import org.finos.legend.pure.m3.extensions.compilerstats.CompilerStatsLanguageExtension;
import org.finos.legend.pure.m3.extensions.testfile.TestFileLanguageExtension;
import org.finos.legend.pure.m3.module.CompilationError;
import org.finos.legend.pure.m3.module.CompilationResult;
import org.finos.legend.pure.m3.module.Module;
import org.finos.legend.pure.m3.module.bootstrapModule.BootstrapModule;
import org.finos.legend.pure.m3.module.localModule.LocalModule;
import org.finos.legend.pure.m3.module.localModule.PureContent;
import org.finos.legend.pure.m3.module.pdbModule.PDBModule;
import org.finos.legend.pure.m3.printer.CompiledGraphPrinter;
import org.finos.legend.pure.m3.pureLanguage.PureLanguageExtension;
import org.finos.legend.pure.next.parser.PureParser;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import org.finos.legend.pure.m3.module.pdbModule.archive.CompressedArchiveWriter;
import org.finos.legend.pure.m3.module.pdbModule.archive.PDBExtension;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.stream.Stream;

/**
 * PDB round-trip test: compile → write to PDB → read back → print compiled graph.
 *
 * <p>Reuses the same specification/compiler/tests test files as {@link CompilerCompiledGraphTest}
 * but adds a PDB serialization/deserialization cycle. The compiled graph after the
 * round-trip must match the original compiled graph.</p>
 *
 * <p>This ensures the PDB preserves all compiled information:
 * properties, generalizations, expression sequences, lambda open variables, etc.</p>
 */
public class CompilerCompiledGraphPdbRoundTripTest
{
    private static final String SECTION_MARKER = "###CompiledGraph";

    private static Path locateTestsRoot()
    {
        Path current = Path.of("").toAbsolutePath();
        while (current != null)
        {
            Path candidate = current.resolve("pure").resolve("specification").resolve("compiler").resolve("tests");
            if (Files.isDirectory(candidate))
            {
                return candidate;
            }
            current = current.getParent();
        }
        throw new RuntimeException("Cannot locate pure/specification/compiler/tests");
    }

    public static Collection<Arguments> discoverTests() throws IOException
    {
        List<Arguments> tests = new ArrayList<>();
        Path rootDir = locateTestsRoot();
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
                            if (content.contains(SECTION_MARKER))
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

    private static final SpecTestRuntime RUNTIME = new SpecTestRuntime();

    @ParameterizedTest(name = "{0}")
    @MethodSource("discoverTests")
    public void testPdbRoundTrip(String testName, String resourcePath) throws Exception
    {
        String content = Files.readString(Path.of(resourcePath), StandardCharsets.UTF_8);

        // Skip error tests — they don't produce a compiled graph to round-trip
        if (content.contains("###Error"))
        {
            return;
        }

        // --- Step 1: Compile in memory ---
        CompiledSpec spec = RUNTIME.compileSpec(content, testName);

        List<String> errors = spec.result().errors().stream().map(CompilationError::message).toList();
        Assertions.assertTrue(errors.isEmpty(), "Compilation errors: " + String.join("\n", errors));

        Module testModule = spec.testModule();
        List<PackageableElement> compiledElements = spec.compiledElementsInDeclarationOrder();
        String expectedGraph = CompiledGraphPrinter.print(compiledElements).stripTrailing();

        // --- Step 2: Write to PDB ---
        Path tempPdb = Files.createTempFile("pdb-roundtrip-", ".pdb");
        try
        {
            // Write ALL elements from the test module (including packages, profiles, etc.)
            List<PackageableElement> allModuleElements = new ArrayList<>();
            for (String path : testModule.elementPaths())
            {
                PackageableElement elem = testModule.getElement(path);
                if (elem != null)
                {
                    allModuleElements.add(elem);
                }
            }
            List<PDBExtension> pdbExtensions = List.of(new PureLanguageExtension());
            try
            {
                new CompressedArchiveWriter().write(allModuleElements, pdbExtensions, testModule,
                        new org.finos.legend.pure.m3.module.ModuleManifest(
                                testModule.getName(), testModule.getPackagePattern(), testModule.getDependencies()),
                        List.of(), tempPdb);
            }
            catch (IllegalArgumentException e)
            {
                // Serialization validation failure — record as a known PDB issue
                Assertions.fail("PDB serialization failed for " + testName + ": " + e.getMessage());
            }

            // --- Step 3: Read back from PDB ---
            PDBModule roundTripModule = new PDBModule(tempPdb, PDBModule.Mode.EXECUTION);
            PureModel model2 = PureModel.withModules(Lists.mutable.with(roundTripModule, RUNTIME.coreModule()))
                    .withExtensions(Lists.mutable.with(new PureLanguageExtension()))
                    .build();
            model2.compile();

            // --- Step 4: Print compiled graph from PDB-loaded elements ---
            List<PackageableElement> pdbElements = new ArrayList<>();
            for (PackageableElement elem : compiledElements)
            {
                String path = org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._PackageableElement.path(elem);
                PackageableElement fromPdb = roundTripModule.getElement(path);
                if (fromPdb != null)
                {
                    pdbElements.add(fromPdb);
                }
            }

            String actualGraph = CompiledGraphPrinter.print(pdbElements).stripTrailing();

            // --- Step 5: Compare ---
            Assertions.assertEquals(expectedGraph, actualGraph,
                    "PDB round-trip mismatch for " + testName);
        }
        finally
        {
            Files.deleteIfExists(tempPdb);
        }
    }

}
