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

package org.finos.legend.pure.m3.module;

import meta.pure.metamodel.PackageableElement;
import meta.pure.metamodel.type.Type;
import org.eclipse.collections.api.list.MutableList;
import org.finos.legend.pure.m3.module.localModule.topLevel.CompilationContext;

import java.util.Map;
import java.util.Set;

/**
 * Transparent {@link MetadataAccess} wrapper that records every successful
 * cross-element lookup into the {@link CompilationContext}'s reverse
 * reference index. No classification happens here — the wrapper exists
 * only to build data; a last-stage validator reads the index after all
 * passes complete.
 *
 * <p>The recording hook is the same shape compiler-pure needs in its own
 * lookup function: <em>after resolving a path, append (callerPath,
 * targetPath) to a side-effect index</em>. Keeping the hook minimal
 * (record-only, no business logic) makes the cross-port port-over
 * trivial.</p>
 *
 * <p>Skips noisy targets that aren't meaningful in the reverse index:
 * built-in primitives, top type {@code Any}, multiplicity instances, the
 * {@code SourceInformation} struct, and UDPGT optimization anchors. User-
 * defined primitives (e.g. {@code protocol::Varchar}) are kept because
 * their paths don't fall under the skip prefixes.</p>
 */
public final class RecordingMetadataAccess implements MetadataAccess
{
    /**
     * Path prefixes whose lookups are excluded from the reverse index.
     * Everything under {@code meta::pure::metamodel::} is m3 (primitives,
     * multiplicities, metaclasses like Class/Function/Property, the Any
     * top type, GenericType optimization anchors, SourceInformation, etc.)
     * — referenced by every compiled element through its
     * {@code classifierGenericType}, generalizations, properties, etc.
     * Recording these creates a flood of edges with no signal: nearly
     * every element references nearly every metaclass.
     */
    private static final String M3_PREFIX = "meta::pure::metamodel::";

    private static boolean isNoise(String path)
    {
        return path.startsWith(M3_PREFIX);
    }

    private final MetadataAccess inner;
    private final CompilationContext context;

    public RecordingMetadataAccess(MetadataAccess inner, CompilationContext context)
    {
        this.inner = inner;
        this.context = context;
    }

    @Override
    public PackageableElement getElement(String path)
    {
        PackageableElement result = inner.getElement(path);
        if (result != null && path != null)
        {
            // Remember the lookup path against the resolved element so
            // sub-element recording (stereotypes, tags) can find the full
            // path even before {@code updatePackageTree} runs in pass 3.
            // Recording the path unconditionally — even for noise targets
            // — because the side map is a navigational helper, not the
            // reverse index itself.
            context.rememberResolvedPath(result, path);
            if (!isNoise(path))
            {
                context.recordReference(path);
            }
        }
        return result;
    }

    @Override
    public boolean hasElement(String path)
    {
        return inner.hasElement(path);
    }

    @Override
    public Set<String> elementPaths()
    {
        return inner.elementPaths();
    }

    @Override
    public <T extends MetadataAccessExtension> MutableList<T> getMetadataAccessExtension(Class<T> clz)
    {
        return inner.getMetadataAccessExtension(clz);
    }

    @Override
    public Type any()
    {
        return inner.any();
    }

    @Override
    public Type nil()
    {
        return inner.nil();
    }

    @Override
    public Map<Type, MutableList<Type>> linearizationCache()
    {
        return inner.linearizationCache();
    }

    @Override
    public Map<Type, java.util.List<String>> equalityKeyPropertiesCache()
    {
        return inner.equalityKeyPropertiesCache();
    }

    @Override
    public Map<PackageableElement, String> elementPathCache()
    {
        return inner.elementPathCache();
    }
}
