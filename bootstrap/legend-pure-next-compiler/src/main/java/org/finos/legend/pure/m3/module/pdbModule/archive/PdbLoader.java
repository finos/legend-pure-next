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

package org.finos.legend.pure.m3.module.pdbModule.archive;

import meta.pure.metamodel.PackageableElement;
import org.finos.legend.pure.m3.module.MetadataAccess;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Loads pre-compiled Pure elements from a .pdb archive and returns
 * them as read-only FlatBuffer-backed wrapper objects.
 *
 * <p>Elements are deserialized lazily and cached — the first call to
 * {@link #resolve(String)} decompresses and wraps the FlatBuffer blob;
 * subsequent calls return the cached wrapper.</p>
 *
 * <p>Cross-references within wrappers are resolved through a
 * {@link MetadataAccess} (typically {@code PureModel}), which is set
 * via {@link #setResolver(MetadataAccess)} before any element access.
 * This ensures wrappers navigate the full graph via PureModel's
 * multi-module resolution.</p>
 *
 * <p>All element type handling is delegated to {@link PDBExtension}
 * implementations via {@link PDBExtension#deserialize}.</p>
 */
public class PdbLoader
{
    private final CompressedArchiveReader archive;
    private final Map<String, PackageableElement> cache;
    private MetadataAccess wrapperResolver;
    private List<? extends PDBExtension> extensions = List.of();

    /**
     * Open a .pdb archive for reading.
     *
     * @param pdbPath path to the .pdb file
     */
    public PdbLoader(Path pdbPath) throws IOException
    {
        this.archive = new CompressedArchiveReader(pdbPath);
        this.cache = new LinkedHashMap<>();
    }

    /**
     * Set the MetadataAccess used by FlatBuffer wrappers for cross-reference
     * resolution. This should be the {@code PureModel} instance, which
     * provides multi-module resolution across all modules.
     *
     * @param resolver the metadata access (typically PureModel)
     */
    public void setResolver(MetadataAccess resolver)
    {
        this.wrapperResolver = resolver;
    }

    /**
     * Set the PDB extensions used for element deserialization.
     *
     * @param extensions the extensions to consult
     */
    public void setExtensions(List<? extends PDBExtension> extensions)
    {
        this.extensions = extensions;
    }

    /**
     * Resolve an element by path. Checks the local cache first,
     * then deserializes from the archive.
     */
    public PackageableElement resolve(String path)
    {
        // 1. Check cache
        PackageableElement cached = cache.get(path);
        if (cached != null)
        {
            return cached;
        }

        // 2. Try to load from archive
        if (archive.hasElement(path))
        {
            PackageableElement element = deserializeElement(path);
            if (element != null)
            {
                cache.put(path, element);
                return element;
            }
        }

        return null;
    }

    /**
     * Check if the archive contains an element.
     */
    public boolean hasElement(String path)
    {
        return archive.hasElement(path);
    }

    /**
     * Get all element paths in the archive.
     */
    public Set<String> elementPaths()
    {
        return archive.elementPaths();
    }

    /**
     * Load all elements eagerly into the cache and return them.
     * Useful for bulk-loading into PureModel's index.
     */
    public Map<String, PackageableElement> loadAll()
    {
        for (String path : archive.elementPaths())
        {
            if (!cache.containsKey(path))
            {
                PackageableElement element = deserializeElement(path);
                if (element != null)
                {
                    cache.put(path, element);
                }
            }
        }
        return cache;
    }

    /**
     * Deserialize a single element from the archive by consulting extensions.
     */
    private PackageableElement deserializeElement(String path)
    {
        String typeName = archive.getElementType(path);
        byte[] data = archive.readEntryBytes(path);
        if (typeName == null || data == null)
        {
            return null;
        }

        for (PDBExtension ext : extensions)
        {
            PackageableElement result = ext.deserialize(typeName, data, wrapperResolver);
            if (result != null)
            {
                return result;
            }
        }

        return null;
    }
}

