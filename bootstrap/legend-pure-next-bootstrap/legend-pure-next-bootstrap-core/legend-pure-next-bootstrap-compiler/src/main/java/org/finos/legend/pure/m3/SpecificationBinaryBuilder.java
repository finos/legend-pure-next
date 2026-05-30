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

package org.finos.legend.pure.m3;

import meta.pure.metamodel.PackageableElement;
import org.eclipse.collections.api.factory.Lists;
import org.eclipse.collections.api.list.MutableList;
import org.finos.legend.pure.m3.module.CompilationResult;
import org.finos.legend.pure.m3.module.Module;
import org.finos.legend.pure.m3.module.ModuleManifest;
import org.finos.legend.pure.m3.module.TestElementFilter;
import org.finos.legend.pure.m3.module.bootstrapModule.BootstrapModule;
import org.finos.legend.pure.m3.module.localModule.LocalModule;
import org.finos.legend.pure.m3.module.pdbModule.archive.CompressedArchiveWriter;
import org.finos.legend.pure.m3.pureLanguage.PureLanguageExtension;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Map;
import java.util.Set;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * Command-line tool to compile Pure source files into a .pdb archive.
 *
 * <p>Usage: {@code java SpecificationBinaryBuilder <m3.ttl> <sourceDir...> <outputFile.pdb>}</p>
 *
 * <p>Compiles the Pure source files and writes a compressed FlatBuffer
 * archive containing the compiled elements, including all bootstrap M3 types.</p>
 */
public class SpecificationBinaryBuilder
{
    public static void main(String[] args) throws Exception
    {
        Path m3TtlPath = null;
        Path outputDir = null;
        TestElementFilter.Mode mode = TestElementFilter.Mode.NONE;
        List<String> sourceDirArgs = new ArrayList<>();
        for (int i = 0; i < args.length; i++)
        {
            switch (args[i])
            {
                case "--m3-ttl" -> m3TtlPath = Path.of(args[++i]);
                case "--tests" -> mode = TestElementFilter.Mode.parse(args[++i]);
                case "--output-dir" -> outputDir = Path.of(args[++i]);
                default -> sourceDirArgs.add(args[i]);
            }
        }
        if (m3TtlPath == null || outputDir == null || sourceDirArgs.isEmpty())
        {
            System.err.println("Usage: SpecificationBinaryBuilder --m3-ttl <file> --output-dir <dir> [--tests {none|with|only|split}] <sourceDir...>");
            System.err.println("  module.json is auto-discovered by walking up from the first <sourceDir>.");
            System.err.println("  Output filename comes from module manifest 'name'.");
            System.err.println("  --tests filter (default 'none'):");
            System.err.println("    none  : lean PDB (no <<test.*>>-annotated elements)         -> <dir>/<name>.pdb");
            System.err.println("    with  : full PDB (everything)                                -> <dir>/<name>-with-tests.pdb");
            System.err.println("    only  : tests-only PDB (only <<test.*>>-annotated elements)  -> <dir>/<name>-tests.pdb");
            System.err.println("    split : both lean and tests-only PDBs                        -> <dir>/<name>.pdb + <dir>/<name>-tests.pdb");
            System.exit(1);
        }
        List<Path> sourceDirs = new ArrayList<>();
        for (String s : sourceDirArgs)
        {
            sourceDirs.add(Path.of(s));
        }

        compile(m3TtlPath, sourceDirs, outputDir, mode);
    }

    /**
     * Compile all .pure files under sourceDir and write one or two .pdb archives
     * into {@code outputDir} depending on {@code mode}. The output filename
     * comes from the module manifest's {@code name} field (e.g.
     * {@code <name>.pdb} for the lean PDB). The archive includes both
     * bootstrap M3 types and locally compiled elements, driven from the
     * module index rather than scanning the package tree.
     *
     * <p>The {@code module.json} manifest is auto-discovered by walking up
     * from {@code sourceDirs.get(0)}.</p>
     *
     * <p>See {@link TestElementFilter.Mode} for the meaning of each mode and
     * the corresponding output filenames.</p>
     */
    public static void compile(Path m3TtlPath, List<Path> sourceDirs, Path outputDir, TestElementFilter.Mode mode) throws IOException
    {
        ModuleManifest manifest = ModuleManifest.locate(sourceDirs.get(0));
        Path outputFile = outputDir.resolve(manifest.name() + ".pdb");

        System.out.println();
        System.out.println("Pure Specification Binary Builder From TTL And Pure Files (PDB)");
        System.out.println("===============================================================");
        System.out.println("  Inputs: " + m3TtlPath);
        for (Path src : sourceDirs)
        {
            System.out.println("          " + src);
        }
        System.out.println("  Manifest: module='" + manifest.name() + "', deps=" + manifest.dependencies());
        System.out.println("  Output dir: " + outputDir);
        System.out.println("  Tests mode: " + mode);

        // --- Compile ---
        // m3 is the bootstrap supply of metamodel types (Class, Property, ...).
        // It is NOT a runtime dependency — its elements get folded into the
        // resulting core.pdb during serialization. So the manifest declares
        // no m3 dep (consumers don't need to load m3.ttl), but the build-time
        // LocalModule sees m3 so element resolution finds those types.
        BootstrapModule m3 = new BootstrapModule(m3TtlPath);
        List<String> buildDeps = new ArrayList<>(manifest.dependencies());
        buildDeps.add(m3.getName());
        LocalModule localModule = new LocalModule(
                manifest.name(), manifest.packagePattern(), sourceDirs, buildDeps);
        MutableList<Module> modules = Lists.mutable.with(m3, localModule);
        MutableList<LanguageExtension> extensions = Lists.mutable.with(new PureLanguageExtension());
        PureModel model = PureModel.withModules(modules).withExtensions(extensions).build();
        CompilationResult result = model.compile();

        if (!result.errors().isEmpty())
        {
            System.err.println("Compilation errors:");
            result.errors().forEach(e -> System.err.println("  " + e.message()));
            throw new RuntimeException("Pure compilation failed with " + result.errors().size() + " error(s)");
        }

        System.out.print(result.statistics().summary());

        // --- Collect elements from the index (all modules), deduplicated by path ---
        LinkedHashMap<String, PackageableElement> elementsByPath = new LinkedHashMap<>();
        for (Module module : modules)
        {
            for (String path : module.elementPaths())
            {
                if (!elementsByPath.containsKey(path))
                {
                    PackageableElement element = module.getElement(path);
                    if (element != null)
                    {
                        elementsByPath.put(path, element);
                    }
                }
            }
        }
        List<PackageableElement> elements = new ArrayList<>(elementsByPath.values());
        System.out.println("  Compiled " + elements.size() + " elements");

        // --- Partition by test-stereotype + serialize per mode ---
        writeFiltered(elements, extensions, localModule, manifest, outputFile, mode, result.referencedBy());
    }

    /**
     * Partition {@code elements} into lean (no test stereotypes) and tests-only
     * (test-stereotyped) buckets and write one or two PDBs based on {@code mode}.
     * The reverse reference index is partitioned alongside: each output PDB
     * carries only the index entries whose callers belong to that PDB.
     */
    private static void writeFiltered(
            List<PackageableElement> elements,
            MutableList<LanguageExtension> extensions,
            LocalModule localModule,
            ModuleManifest manifest,
            Path outputFile,
            TestElementFilter.Mode mode,
            Map<String, Set<String>> referencedBy) throws IOException
    {
        Files.createDirectories(outputFile.getParent());

        switch (mode)
        {
            case WITH ->
            {
                Path target = TestElementFilter.withTestsPath(outputFile);
                writePdb("full", elements, extensions, localModule, manifest, target, referencedBy);
            }
            case NONE ->
            {
                List<PackageableElement> lean = elements.stream()
                        .filter(e -> !TestElementFilter.isTestElement(e))
                        .toList();
                Set<String> leanPaths = pathSet(lean);
                writePdb("lean (" + lean.size() + "/" + elements.size() + ")",
                        lean, extensions, localModule, manifest, outputFile,
                        org.finos.legend.pure.m3.module.pdbModule.archive.ReverseIndexSection.filter(referencedBy, leanPaths::contains));
            }
            case ONLY ->
            {
                List<PackageableElement> tests = elements.stream()
                        .filter(TestElementFilter::isTestElement)
                        .toList();
                Set<String> testPaths = pathSet(tests);
                Path target = TestElementFilter.testsOnlyPath(outputFile);
                writePdb("tests-only (" + tests.size() + "/" + elements.size() + ")",
                        tests, extensions, localModule, TestElementFilter.testsManifest(manifest), target,
                        org.finos.legend.pure.m3.module.pdbModule.archive.ReverseIndexSection.filter(referencedBy, testPaths::contains));
            }
            case SPLIT ->
            {
                List<PackageableElement> lean = new ArrayList<>(elements.size());
                List<PackageableElement> tests = new ArrayList<>();
                for (PackageableElement e : elements)
                {
                    (TestElementFilter.isTestElement(e) ? tests : lean).add(e);
                }
                Set<String> leanPaths = pathSet(lean);
                Set<String> testPaths = pathSet(tests);
                // Rewrite Package PDOs so each Package's children only point
                // at paths in the same PDB; add shadow Packages to the tests
                // half for parent paths whose canonical owner lives in lean.
                // Runtime {@link org.finos.legend.pure.m3.module.MergedPackage}
                // unions both halves when both PDBs are loaded.
                List<PackageableElement> leanFiltered =
                        org.finos.legend.pure.m3.module.PackageSplitFilter.filterPackageChildren(lean, leanPaths);
                List<PackageableElement> testsWithShadows =
                        org.finos.legend.pure.m3.module.PackageSplitFilter.withShadowPackages(tests, lean, testPaths);
                writePdb("lean (" + leanFiltered.size() + "/" + elements.size() + ")",
                        leanFiltered, extensions, localModule, manifest, outputFile,
                        org.finos.legend.pure.m3.module.pdbModule.archive.ReverseIndexSection.filter(referencedBy, leanPaths::contains));
                writePdb("tests-only (" + testsWithShadows.size() + "/" + elements.size() + ")",
                        testsWithShadows, extensions, localModule, TestElementFilter.testsManifest(manifest),
                        TestElementFilter.testsOnlyPath(outputFile),
                        org.finos.legend.pure.m3.module.pdbModule.archive.ReverseIndexSection.filter(referencedBy, testPaths::contains));
            }
        }
    }

    /** Compute the set of element paths in {@code elements}. */
    private static Set<String> pathSet(List<PackageableElement> elements)
    {
        Set<String> set = new java.util.HashSet<>();
        for (PackageableElement e : elements)
        {
            String p = org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._PackageableElement.path(e);
            if (p != null) set.add(p);
        }
        return set;
    }

    private static void writePdb(
            String label,
            List<PackageableElement> elements,
            MutableList<LanguageExtension> extensions,
            LocalModule localModule,
            ModuleManifest manifest,
            Path target,
            Map<String, Set<String>> referencedBy) throws IOException
    {
        List<org.finos.legend.pure.m3.module.pdbModule.archive.PDBArchiveSection> sections = new ArrayList<>();
        org.finos.legend.pure.m3.module.pdbModule.archive.PDBArchiveSection riSection =
                org.finos.legend.pure.m3.module.pdbModule.archive.ReverseIndexSection.serialize(referencedBy);
        if (riSection != null)
        {
            sections.add(riSection);
        }
        new CompressedArchiveWriter().write(elements, extensions, localModule, manifest, sections, target);
        System.out.println("    Written: " + target + " [" + label + ", " + Files.size(target) + " bytes, "
                + (referencedBy == null ? 0 : referencedBy.size()) + " ref targets]");
    }
}
