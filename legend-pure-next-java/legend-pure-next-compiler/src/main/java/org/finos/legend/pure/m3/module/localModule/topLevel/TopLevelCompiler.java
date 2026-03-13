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
import org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.structural.SourceInformationCompiler;

import java.util.List;
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
        firstPass(files);

        validatePathForModulePattern(packagePattern, context);
        if (context.errors().notEmpty())
        {
            return false;
        }

        secondPass(model, context);

        if (context.errors().notEmpty())
        {
            return false;
        }

        updatePackageTree();
        thirdPass(localModule, model, context);
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
                            ? SourceInformationCompiler.compile(entry.grammarElement()._sourceInformation())
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

    // -----------------------------------------------------------------------
    // First pass
    // -----------------------------------------------------------------------

    private void firstPass(MutableList<PureFile> files)
    {
        files.forEach(file ->
        {
            String fileSourceId = file._sourceId();
            file._sections().forEach(section ->
                    section._elements().forEach(grammarElement ->
                    {
                        String name = grammarElement._name();
                        String packagePath = grammarElement._package() != null
                                ? grammarElement._package()._pointerValue()
                                : null;
                        String fullPath = packagePath != null
                                ? packagePath + "::" + name
                                : name;

                        PackageableElement element = firstPassElement(grammarElement);
                        elementIndex.put(fullPath, new IndexEntry(element, grammarElement, section, fileSourceId));
                    }));
        });
    }

    private PackageableElement firstPassElement(
            meta.pure.protocol.grammar.PackageableElement grammar)
    {
        for (CompilerExtension ext : this.extensions)
        {
            PackageableElement result = ext.firstPass(grammar);
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
                PackageableElement updated = secondPassEntry(entry, model, context);
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
        this.elementIndex.forEachValue(entry ->
        {
            if (entry.grammarElement() == null)
            {
                return;
            }
            context.setSourceId(entry.sourceId());
            if (entry.section() != null)
            {
                context.setImports(resolveImports(entry.section()));
            }
            thirdPassEntry(entry, model, context);
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

    private void updatePackageTree()
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
                        ? entry.grammarElement()._package()._pointerValue()
                        : null;
                Package parent = packagePath != null
                        ? getOrCreatePackage(root, packagePath)
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

    private Package getOrCreatePackage(Package root, String packagePath)
    {
        Package current = root;
        StringBuilder currentPath = new StringBuilder();
        for (String part : packagePath.split("::"))
        {
            if (currentPath.length() > 0)
            {
                currentPath.append("::");
            }
            currentPath.append(part);

            String partName = part;
            Package existing = (Package) current._children()
                    .detect(c -> c instanceof Package && partName.equals(c._name()));
            if (existing == null)
            {
                PackageImpl newPkg = new PackageImpl()._name(part)._package(current);
                current._children().add(newPkg);
                elementIndex.put(currentPath.toString(), new IndexEntry(newPkg, null, null, null));
                existing = newPkg;
            }
            current = existing;
        }
        return current;
    }
}
