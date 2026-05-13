// Copyright 2026 Goldman Sachs
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0

package org.finos.legend.pure.ide.truffle;

import org.finos.legend.pure.truffle.runtime.TruffleModule;
import org.finos.legend.pure.truffle.runtime.TruffleTypeCache;
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
 * <p>Pure-IDE Run flow uses this to make user-compiled elements addressable
 * through the resolver immediately after a {@code compileDir} call, without
 * the PDB-write-then-load roundtrip. Once registered the runtime's AST
 * builder can resolve {@code welcome::go} (and any user types) by path, so
 * cross-module edges (e.g. {@code Integer} → {@code core}) automatically
 * route to canonical resolver objects.</p>
 *
 * <p>Each element's qualified path is computed once at construction via the
 * package-chain walker in {@link _PackageableElement#path}. No additional
 * canonicalisation pass is needed because {@code compileDir} already runs
 * with the resolver in scope — references to types in dependency modules
 * (e.g. {@code Integer}) are bound to the resolver's canonical objects
 * during pass 3.</p>
 */
public final class TruffleInMemoryModule implements TruffleModule
{
    private final String name;
    private final List<String> dependencies;
    private final Map<String, Object> byPath;
    private final IdentityHashMap<Object, String> pathByElement;
    private final TruffleTypeCache typeCache = new TypeCache();

    public TruffleInMemoryModule(String name, List<String> dependencies, PureSequence elements)
    {
        this.name = name;
        this.dependencies = List.copyOf(dependencies);
        this.byPath = new HashMap<>();
        this.pathByElement = new IdentityHashMap<>();
        for (int i = 0, n = elements.size(); i < n; i++)
        {
            Object el = elements.getBoxed(i);
            if (el == null) { continue; }
            String path = _PackageableElement.path(el);
            if (path == null || path.isEmpty()) { continue; }
            // compileDir can emit multiple revisions of the same path
            // across passes; the writer dedups by name and keeps the last.
            // Mirror that: later entries win.
            byPath.put(path, el);
            pathByElement.put(el, path);
        }
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
