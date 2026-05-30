// Copyright 2026 Goldman Sachs
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

package org.finos.legend.pure.truffle.runtime;

import org.finos.legend.pure.truffle.runtime.helper.TypeCache;
import org.finos.legend.pure.truffle.runtime.helper._PackageableElement;
import org.finos.legend.pure.truffle.types.PureSequence;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A {@link TruffleModule} backed by an in-memory {@code CompilationResult.elements}
 * sequence rather than a {@code .pdb} file.
 *
 * <p>Use this to make freshly-compiled elements addressable through a
 * {@link TruffleModuleRegistry} without the PDB-write-then-load round-trip.
 * Once registered the runtime's AST builder can resolve any user type by
 * path, so cross-module edges (e.g. references to {@code Integer} in core)
 * automatically route to canonical resolver objects.</p>
 *
 * <p>Each element's qualified path is computed once at construction via the
 * package-chain walker in {@link _PackageableElement#path}. No additional
 * canonicalisation pass is needed because {@code compile} already runs
 * with the resolver in scope — references to types in dependency modules
 * (e.g. {@code Integer}) are bound to the resolver's canonical objects
 * during pass 3, and the {@code resolveAndReturnGraph} native fires at the
 * end of compile to swap every remaining TempCompilerPointer for its live
 * target before the elements ever leave compile-pure.</p>
 */
public final class TruffleInMemoryModule implements TruffleModule
{
    private final String name;
    private final List<String> dependencies;
    private final Map<String, Object> byPath;
    private final IdentityHashMap<Object, String> pathByElement;
    private final TruffleTypeCache typeCache = new TypeCache();

    public TruffleInMemoryModule(String name, List<String> dependencies, PureSequence elements,
                                 TruffleMetadataAccess resolver)
    {
        this.name = name;
        this.dependencies = List.copyOf(dependencies);
        this.byPath = new HashMap<>();
        this.pathByElement = new IdentityHashMap<>();
        for (int i = 0, n = elements.size(); i < n; i++)
        {
            Object el = elements.getBoxed(i);
            if (el == null) { continue; }
            String path = _PackageableElement.path(el, resolver);
            if (path == null || path.isEmpty()) { continue; }
            // compile can emit multiple revisions of the same path
            // across passes; the writer dedups by name and keeps the last.
            // Mirror that: later entries win.
            byPath.put(path, el);
            pathByElement.put(el, path);
        }
        // Pointer resolution happens inside meta::pure::compiler::compile
        // (via the resolveAndReturnGraph native) BEFORE the elements reach
        // this constructor. No post-compile Java resolution is needed.
        //
        // We DO NOT drop synthesised `Package` PDOs whose path the resolver
        // already owns. The registry permits Package path collisions (see
        // TruffleModuleRegistry.register) and folds children across contributors
        // via resolveAndMerge / foldChildrenInto — exactly what we need so
        // this module's namespace contributions (test sub-packages, new user
        // classes, etc.) appear in the canonical Package.children tree.
        // Dropping them here used to bypass that mechanism and made
        // Package.children-walking discovery (notably runTests/runPCTTests)
        // silently see zero results. Symmetric teardown lives in
        // TruffleModuleRegistry.unregister: when this module is dropped, its
        // contributed children are identity-removed from the surviving anchor.
    }

    @Override
    public String name()
    {
        return name;
    }

    @Override
    public List<String> dependencies()
    {
        return dependencies;
    }

    @Override
    public Object getElement(String path)
    {
        return byPath.get(path);
    }

    @Override
    public boolean hasElement(String path)
    {
        return byPath.containsKey(path);
    }

    @Override
    public Set<String> elementPaths()
    {
        return new LinkedHashSet<>(byPath.keySet());
    }

    @Override
    public String pathOf(Object element)
    {
        return pathByElement.get(element);
    }

    @Override
    public TruffleTypeCache typeCache()
    {
        return typeCache;
    }
}
