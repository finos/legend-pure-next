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
import org.finos.legend.pure.m3.module.bootstrapModule.BootstrapModule;
import org.finos.legend.pure.m3.module.localModule.LocalModule;
import org.finos.legend.pure.m3.module.pdbModule.archive.CompressedArchiveWriter;
import org.finos.legend.pure.m3.pureLanguage.PureLanguageExtension;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
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
        List<String> positional = new ArrayList<>();
        for (int i = 0; i < args.length; i++)
        {
            if ("--m3-ttl".equals(args[i]))
            {
                m3TtlPath = Path.of(args[++i]);
            }
            else
            {
                positional.add(args[i]);
            }
        }
        if (m3TtlPath == null || positional.size() < 2)
        {
            System.err.println("Usage: SpecificationBinaryBuilder --m3-ttl <file> <sourceDir...> <outputFile.pdb>");
            System.err.println("  module.json is auto-discovered by walking up from the first <sourceDir>.");
            System.exit(1);
        }
        List<Path> sourceDirs = new ArrayList<>();
        for (int i = 0; i < positional.size() - 1; i++)
        {
            sourceDirs.add(Path.of(positional.get(i)));
        }
        Path outputFile = Path.of(positional.get(positional.size() - 1));

        compile(m3TtlPath, sourceDirs, outputFile);
    }

    /**
     * Compile all .pure files under sourceDir and write a .pdb archive.
     * The archive includes both bootstrap M3 types and locally compiled elements,
     * driven from the module index rather than scanning the package tree.
     *
     * <p>The {@code module.json} manifest is auto-discovered by walking up
     * from {@code sourceDirs.get(0)}.</p>
     */
    public static void compile(Path m3TtlPath, List<Path> sourceDirs, Path outputFile) throws IOException
    {
        ModuleManifest manifest = ModuleManifest.locate(sourceDirs.get(0));

        System.out.println();
        System.out.println("Pure Specification Binary Builder From TTL And Pure Files (PDB)");
        System.out.println("===============================================================");
        System.out.println("  Inputs: " + m3TtlPath);
        for (Path src : sourceDirs)
        {
            System.out.println("          " + src);
        }
        System.out.println("  Manifest: module='" + manifest.name() + "', deps=" + manifest.dependencies());
        System.out.println("  Output: " + outputFile);

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
        // --- Serialize to .pdb ---
        Files.createDirectories(outputFile.getParent());
        new CompressedArchiveWriter().write(elements, extensions, localModule, manifest, List.of(), outputFile);
        System.out.println("    Written: " + outputFile + " (" + Files.size(outputFile) + " bytes)");
    }

}
