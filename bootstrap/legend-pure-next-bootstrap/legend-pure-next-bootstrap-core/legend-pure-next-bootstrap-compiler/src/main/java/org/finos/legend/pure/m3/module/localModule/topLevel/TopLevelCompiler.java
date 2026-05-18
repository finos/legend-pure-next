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

package org.finos.legend.pure.m3.module.localModule.topLevel;

import meta.pure.metamodel.Package;
import meta.pure.metamodel.PackageImpl;
import meta.pure.metamodel.PackageableElement;
import meta.pure.metamodel.SourceInformation;
import meta.pure.metamodel.type.Type;
import meta.pure.metamodel.type.generics.GenericType;
import meta.pure.metamodel.type.generics.GenericTypeValue;
import meta.pure.protocol.PureFile;
import meta.pure.protocol.Section;
import org.eclipse.collections.api.factory.Lists;
import org.eclipse.collections.api.factory.Maps;
import org.eclipse.collections.api.list.MutableList;
import org.eclipse.collections.api.map.MutableMap;
import org.finos.legend.pure.m3.PureModel;
import org.finos.legend.pure.m3.module.MetadataAccess;
import org.finos.legend.pure.m3.module.localModule.LocalModule;
import org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.PureLanguageCompilerExtension;
import org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._GenericType;
import org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.structural.SourceInformationCompiler;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Holds the compilation state for locally compiled files and orchestrates
 * the multi-pass compilation pipeline.
 *
 * <p>
 * This class is scoped to the local source files only.
 * Cross-module concerns (element resolution across modules, merging
 * function indices from pre-compiled modules) are handled by
 * {@link PureModel}.
 * </p>
 *
 * <p>
 * The compiler uses a three-pass strategy:
 * <ol>
 *   <li><b>First pass</b> – creates every element with its name and registers
 *       it in the index.  No cross-references are resolved.</li>
 *   <li><b>Second pass</b> – resolves cross-references such as properties
 *       and generalizations, now that every element is reachable by path.</li>
 *   <li><b>Third pass</b> – resolves function references now that the
 *       function index is available.</li>
 * </ol>
 * </p>
 */
public class TopLevelCompiler
{
    public static final MutableList<String> DEFAULT_IMPORTS = Lists.mutable.with(
            "meta::pure::metamodel::type::primitives",
            "meta::pure::metamodel",
            "meta::pure::metamodel::function::property",
            "meta::pure::metamodel::type",
            "meta::pure::metamodel::type::generics",
            "meta::pure::metamodel::function",
            "meta::pure::metamodel::multiplicity",
            "meta::pure::metamodel::relation",
            "meta::pure::metamodel::valuespecification",
            "meta::pure::functions::math",
            "meta::pure::functions::lang",
            "meta::pure::functions::string",
            "meta::pure::functions::collection",
            "meta::pure::functions::boolean",
            "meta::pure::functions::relation",
            "meta::pure::functions::multiplicity",
            "meta::pure::functions::asserts",
            "meta::pure::functions::meta",
            "meta::pure::functions::io",
            "meta::pure::profiles"
    );

    private final Package root;
    private final MutableMap<String, IndexEntry> elementIndex;
    private final PureLanguageCompilerExtension pureLanguageCompilerExtension;
    private final List<CompilerExtension> extensions;

    /** Per-element timing: elementPath → [firstPassNanos, secondPassNanos, thirdPassNanos, rollbacks, candidateEvals] */
    private final Map<String, long[]> elementTimings = new LinkedHashMap<>();
    private long firstPassDurationNanos;
    private long secondPassDurationNanos;
    private long thirdPassDurationNanos;

    public TopLevelCompiler(Package root, List<? extends CompilerExtension> extensions)
    {
        this.root = root;
        this.elementIndex = Maps.mutable.empty();
        this.pureLanguageCompilerExtension = new PureLanguageCompilerExtension();
        MutableList<CompilerExtension> allExtensions = Lists.mutable.with(this.pureLanguageCompilerExtension);
        allExtensions.addAllIterable(extensions);
        this.extensions = allExtensions;
    }

    // -----------------------------------------------------------------------
    // Compile pipeline
    // -----------------------------------------------------------------------

    /**
     * Run the full compilation pipeline on the given files.
     *
     * @param files   the parsed PureFiles to compile
     * @param model   the PureModel for cross-module resolution
     * @param context the compilation context for error collection
     * @return true if compilation completed all passes, false if stopped after second pass due to errors
     */
    public boolean compile(LocalModule localModule, MutableList<PureFile> files, String packagePattern, MetadataAccess model, CompilationContext context)
    {
        long t0 = System.nanoTime();
        firstPass(files, model, context);
        this.firstPassDurationNanos = System.nanoTime() - t0;

        validatePathForModulePattern(packagePattern, context);
        if (context.errors().notEmpty())
        {
            return false;
        }

        t0 = System.nanoTime();
        secondPass(model, context);
        this.secondPassDurationNanos = System.nanoTime() - t0;

        if (context.errors().notEmpty())
        {
            return false;
        }

        updatePackageTree(model);

        t0 = System.nanoTime();
        thirdPass(localModule, model, context);
        this.thirdPassDurationNanos = System.nanoTime() - t0;

        return context.errors().isEmpty();
    }

    private void validatePathForModulePattern(String packagePattern, CompilationContext context)
    {
        if (packagePattern != null && !packagePattern.equals("*"))
        {
            Pattern regex = Pattern.compile(packagePattern);
            elementIndex.forEachKeyValue((path, entry) ->
            {
                if (!(entry.element() instanceof Package) && !regex.matcher(path).matches())
                {
                    SourceInformation si = entry.grammarElement() != null
                            ? SourceInformationCompiler.compile(entry.grammarElement()._p_sourceInformation())
                            : null;
                    context.errors().add(new CompilationError("Element '" + path + "' does not match module package pattern '" + packagePattern + "'", si));
                }
            });
        }
    }

    // -----------------------------------------------------------------------
    // Accessors
    // -----------------------------------------------------------------------

    public Package root()
    {
        return root;
    }

    public MutableMap<String, IndexEntry> elementIndex()
    {
        return elementIndex;
    }

    public PureLanguageCompilerExtension pureLanguageCompilerExtension()
    {
        return pureLanguageCompilerExtension;
    }

    public PackageableElement getElement(String path)
    {
        IndexEntry entry = elementIndex.get(path);
        return entry != null ? entry.element() : null;
    }

    public boolean hasElement(String path)
    {
        return elementIndex.containsKey(path);
    }

    public Set<String> elementPaths()
    {
        return elementIndex.keySet();
    }

    /**
     * Per-element timing data collected during compilation.
     * Key: element path, Value: [firstPassNanos, secondPassNanos, thirdPassNanos]
     */
    public Map<String, long[]> elementTimings()
    {
        return elementTimings;
    }

    public long firstPassDurationNanos()
    {
        return firstPassDurationNanos;
    }

    public long secondPassDurationNanos()
    {
        return secondPassDurationNanos;
    }

    public long thirdPassDurationNanos()
    {
        return thirdPassDurationNanos;
    }

    // -----------------------------------------------------------------------
    // First pass
    // -----------------------------------------------------------------------

    private void firstPass(MutableList<PureFile> files, MetadataAccess model, CompilationContext context)
    {
        files.forEach(file ->
        {
            String fileSourceId = file._sourceId();
            // Mirror compiler-pure compiler.pure phase-2 wiring: every pass
            // sets context.sourceId from the file currently being compiled so
            // SourceInformationCompiler.compile(grammar, context.getSourceId(), model)
            // falls back to the file when the grammar SI has no sourceId.
            context.setSourceId(fileSourceId);
            file._sections().forEach(section ->
                    section._elements().forEach(grammarElement ->
                    {
                        String name = grammarElement._name();
                        String packagePath = grammarElement._package() != null
                                ? grammarElement._package()._value()
                                : null;
                        String fullPath = packagePath != null
                                ? packagePath + "::" + name
                                : name;
                        if (elementIndex.containsKey(fullPath))
                        {
                            meta.pure.protocol.grammar.SourceInformation si = grammarElement._p_sourceInformation();
                            throw new RuntimeException("The element '" + fullPath + "' already exists at: " + si._sourceId() + ":" + si._startLine() + "c" + si._startColumn());
                        }
                        long t0 = System.nanoTime();
                        PackageableElement element = firstPassElement(grammarElement, model, context);
                        long elapsed = System.nanoTime() - t0;
                        elementTimings.computeIfAbsent(fullPath, k -> new long[5])[0] = elapsed;
                        elementIndex.put(fullPath, new IndexEntry(element, grammarElement, section, fileSourceId));
                    }));
        });
    }

    private PackageableElement firstPassElement(
            meta.pure.protocol.grammar.PackageableElement grammar, MetadataAccess model, CompilationContext context)
    {
        for (CompilerExtension ext : this.extensions)
        {
            PackageableElement result = ext.firstPass(grammar, model, context);
            if (result != null)
            {
                return result;
            }
        }
        throw new UnsupportedOperationException("Unsupported element type: " + grammar.getClass().getName());
    }

    // -----------------------------------------------------------------------
    // Second pass
    // -----------------------------------------------------------------------

    private void secondPass(MetadataAccess model, CompilationContext context)
    {
        elementIndex.forEachKeyValue((fullPath, entry) ->
        {
            if (entry.grammarElement() != null)
            {
                context.setSourceId(entry.sourceId());
                if (entry.section() != null)
                {
                    context.setImports(resolveImports(entry.section()));
                }
                long t0 = System.nanoTime();
                PackageableElement updated = secondPassEntry(entry, model, context);
                long elapsed = System.nanoTime() - t0;
                elementTimings.computeIfAbsent(fullPath, k -> new long[5])[1] = elapsed;
                context.flushCurrentErrors();
                elementIndex.put(fullPath, new IndexEntry(updated, entry.grammarElement(), entry.section(), entry.sourceId()));
            }
        });
    }

    private PackageableElement secondPassEntry(IndexEntry entry, MetadataAccess model, CompilationContext context)
    {
        for (CompilerExtension ext : this.extensions)
        {
            PackageableElement result = ext.secondPass(entry, model, context);
            if (result != null)
            {
                return result;
            }
        }
        throw new UnsupportedOperationException("Unsupported element type: " + entry.grammarElement().getClass().getName());
    }

    // -----------------------------------------------------------------------
    // Third pass
    // -----------------------------------------------------------------------

    private void thirdPass(LocalModule localModule, MetadataAccess model, CompilationContext context)
    {
        for (CompilerExtension ext : this.extensions)
        {
            ext.preThirdPass(localModule, model);
        }
        // Snapshot to avoid ConcurrentModificationException when swapping
        var snapshot = Lists.mutable.withAll(elementIndex.keyValuesView());
        snapshot.forEach(pair ->
        {
            String fullPath = pair.getOne();
            IndexEntry entry = pair.getTwo();
            if (entry.grammarElement() == null)
            {
                return;
            }
            context.setSourceId(entry.sourceId());
            if (entry.section() != null)
            {
                context.setImports(resolveImports(entry.section()));
            }
            int rollbacksBefore = context.inferenceRollbackCount();
            int candidatesBefore = context.candidateEvaluationCount();
            long t0 = System.nanoTime();
            PackageableElement updated = thirdPassEntry(entry, model, context);
            long elapsed = System.nanoTime() - t0;
            long[] timings = elementTimings.computeIfAbsent(fullPath, k -> new long[5]);
            timings[2] = elapsed;
            timings[3] = context.inferenceRollbackCount() - rollbacksBefore;
            timings[4] = context.candidateEvaluationCount() - candidatesBefore;
            elementIndex.put(fullPath, new IndexEntry(updated, entry.grammarElement(), entry.section(), entry.sourceId()));
            context.flushCurrentErrors();
        });
    }

    private PackageableElement thirdPassEntry(IndexEntry entry, MetadataAccess model, CompilationContext context)
    {
        for (CompilerExtension ext : this.extensions)
        {
            PackageableElement result = ext.thirdPass(entry, model, context);
            if (result != null)
            {
                return result;
            }
        }
        throw new UnsupportedOperationException("Unsupported element type: " + entry.grammarElement().getClass().getName());
    }

    // -----------------------------------------------------------------------
    // Package tree (local only)
    // -----------------------------------------------------------------------

    private void updatePackageTree(MetadataAccess model)
    {
        // Snapshot entries — getOrCreatePackage modifies elementIndex
        var snapshot = Lists.mutable.withAll(elementIndex.keyValuesView());
        snapshot.forEach(pair ->
        {
            String fullPath = pair.getOne();
            IndexEntry entry = pair.getTwo();
            if (entry.grammarElement() != null)
            {
                String packagePath = entry.grammarElement()._package() != null
                        ? entry.grammarElement()._package()._value()
                        : null;
                // Anchor sub-package classifier at canonical GenericType_Package (UDPGT) so the
                // chain matches Pure's `^Package(...)` (platform fix in MetaNatives.new substitutes canonical).
                meta.pure.metamodel.type.generics.GenericTypeValue packageCanonical =
                        (meta.pure.metamodel.type.generics.GenericTypeValue) model.getElement(
                                "meta::pure::metamodel::type::generics::optimization::GenericType_Package");
                Package parent = packagePath != null
                        ? getOrCreatePackage(root, packagePath,
                                packageCanonical != null
                                        ? packageCanonical
                                        : _GenericType.buildUserDefinedGenericType((Type)model.getElement("meta::pure::metamodel::Package"), model),
                                model)
                        : root;
                entry.element()._package(parent);
                parent._children().add(entry.element());
            }
        });
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    public static MutableList<String> resolveImports(Section section)
    {
        MutableList<String> allImports = Lists.mutable.withAll(DEFAULT_IMPORTS);
        allImports.addAllIterable(section._imports().collect(imp ->
                imp.endsWith("::*") ? imp.substring(0, imp.length() - 3) : imp));
        return allImports;
    }

    private Package getOrCreatePackage(Package root, String packagePath, GenericTypeValue packageGT, MetadataAccess model)
    {
        Package current = root;
        StringBuilder currentPath = new StringBuilder();
        for (String part : packagePath.split("::"))
        {
            if (!currentPath.isEmpty())
            {
                currentPath.append("::");
            }
            currentPath.append(part);

            Package existing = (Package) current._children()
                    .detect(c -> c instanceof Package && part.equals(c._name()));
            if (existing == null)
            {
                PackageImpl newPkg = new PackageImpl(model)
                                            ._name(part)
                                            ._package(current)
                                            ._classifierGenericType(packageGT);
                current._children().add(newPkg);
                elementIndex.put(currentPath.toString(), new IndexEntry(newPkg, null, null, null));
                existing = newPkg;
            }
            current = existing;
        }
        return current;
    }
}
