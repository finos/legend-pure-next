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

import meta.pure.metamodel.PackageableElement;
import org.finos.legend.pure.m3.module.CompilationError;
import org.finos.legend.pure.m3.module.Module;
import org.finos.legend.pure.m3.module.ModuleManifest;
import org.finos.legend.pure.m3.module.pdbModule.archive.CompressedArchiveWriter;
import org.finos.legend.pure.m3.module.pdbModule.archive.PDBExtension;
import org.finos.legend.pure.m3.pureLanguage.PureLanguageExtension;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

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
 * ({@code pure/specification/compiler/tests-pdb-serialization}) — they are reference
 * artifacts that validate every port, not a JS-platform fixture.
 *
 * <p>Regenerate: {@code mvn -pl ...bootstrap-compiler test -Dtest=PdbGoldenGeneratorTest
 * -Dpdb.goldens.dir=$PWD/pure/specification/compiler/tests-pdb-serialization}</p>
 */
public class PdbGoldenGeneratorTest
{
    private static final SpecTestRuntime RUNTIME = new SpecTestRuntime();

    public static Collection<org.junit.jupiter.params.provider.Arguments> discoverTests() throws Exception
    {
        return CompilerCompiledGraphPdbRoundTripTest.discoverTests();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("discoverTests")
    public void generate(String testName, String resourcePath) throws Exception
    {
        String dir = System.getProperty("pdb.goldens.dir");
        Assumptions.assumeTrue(dir != null && !dir.isEmpty(), "set -Dpdb.goldens.dir to generate goldens");

        String content = Files.readString(Path.of(resourcePath), StandardCharsets.UTF_8);
        if (content.contains("###Error"))
        {
            return; // error tests produce no compiled graph
        }

        CompiledSpec spec = RUNTIME.compileSpec(content, testName);
        List<String> errors = spec.result().errors().stream().map(CompilationError::message).toList();
        if (!errors.isEmpty())
        {
            return; // only round-trippable (clean) graphs get a golden
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
            if (!(elem instanceof meta.pure.metamodel.Package) && serializability.serialize(elem) != null)
            {
                serializablePaths.add(
                        org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._PackageableElement.path(elem));
            }
        }
        allModuleElements.removeIf(elem ->
        {
            if (!(elem instanceof meta.pure.metamodel.Package))
            {
                return false;
            }
            String prefix = org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._PackageableElement.path(elem) + "::";
            return serializablePaths.stream().noneMatch(p -> p.startsWith(prefix));
        });

        Path out = Path.of(dir).resolve(testName + ".pdb");
        Files.createDirectories(out.getParent());
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
                new ModuleManifest(testModule.getName(), testModule.getPackagePattern(), testModule.getDependencies()),
                additionalSections, out);
    }
}
