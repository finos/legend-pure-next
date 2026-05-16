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
        if (args.length < 3)
        {
            System.err.println("Usage: CompilerBinaryBuilder <corePdb> <sourceDir> <outputFile.pdb>");
            System.err.println("  module.json is auto-discovered by walking up from <sourceDir>.");
            System.exit(1);
        }

        Path corePdb = Path.of(args[0]);
        Path sourceDir = Path.of(args[1]);
        Path outputFile = Path.of(args[2]);

        compile(corePdb, sourceDir, outputFile);
    }

    public static void compile(Path corePdb, Path sourceDir, Path outputFile) throws IOException
    {
        ModuleManifest manifest = ModuleManifest.locate(sourceDir);

        System.out.println();
        System.out.println("Pure Compiler Binary Builder From Pure Files (PDB)");
        System.out.println("==================================================");
        System.out.println("  Inputs: " + corePdb);
        System.out.println("          " + sourceDir);
        System.out.println("  Manifest: module='" + manifest.name() + "', deps=" + manifest.dependencies());
        System.out.println("  Output: " + outputFile);

        // Load core.pdb — its identity (name "specification", etc.) comes from
        // the manifest embedded inside the archive.
        PDBModule coreModule = new PDBModule(corePdb, PDBModule.Mode.COMPILATION);
        verifyDependencies(manifest, List.of(coreModule.getName()));

        // Local module for compiler helper files, identity from the source manifest
        LocalModule localModule = new LocalModule(
                manifest.name(), manifest.packagePattern(), manifest.dependencies(), sourceDir);

        MutableList<Module> modules = Lists.mutable.with(coreModule, localModule);
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

        // Collect elements from the local module only, excluding those already in core.pdb
        LinkedHashMap<String, PackageableElement> elementsByPath = new LinkedHashMap<>();
        for (String path : localModule.elementPaths())
        {
            if (!elementsByPath.containsKey(path) && !coreModule.hasElement(path))
            {
                PackageableElement element = localModule.getElement(path);
                if (element != null)
                {
                    elementsByPath.put(path, element);
                }
            }
        }
        List<PackageableElement> elements = new ArrayList<>(elementsByPath.values());
        System.out.println("  Compiled " + elements.size() + " elements");

        // Write compiler.pdb
        Files.createDirectories(outputFile.getParent());
        new CompressedArchiveWriter().write(elements, extensions, localModule, manifest, List.of(), outputFile);
        System.out.println("    Written: " + outputFile + " (" + Files.size(outputFile) + " bytes)");
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
