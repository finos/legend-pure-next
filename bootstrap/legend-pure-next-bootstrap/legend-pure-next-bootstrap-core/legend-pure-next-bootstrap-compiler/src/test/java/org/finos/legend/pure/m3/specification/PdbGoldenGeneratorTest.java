// ©2026 JP Morgan Chase & Co. All rights reserved.
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

import platform.pure.metamodel.PackageableElement;
import org.finos.legend.pure.m3.module.CompilationError;
import org.finos.legend.pure.m3.module.Module;
import org.finos.legend.pure.m3.module.ModuleManifest;
import org.finos.legend.pure.m3.module.pdbModule.archive.CompressedArchiveWriter;
import org.finos.legend.pure.m3.module.pdbModule.archive.PDBExtension;
import org.finos.legend.pure.m3.pureLanguage.PureLanguageExtension;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * One-shot generator for golden PDB files — the authoritative "what Java writes"
 * for each ###CompiledGraph spec test. Writes {@code <name>.pdb} (declaration-order
 * element set, exactly as {@link CompilerCompiledGraphPdbRoundTripTest} serializes)
 * under the directory named by {@code -Dpdb.goldens.dir=...}; skipped otherwise, so
 * it never runs in a normal build. The goldens live in the SPECIFICATION area
 * ({@code pure/specification/compiler/tests/pdb}) — they are reference
 * artifacts that validate every port, not a JS-platform fixture.
 *
 * <p>Regenerate: {@code bazel run //:update_pdb_goldens__compiler}, which runs this class with
 * {@code -Dpdb.goldens.dir} set to {@code pure/specification/compiler/tests/pdb}.</p>
 *
 * <p>Check: {@code bazel test //tools/bazel:test_pdb_goldens_up_to_date__compiler} runs it with
 * {@code -Dpdb.goldens.check} instead, writing nothing: it fails when a committed golden is not what the writer
 * writes today, byte for byte in every entry, when a fixture lacks its golden, or when a golden has no fixture. The
 * golden test of the ports compares decoded nodes, so a golden that only differs in layout passes there; this is what
 * keeps the bytes current.</p>
 */
public class PdbGoldenGeneratorTest
{
    private static final SpecTestRuntime RUNTIME = new SpecTestRuntime();

    public static Collection<org.junit.jupiter.params.provider.Arguments> discoverTests() throws Exception
    {
        return CompilerCompiledGraphPdbRoundTripTest.discoverTests();
    }

    private static final String UPDATE = "bazel run //:update_pdb_goldens__compiler";

    @ParameterizedTest(name = "{0}")
    @MethodSource("discoverTests")
    public void generate(String testName, String resourcePath) throws Exception
    {
        String dir = System.getProperty("pdb.goldens.dir");
        Assumptions.assumeTrue(dir != null && !dir.isEmpty(), "set -Dpdb.goldens.dir to generate goldens");

        byte[] archive = archive(testName, resourcePath);
        if (archive != null)
        {
            Path out = Path.of(dir).resolve(testName + ".pdb");
            Files.createDirectories(out.getParent());
            Files.write(out, archive);
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("discoverTests")
    public void upToDate(String testName, String resourcePath) throws Exception
    {
        String dir = System.getProperty("pdb.goldens.check");
        Assumptions.assumeTrue(dir != null && !dir.isEmpty(), "set -Dpdb.goldens.check to check goldens");

        byte[] archive = archive(testName, resourcePath);
        if (archive == null)
        {
            return;
        }
        Path golden = Path.of(dir).resolve(testName + ".pdb");
        if (!Files.isRegularFile(golden))
        {
            Assertions.fail("missing golden " + testName + ".pdb: run '" + UPDATE + "'");
        }
        String difference = firstDifference(entries(Files.readAllBytes(golden)), entries(archive));
        if (difference != null)
        {
            Assertions.fail("stale golden " + testName + ".pdb (" + difference + "): run '" + UPDATE + "'");
        }
    }

    @Test
    public void noGoldenWithoutFixture() throws Exception
    {
        String dir = System.getProperty("pdb.goldens.check");
        Assumptions.assumeTrue(dir != null && !dir.isEmpty(), "set -Dpdb.goldens.check to check goldens");

        java.util.Set<String> fixtures = new java.util.HashSet<>();
        for (org.junit.jupiter.params.provider.Arguments arguments : discoverTests())
        {
            fixtures.add((String) arguments.get()[0]);
        }
        Path root = Path.of(dir);
        List<String> orphans;
        try (java.util.stream.Stream<Path> walk = Files.walk(root))
        {
            orphans = walk.filter(p -> p.toString().endsWith(".pdb"))
                    .map(p -> root.relativize(p).toString().replace('\\', '/'))
                    .map(n -> n.substring(0, n.length() - 4))
                    // Island values: written by the Pure writer (update_pdb_goldens__island), not this class.
                    .filter(n -> !n.startsWith(CompilerCompiledGraphTest.ISLAND_VALUE_FIXTURES))
                    .filter(n -> !fixtures.contains(n))
                    .sorted()
                    .toList();
        }
        Assertions.assertTrue(orphans.isEmpty(), () -> "goldens without a fixture, to delete: " + orphans);
    }

    // The uncompressed bytes of each entry of an archive, by name in archive order: what the writer decides. The
    // deflated bytes also depend on the zlib of the JDK, which is not the writer's.
    private static java.util.Map<String, byte[]> entries(byte[] archive) throws IOException
    {
        java.util.Map<String, byte[]> entries = new java.util.LinkedHashMap<>();
        try (java.util.zip.ZipInputStream zip = new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(archive)))
        {
            for (java.util.zip.ZipEntry entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry())
            {
                entries.put(entry.getName(), zip.readAllBytes());
            }
        }
        return entries;
    }

    private static String firstDifference(java.util.Map<String, byte[]> golden, java.util.Map<String, byte[]> written)
    {
        if (!golden.keySet().equals(written.keySet()))
        {
            java.util.Set<String> missing = new java.util.TreeSet<>(written.keySet());
            missing.removeAll(golden.keySet());
            java.util.Set<String> extra = new java.util.TreeSet<>(golden.keySet());
            extra.removeAll(written.keySet());
            return "entries the writer writes but the golden lacks: " + missing + ", entries only in the golden: " + extra;
        }
        if (!List.copyOf(golden.keySet()).equals(List.copyOf(written.keySet())))
        {
            return "entries in another order";
        }
        for (java.util.Map.Entry<String, byte[]> entry : golden.entrySet())
        {
            byte[] expected = entry.getValue();
            byte[] actual = written.get(entry.getKey());
            int at = java.util.Arrays.mismatch(expected, actual);
            if (at >= 0)
            {
                return entry.getKey() + " differs from byte " + at + ", " + expected.length + " bytes in the golden, " + actual.length + " written";
            }
        }
        return null;
    }

    // The archive the writer writes for a fixture, or null when the fixture has none (an error fixture, or one that
    // does not compile cleanly).
    private static byte[] archive(String testName, String resourcePath) throws Exception
    {
        String content = Files.readString(Path.of(resourcePath), StandardCharsets.UTF_8);
        if (content.contains("###Error"))
        {
            return null; // error tests produce no compiled graph
        }

        CompiledSpec spec = RUNTIME.compileSpec(content, testName);
        List<String> errors = spec.result().errors().stream().map(CompilationError::message).toList();
        if (!errors.isEmpty())
        {
            return null; // only round-trippable (clean) graphs get a golden
        }

        Module testModule = spec.testModule();
        List<PackageableElement> allModuleElements = new ArrayList<>();
        for (String path : testModule.elementPaths())
        {
            PackageableElement elem = testModule.getElement(path);
            if (elem != null)
            {
                allModuleElements.add(elem);
            }
        }
        // Drop test-harness pollution: the parsed ###CompiledGraph section is
        // registered as a fixture element at <sourceId>::CompiledGraph, whose
        // auto-created parent Package (named after the source file) would
        // otherwise be written as an empty entry with a dangling child
        // pointer. Keep a Package only when a serializable non-Package
        // element lives beneath it — the same element set the ports' compile
        // results produce.
        PureLanguageExtension serializability = new PureLanguageExtension();
        java.util.Set<String> serializablePaths = new java.util.TreeSet<>();
        for (PackageableElement elem : allModuleElements)
        {
            if (!(elem instanceof platform.pure.metamodel.Package) && serializability.serialize(elem) != null)
            {
                serializablePaths.add(
                        org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._PackageableElement.path(elem));
            }
        }
        allModuleElements.removeIf(elem ->
        {
            if (!(elem instanceof platform.pure.metamodel.Package))
            {
                return false;
            }
            String prefix = org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._PackageableElement.path(elem) + "::";
            return serializablePaths.stream().noneMatch(p -> p.startsWith(prefix));
        });

        Path out = Files.createTempFile("golden", ".pdb");
        List<PDBExtension> pdbExtensions = List.of(new PureLanguageExtension());
        // Carry the reverse reference index like production writers do
        // (SpecificationBinaryBuilder / TruffleCompilerBinaryBuilder), so the
        // goldens pin that section's bytes for the ports too.
        List<org.finos.legend.pure.m3.module.pdbModule.archive.PDBArchiveSection> additionalSections = new ArrayList<>();
        org.finos.legend.pure.m3.module.pdbModule.archive.PDBArchiveSection riSection =
                org.finos.legend.pure.m3.module.pdbModule.archive.ReverseIndexSection.serialize(spec.result().referencedBy());
        if (riSection != null)
        {
            additionalSections.add(riSection);
        }
        new CompressedArchiveWriter().write(allModuleElements, pdbExtensions, testModule,
                new ModuleManifest(testModule.name(), testModule.packagePattern(), testModule.dependencies()),
                additionalSections, out);
        try
        {
            return Files.readAllBytes(out);
        }
        finally
        {
            Files.delete(out);
        }
    }
}
