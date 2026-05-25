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
import org.eclipse.collections.api.factory.Lists;
import org.eclipse.collections.api.list.MutableList;
import org.finos.legend.pure.m3.LanguageExtension;
import org.finos.legend.pure.m3.PureModel;
import org.finos.legend.pure.m3.module.CompilationResult;
import org.finos.legend.pure.m3.module.Module;
import org.finos.legend.pure.m3.module.ModuleManifest;
import org.finos.legend.pure.m3.module.TestElementFilter;
import org.finos.legend.pure.m3.module.localModule.LocalModule;
import org.finos.legend.pure.m3.module.pdbModule.PDBModule;
import org.finos.legend.pure.m3.module.pdbModule.archive.CompressedArchiveWriter;
import org.finos.legend.pure.m3.pureLanguage.PureLanguageExtension;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * Compiles Pure compiler helper files against the core.pdb standard library.
 *
 * <p>Usage: {@code java CompilerBinaryBuilder <corePdb> <sourceDir> <outputFile.pdb>}</p>
 */
public class CompilerBinaryBuilder
{
    public static void main(String[] args) throws Exception
    {
        List<Path> basePdbs = new ArrayList<>();
        Path sourceDir = null;
        Path outputDir = null;
        TestElementFilter.Mode mode = TestElementFilter.Mode.NONE;
        for (int i = 0; i < args.length; i++)
        {
            switch (args[i])
            {
                case "--base-pdb" -> basePdbs.add(Path.of(args[++i]));
                case "--source" -> sourceDir = Path.of(args[++i]);
                case "--output-dir" -> outputDir = Path.of(args[++i]);
                case "--tests" -> mode = TestElementFilter.Mode.parse(args[++i]);
                default -> throw new IllegalArgumentException("Unknown option: " + args[i]);
            }
        }
        if (basePdbs.isEmpty() || sourceDir == null || outputDir == null)
        {
            System.err.println("Usage: CompilerBinaryBuilder --base-pdb <file>... --source <sourceDir> --output-dir <dir> [--tests {none|with|only|split}]");
            System.err.println("  Repeat --base-pdb to load multiple dependency PDBs (e.g. core + java).");
            System.err.println("  module.json is auto-discovered by walking up from <sourceDir>.");
            System.err.println("  Output filename comes from module manifest 'name'.");
            System.exit(1);
        }

        compile(basePdbs, sourceDir, outputDir, mode);
    }

    public static void compile(List<Path> basePdbs, Path sourceDir, Path outputDir, TestElementFilter.Mode mode) throws IOException
    {
        ModuleManifest manifest = ModuleManifest.locate(sourceDir);
        Path outputFile = outputDir.resolve(manifest.name() + ".pdb");

        System.out.println();
        System.out.println("Pure Compiler Binary Builder From Pure Files (PDB)");
        System.out.println("==================================================");
        System.out.println("  Inputs: " + sourceDir);
        for (Path p : basePdbs)
        {
            System.out.println("          " + p);
        }
        System.out.println("  Manifest: module='" + manifest.name() + "', deps=" + manifest.dependencies());
        System.out.println("  Output dir: " + outputDir);
        System.out.println("  Tests mode: " + mode);

        // Load each base PDB — identity comes from the manifest embedded inside the archive.
        List<PDBModule> baseModules = new ArrayList<>(basePdbs.size());
        List<String> loadedNames = new ArrayList<>(basePdbs.size());
        for (Path p : basePdbs)
        {
            PDBModule mod = new PDBModule(p, PDBModule.Mode.COMPILATION);
            baseModules.add(mod);
            loadedNames.add(mod.getName());
        }
        verifyDependencies(manifest, loadedNames);

        // Local module for the source files, identity from the source manifest
        LocalModule localModule = new LocalModule(
                manifest.name(), manifest.packagePattern(), manifest.dependencies(), sourceDir);

        MutableList<Module> modules = Lists.mutable.empty();
        modules.addAll(baseModules);
        modules.add(localModule);
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

        // Collect elements from the local module only, excluding any already present in a base PDB
        LinkedHashMap<String, PackageableElement> elementsByPath = new LinkedHashMap<>();
        for (String path : localModule.elementPaths())
        {
            if (elementsByPath.containsKey(path)) continue;
            boolean inBase = false;
            for (PDBModule b : baseModules)
            {
                if (b.hasElement(path)) { inBase = true; break; }
            }
            if (inBase) continue;
            PackageableElement element = localModule.getElement(path);
            if (element != null)
            {
                elementsByPath.put(path, element);
            }
        }
        List<PackageableElement> elements = new ArrayList<>(elementsByPath.values());
        System.out.println("  Compiled " + elements.size() + " elements");

        Files.createDirectories(outputFile.getParent());
        writeFiltered(elements, extensions, localModule, manifest, outputFile, mode, result.referencedBy());
    }

    private static void writeFiltered(
            List<PackageableElement> elements,
            MutableList<LanguageExtension> extensions,
            LocalModule localModule,
            ModuleManifest manifest,
            Path outputFile,
            TestElementFilter.Mode mode,
            java.util.Map<String, java.util.Set<String>> referencedBy) throws IOException
    {
        switch (mode)
        {
            case WITH ->
                    writePdb("full", elements, extensions, localModule, manifest,
                            TestElementFilter.withTestsPath(outputFile), referencedBy);
            case NONE ->
            {
                List<PackageableElement> lean = elements.stream()
                        .filter(e -> !TestElementFilter.isTestElement(e))
                        .toList();
                java.util.Set<String> leanPaths = pathSet(lean);
                writePdb("lean (" + lean.size() + "/" + elements.size() + ")",
                        lean, extensions, localModule, manifest, outputFile,
                        org.finos.legend.pure.m3.module.pdbModule.archive.ReverseIndexSection.filter(referencedBy, leanPaths::contains));
            }
            case ONLY ->
            {
                List<PackageableElement> tests = elements.stream()
                        .filter(TestElementFilter::isTestElement)
                        .toList();
                java.util.Set<String> testPaths = pathSet(tests);
                writePdb("tests-only (" + tests.size() + "/" + elements.size() + ")",
                        tests, extensions, localModule, TestElementFilter.testsManifest(manifest),
                        TestElementFilter.testsOnlyPath(outputFile),
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
                java.util.Set<String> leanPaths = pathSet(lean);
                java.util.Set<String> testPaths = pathSet(tests);
                writePdb("lean (" + lean.size() + "/" + elements.size() + ")",
                        lean, extensions, localModule, manifest, outputFile,
                        org.finos.legend.pure.m3.module.pdbModule.archive.ReverseIndexSection.filter(referencedBy, leanPaths::contains));
                writePdb("tests-only (" + tests.size() + "/" + elements.size() + ")",
                        tests, extensions, localModule, TestElementFilter.testsManifest(manifest),
                        TestElementFilter.testsOnlyPath(outputFile),
                        org.finos.legend.pure.m3.module.pdbModule.archive.ReverseIndexSection.filter(referencedBy, testPaths::contains));
            }
        }
    }

    private static java.util.Set<String> pathSet(List<PackageableElement> elements)
    {
        java.util.Set<String> set = new java.util.HashSet<>();
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
            java.util.Map<String, java.util.Set<String>> referencedBy) throws IOException
    {
        java.util.List<org.finos.legend.pure.m3.module.pdbModule.archive.PDBArchiveSection> sections = new ArrayList<>();
        org.finos.legend.pure.m3.module.pdbModule.archive.PDBArchiveSection riSection =
                org.finos.legend.pure.m3.module.pdbModule.archive.ReverseIndexSection.serialize(referencedBy);
        if (riSection != null) sections.add(riSection);
        new CompressedArchiveWriter().write(elements, extensions, localModule, manifest, sections, target);
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
}
