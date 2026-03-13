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

package org.finos.legend.pure.m3.module.localModule;

import meta.pure.metamodel.Package;
import meta.pure.metamodel.PackageableElement;
import meta.pure.metamodel.SourceInformation;
import meta.pure.protocol.PureFile;
import org.eclipse.collections.api.list.MutableList;
import org.eclipse.collections.impl.list.mutable.ListAdapter;
import org.finos.legend.pure.m3.LanguageExtension;
import org.finos.legend.pure.m3.PureModel;
import org.finos.legend.pure.m3.module.CompilationError;
import org.finos.legend.pure.m3.module.CompilationResult;
import org.finos.legend.pure.m3.module.MetadataAccessExtension;
import org.finos.legend.pure.m3.module.Module;
import org.finos.legend.pure.m3.module.ScopedMetadataAccess;
import org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.structural.SourceInformationCompiler;
import org.finos.legend.pure.m3.module.localModule.topLevel.CompilationContext;
import org.finos.legend.pure.m3.module.localModule.topLevel.CompilerExtension;
import org.finos.legend.pure.m3.module.localModule.topLevel.TopLevelCompiler;
import org.finos.legend.pure.next.parser.PureParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;

/**
 * A module representing local source files being compiled.
 *
 * <p>Accepts either a folder {@link Path} (production) or a list of
 * {@link PureContent} (tests). Parsing is handled internally during
 * {@link #compile()}.</p>
 */
public class LocalModule implements Module
{
    private final String name;
    private final String packagePattern;
    private final List<String> dependencies;

    /** Sources provided as content strings (for tests). */
    private final List<PureContent> sources;
    /** Source folder containing .pure files (for production). */
    private final Path sourceFolder;

    private PureModel pureModel;
    private TopLevelCompiler state;
    private CompilationContext compilationContext;
    private MutableList<MetadataAccessExtension> metadataAccessExtensions;

    /**
     * Create a module from a list of source content strings.
     */
    public LocalModule(String name, String packagePattern, List<String> dependencies, List<PureContent> sources)
    {
        this.name = name;
        this.packagePattern = packagePattern;
        this.dependencies = dependencies;
        this.sources = sources;
        this.sourceFolder = null;
    }

    @Override
    public <T extends MetadataAccessExtension> MutableList<T> getMetadataAccessExtension(Class<T> clz)
    {
        return this.metadataAccessExtensions.selectInstancesOf(clz);
    }

    /**
     * Create a module from a folder of .pure files.
     */
    public LocalModule(String name, String packagePattern, List<String> dependencies, Path sourceFolder)
    {
        this.name = name;
        this.packagePattern = packagePattern;
        this.dependencies = dependencies;
        this.sources = null;
        this.sourceFolder = sourceFolder;
    }

    @Override
    public void setPureModel(PureModel model)
    {
        this.pureModel = model;
        this.metadataAccessExtensions = model.extensions().collect(e -> e.buildMetadataExtensionForModule(this)).select(Objects::nonNull);
    }

    @Override
    public String getName()
    {
        return name;
    }

    @Override
    public List<String> getDependencies()
    {
        return dependencies;
    }

    @Override
    public String getPackagePattern()
    {
        return packagePattern;
    }

    @Override
    public PackageableElement getElement(String path)
    {
        return state != null ? state.getElement(path) : null;
    }

    @Override
    public boolean hasElement(String path)
    {
        return state != null && state.hasElement(path);
    }

    @Override
    public Set<String> elementPaths()
    {
        return state != null ? state.elementPaths() : Set.of();
    }

    /**
     * Parse source files and run the full compilation pipeline.
     *
     * @return the compilation result with any errors
     */
    public CompilationResult compile()
    {
        // Collect raw sources
        List<PureContent> rawSources = collectSources();

        // Parse all sources (extensions provide custom section parsers)
        List<LanguageExtension> extensions = pureModel.extensions();

        // Parse
        PureParser parser = PureParser.builder().withExtensions(extensions).build();
        MutableList<PureFile> files = ListAdapter.adapt(rawSources)
                .collect(source -> parser.parse(source.sourceId(), source.content()));

        // Compile
        this.compilationContext = new CompilationContext(pureModel.extensions().collect(CompilerExtension::buildCompilerContextExtension).select(Objects::nonNull));
        this.state = new TopLevelCompiler(pureModel._root(), extensions);
        this.state.compile(this, files, packagePattern, new ScopedMetadataAccess(this, pureModel), compilationContext);
        validateNonDuplicateElements(this.state);

        List<CompilationError> errors = compilationContext.errors().stream()
                .map(e -> new CompilationError(e.formatMessage(), e.sourceInformation()))
                .toList();
        return new CompilationResult(errors);
    }

    private List<PureContent> collectSources()
    {
        if (sources != null)
        {
            return sources;
        }
        if (sourceFolder == null)
        {
            return List.of();
        }
        try (Stream<Path> walk = Files.walk(sourceFolder))
        {
            return walk.filter(p -> p.toString().endsWith(".pure"))
                    .map(p ->
                    {
                        try
                        {
                            String content = Files.readString(p, StandardCharsets.UTF_8);
                            String sourceId = sourceFolder.relativize(p).toString();
                            return new PureContent(content, sourceId);
                        }
                        catch (IOException e)
                        {
                            throw new RuntimeException("Failed to read: " + p, e);
                        }
                    })
                    .toList();
        }
        catch (IOException e)
        {
            throw new RuntimeException("Failed to scan folder: " + sourceFolder, e);
        }
    }

    private void validateNonDuplicateElements(TopLevelCompiler cs)
    {
        for (String depName : dependencies)
        {
            Module dep = pureModel.getModule(depName);
            if (dep != null)
            {
                cs.elementIndex().forEachKeyValue((path, entry) ->
                {
                    if (!(entry.element() instanceof Package) && dep.hasElement(path))
                    {
                        SourceInformation si = entry.grammarElement() != null
                                ? SourceInformationCompiler.compile(entry.grammarElement()._sourceInformation())
                                : null;
                        compilationContext.errors().add(new org.finos.legend.pure.m3.module.localModule.topLevel.CompilationError(
                                "Element '" + path + "' is already defined in a dependency module", si));
                    }
                });
            }
        }
    }
}
