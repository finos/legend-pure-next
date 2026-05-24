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
import org.eclipse.collections.api.map.MutableMap;
import org.eclipse.collections.impl.factory.Maps;
import org.eclipse.collections.impl.list.mutable.ListAdapter;
import org.finos.legend.pure.m3.LanguageExtension;
import org.finos.legend.pure.m3.PureModel;
import org.finos.legend.pure.m3.module.CompilationError;
import org.finos.legend.pure.m3.module.CompilationResult;
import org.finos.legend.pure.m3.module.CompilationStatistics;
import org.finos.legend.pure.m3.module.ElementStatistics;
import org.finos.legend.pure.m3.module.MetadataAccessExtension;
import org.finos.legend.pure.m3.module.Module;
import org.finos.legend.pure.m3.module.ScopedMetadataAccess;
import org.finos.legend.pure.m3.module.localModule.topLevel.CompilationContext;
import org.finos.legend.pure.m3.module.localModule.topLevel.CompilerExtension;
import org.finos.legend.pure.m3.module.localModule.topLevel.IndexEntry;
import org.finos.legend.pure.m3.module.localModule.topLevel.TopLevelCompiler;
import org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.structural.SourceInformationCompiler;
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
    /** Source folders containing .pure files (for production). */
    private final List<Path> sourceFolders;

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
        this.sourceFolders = null;
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
        this(name, packagePattern, List.of(sourceFolder), dependencies);
    }

    /**
     * Create a module from multiple folders of .pure files.
     */
    public LocalModule(String name, String packagePattern, Iterable<Path> sourceFolders, List<String> dependencies)
    {
        this.name = name;
        this.packagePattern = packagePattern;
        this.dependencies = dependencies;
        this.sources = null;
        this.sourceFolders = new java.util.ArrayList<>();
        if (sourceFolders != null) {
            sourceFolders.forEach(this.sourceFolders::add);
        }
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
     * Filesystem roots this module owns ({@code null} when the module was
     * constructed from in-memory {@link PureContent}s rather than a folder).
     * Exposed for tools that need to re-read or re-compile the module's
     * source — e.g. an IDE backend driving a parallel compile pipeline.
     */
    public List<Path> sourceFolders()
    {
        return sourceFolders == null ? List.of() : List.copyOf(sourceFolders);
    }

    @Override
    public Set<String> sourceFiles()
    {
        Set<String> files = new java.util.LinkedHashSet<>();
        try
        {
            for (PureContent c : collectSources())
            {
                files.add(c.sourceId());
            }
        }
        catch (Exception e)
        {
            // fallback if IO fails
            if (state != null)
            {
                state.elementIndex().forEachValue(entry ->
                {
                    if (entry.sourceId() != null)
                    {
                        files.add(entry.sourceId());
                    }
                });
            }
        }
        return files;
    }

    public String getSourceIdForElement(String elementPath)
    {
        if (state != null && state.elementIndex().containsKey(elementPath))
        {
            return state.elementIndex().get(elementPath).sourceId();
        }
        return null;
    }

    public String getSourceText(String sourceId)
    {
        if (sources != null)
        {
            for (PureContent c : sources)
            {
                if (sourceId.equals(c.sourceId()))
                {
                    return c.content();
                }
            }
        }
        if (sourceFolders != null)
        {
            for (Path folder : sourceFolders)
            {
                Path file = folder.resolve(sourceId);
                if (Files.exists(file))
                {
                    try
                    {
                        return Files.readString(file, StandardCharsets.UTF_8);
                    }
                    catch (IOException e)
                    {
                        // ignore and try next
                    }
                }
            }
        }
        return null;
    }


    public boolean saveSourceText(String sourceId, String content)
    {
        if (sourceFolders != null)
        {
            for (Path folder : sourceFolders)
            {
                Path file = folder.resolve(sourceId);
                if (Files.exists(file))
                {
                    try
                    {
                        Files.writeString(file, content, StandardCharsets.UTF_8);
                        return true;
                    }
                    catch (IOException e)
                    {
                        return false;
                    }
                }
            }
        }
        return false;
    }

    /**
     * Parse source files and run the full compilation pipeline.
     *
     * @return the compilation result with any errors
     */
    public CompilationResult compile()
    {
        long compileStart = System.nanoTime();
        Runtime runtime = Runtime.getRuntime();
        runtime.gc();
        long memBefore = runtime.totalMemory() - runtime.freeMemory();

        // Collect raw sources
        List<PureContent> rawSources = collectSources();

        // Parse all sources (extensions provide custom section parsers)
        List<LanguageExtension> extensions = pureModel.extensions();

        // Parse
        long parseStart = System.nanoTime();
        PureParser parser = PureParser.builder().withExtensions(extensions).build();
        MutableList<PureFile> files = ListAdapter.adapt(rawSources)
                .collect(source -> parser.parse(source.sourceId(), source.content()));
        long parsingDurationNanos = System.nanoTime() - parseStart;

        // Compile
        this.compilationContext = new CompilationContext(pureModel.extensions().collect(CompilerExtension::buildCompilerContextExtension).select(Objects::nonNull));
        this.state = new TopLevelCompiler(pureModel._root(), extensions);
        this.state.compile(this, files, packagePattern, new ScopedMetadataAccess(this, pureModel), compilationContext);
        validateNonDuplicateElements(this.state);

        // Build per-element statistics
        MutableMap<String, ElementStatistics> elementStats = Maps.mutable.empty();
        state.elementTimings().forEach((path, timings) ->
        {
            IndexEntry entry = state.elementIndex().get(path);
            String elementType = entry != null && entry.element() != null
                    ? entry.element().getClass().getSimpleName().replace("Impl", "")
                    : "Unknown";
            elementStats.put(path, new ElementStatistics(path, elementType, timings[0], timings[1], timings[2], (int) timings[3], (int) timings[4]));
        });

        long memAfter = runtime.totalMemory() - runtime.freeMemory();
        long totalDurationNanos = System.nanoTime() - compileStart;

        CompilationStatistics statistics = new CompilationStatistics(
                totalDurationNanos,
                parsingDurationNanos,
                state.firstPassDurationNanos(),
                state.secondPassDurationNanos(),
                state.thirdPassDurationNanos(),
                state.elementIndex().size(),
                rawSources.size(),
                memAfter - memBefore,
                compilationContext.inferenceRollbackCount(),
                compilationContext.candidateEvaluationCount(),
                elementStats,
                compilationContext.rollbackSites());

        List<CompilationError> errors = compilationContext.errors().collect(
                e -> new CompilationError(e.formatMessage(), e.sourceInformation())).toList();

        // Snapshot the reverse reference index built during compile. Convert
        // Eclipse Collections MutableSet → java.util.Set so consumers don't
        // need to depend on EC. The map is per-compile transient state in
        // the CompilationContext; copying it here decouples lifetime.
        java.util.LinkedHashMap<String, java.util.Set<String>> refIndex = new java.util.LinkedHashMap<>();
        compilationContext.referencedBy().forEachKeyValue((target, callers) ->
                refIndex.put(target, new java.util.LinkedHashSet<>(callers.toList())));

        // Derive function-reference edges from the compiled AST. The in-flight
        // recording at the function-/dot-application success sites was prone
        // to multi-candidate trial pollution (phantom edges from candidates
        // that lost specificity, or missing edges if those recordings were
        // rolled back too aggressively). Walking the final {@code _func}
        // slots is structurally exact — the edges match what the PDB
        // actually serialises.
        java.util.List<meta.pure.metamodel.PackageableElement> compiledElements =
                this.state.elementIndex().valuesView()
                        .collect(org.finos.legend.pure.m3.module.localModule.topLevel.IndexEntry::element)
                        .reject(java.util.Objects::isNull)
                        .toList();
        org.finos.legend.pure.m3.module.FunctionRefExtractor.extractInto(compiledElements, (target, caller) ->
                refIndex.computeIfAbsent(target, k -> new java.util.LinkedHashSet<>()).add(caller));

        // Lean-references validator: scan the reverse index for any non-test
        // element that references a test-only element. Uses the per-module
        // resolver so cross-module callers can be classified. Violations are
        // appended to the compile errors and formatted via the same
        // CompilationError pipeline as compiler errors (preserves the
        // {@code (at sourceId:line c col)} suffix from the caller element).
        org.finos.legend.pure.m3.module.ScopedMetadataAccess accessForValidator =
                new org.finos.legend.pure.m3.module.ScopedMetadataAccess(this, pureModel);
        java.util.function.Function<String, meta.pure.metamodel.PackageableElement> resolveForValidator =
                path -> accessForValidator.getElement(path);
        List<CompilationError> leanRefsViolations = org.finos.legend.pure.m3.module.LeanReferencesValidator.validate(refIndex, resolveForValidator)
                .stream()
                .map(e ->
                {
                    org.finos.legend.pure.m3.module.localModule.topLevel.CompilationError wrapped =
                            new org.finos.legend.pure.m3.module.localModule.topLevel.CompilationError(
                                    e.message(), e.sourceInformation());
                    return new CompilationError(wrapped.formatMessage(), wrapped.sourceInformation());
                })
                .toList();
        if (!leanRefsViolations.isEmpty())
        {
            List<CompilationError> combined = new java.util.ArrayList<>(errors);
            combined.addAll(leanRefsViolations);
            errors = combined;
        }

        return new CompilationResult(errors, statistics, refIndex);
    }

    private List<PureContent> collectSources()
    {
        if (sources != null)
        {
            return sources;
        }
        if (sourceFolders == null || sourceFolders.isEmpty())
        {
            return List.of();
        }
        List<PureContent> result = new java.util.ArrayList<>();
        for (Path sourceFolder : sourceFolders)
        {
            if (!Files.exists(sourceFolder)) continue;
            try (Stream<Path> walk = Files.walk(sourceFolder))
            {
                walk.filter(p -> p.toString().endsWith(".pure"))
                        .forEach(p ->
                        {
                            try
                            {
                                String content = Files.readString(p, StandardCharsets.UTF_8);
                                String sourceId = sourceFolder.relativize(p).toString();
                                result.add(new PureContent(content, sourceId));
                            }
                            catch (IOException e)
                            {
                                throw new RuntimeException("Failed to read: " + p, e);
                            }
                        });
            }
            catch (IOException e)
            {
                throw new RuntimeException("Failed to scan folder: " + sourceFolder, e);
            }
        }
        return result;
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
                                ? SourceInformationCompiler.compile(entry.grammarElement()._p_sourceInformation())
                                : null;
                        compilationContext.errors().add(new org.finos.legend.pure.m3.module.localModule.topLevel.CompilationError(
                                "Element '" + path + "' is already defined in a dependency module", si));
                    }
                });
            }
        }
    }
}
