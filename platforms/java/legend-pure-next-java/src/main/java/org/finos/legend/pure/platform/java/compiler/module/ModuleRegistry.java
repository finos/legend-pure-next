// Copyright 2026 Goldman Sachs
// ©2026 JP Morgan Chase & Co. All rights reserved.
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

package org.finos.legend.pure.platform.java.compiler.module;

import org.finos.legend.pure.platform.java.compiler.module.pdbModule.PdbRuntime;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The modules a runtime holds, in registration order, and the one place to ask for an
 * element across all of them.
 *
 * <p>Registration order is lookup order. {@link #validate} checks every declared
 * dependency was registered, which catches the common mistake of loading a module
 * without the one it was compiled against — the failure would otherwise be an
 * "Element not found" much later, naming a path rather than the missing module.</p>
 *
 * <p>The registry answers {@link #getElement} only once a
 * {@link org.finos.legend.pure.platform.java.runtime.PureRuntime} has built it: reading a
 * PDB needs the decoder, and the decoder is shared across every archive because elements
 * refer across them. Before that it holds module handles and nothing more.</p>
 */
public final class ModuleRegistry
{
    private final List<Module> modules = new ArrayList<>();
    private PdbRuntime runtime;

    public void register(Module module)
    {
        unregister(module.name());
        modules.add(module);
    }

    /** Remove the module named {@code name}, if it is registered. */
    public void unregister(String name)
    {
        modules.removeIf(m -> m.name().equals(name));
    }

    public List<Module> modules()
    {
        return List.copyOf(modules);
    }

    /** The module named {@code name}, or null. */
    public Module module(String name)
    {
        return modules.stream().filter(m -> m.name().equals(name)).findFirst().orElse(null);
    }

    /** Every declared dependency must be registered; says which module wanted what. */
    public void validate()
    {
        Set<String> known = new LinkedHashSet<>();
        modules.forEach(m -> known.add(m.name()));
        List<String> missing = new ArrayList<>();
        for (Module module : modules)
        {
            for (String dependency : module.dependencies())
            {
                if (!known.contains(dependency))
                {
                    missing.add(module.name() + " -> " + dependency);
                }
            }
        }
        if (!missing.isEmpty())
        {
            throw new IllegalStateException("Modules depend on modules that are not registered: " + missing);
        }
    }

    /** Called by PureRuntime once it has opened the registered archives. */
    public void attach(PdbRuntime opened)
    {
        this.runtime = opened;
    }

    /** The decoder over the registered archives, once attached. */
    public PdbRuntime runtime()
    {
        if (runtime == null)
        {
            throw new IllegalStateException("The registry is not attached to a runtime yet: build a PureRuntime withRegistry(this) first");
        }
        return runtime;
    }

    /**
     * The element at {@code path} — from an in-memory module's last compile if one
     * defined it, otherwise from the archives. Throws when nothing has it, as
     * {@code pathToElement} does.
     */
    public Object getElement(String path)
    {
        return runtime().element(path);
    }
}
