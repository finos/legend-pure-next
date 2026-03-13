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
import org.finos.legend.pure.m3.module.localModule.LocalModule;
import org.finos.legend.pure.m3.module.bootstrapModule.BootstrapModule;
import org.finos.legend.pure.m3.module.CompilationResult;
import org.finos.legend.pure.m3.module.Module;
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
 * <p>Usage: {@code java PureCompiler <sourceDir> <outputFile.pdb>}</p>
 *
 * <p>Compiles the Pure source files and writes a compressed FlatBuffer
 * archive containing the compiled elements, including all bootstrap M3 types.</p>
 */
public class SpecificationBinaryBuilder
{
    public static void main(String[] args) throws Exception
    {
        if (args.length < 2)
        {
            System.err.println("Usage: PureCompiler <sourceDir> <outputFile.pdb>");
            System.exit(1);
        }

        Path sourceDir = Path.of(args[0]);
        Path outputFile = Path.of(args[1]);

        compile(sourceDir, outputFile);
    }

    /**
     * Compile all .pure files under sourceDir and write a .pdb archive.
     * The archive includes both bootstrap M3 types and locally compiled elements,
     * driven from the module index rather than scanning the package tree.
     */
    public static void compile(Path sourceDir, Path outputFile) throws IOException
    {
        System.out.println("Compiling Pure model from " + sourceDir + "...");

        // --- Compile ---
        LocalModule localModule = new LocalModule("specification", "(meta::pure)(::.*)?", List.of("m3"), sourceDir);
        MutableList<Module> modules = Lists.mutable.with(new BootstrapModule(), localModule);
        MutableList<LanguageExtension> extensions = Lists.mutable.with(new PureLanguageExtension());
        PureModel model = PureModel.withModules(modules).withExtensions(extensions).build();
        CompilationResult result = model.compile();

        if (!result.errors().isEmpty())
        {
            System.err.println("Compilation errors:");
            result.errors().forEach(e -> System.err.println("  " + e.message()));
            throw new RuntimeException("Pure compilation failed with " + result.errors().size() + " error(s)");
        }

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
        System.out.println("Compiled " + elements.size() + " elements");

        // --- Serialize to .pdb ---
        Files.createDirectories(outputFile.getParent());
        new CompressedArchiveWriter().write(elements, extensions, localModule, outputFile);
        System.out.println("Written: " + outputFile + " (" + Files.size(outputFile) + " bytes)");
    }
}
