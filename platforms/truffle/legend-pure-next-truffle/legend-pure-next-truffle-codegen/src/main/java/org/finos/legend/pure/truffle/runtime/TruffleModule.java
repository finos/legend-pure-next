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

package org.finos.legend.pure.truffle.runtime;

import java.util.List;

/**
 * A named, dependency-aware unit of metadata. One module owns some set of
 * paths (typically the elements of a single {@code .pdb} file or an
 * in-memory equivalent) and may depend on other modules whose elements it
 * references.
 *
 * <p>Modules are the unit of lifecycle: a {@link TruffleModuleRegistry} can
 * unregister a module (e.g. on recompile) and cascade-invalidate its
 * dependents without touching unrelated state. Per-module caches —
 * compiled function bodies, classifier-generic-type singletons, and (in
 * the future) DynamicObject shapes — live keyed by the owning module so
 * a single recompile can wipe just the affected slice.</p>
 */
public interface TruffleModule extends TruffleMetadataAccess
{
    /** Stable identifier for this module (e.g. {@code "core"}, {@code "compiler"}). */
    String name();

    /**
     * Names of modules this one references. Resolution looks here when an
     * element isn't found locally; lifecycle uses this to compute which
     * dependents to invalidate when a dependency module is unregistered.
     */
    List<String> dependencies();
}
