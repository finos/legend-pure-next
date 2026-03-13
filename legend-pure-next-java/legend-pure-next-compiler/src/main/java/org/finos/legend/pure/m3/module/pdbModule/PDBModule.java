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

package org.finos.legend.pure.m3.module.pdbModule;

import meta.pure.metamodel.PackageableElement;
import org.eclipse.collections.api.list.MutableList;
import org.finos.legend.pure.m3.module.MetadataAccessExtension;
import org.finos.legend.pure.m3.module.ScopedMetadataAccess;
import org.finos.legend.pure.m3.module.MetadataAccess;
import org.finos.legend.pure.m3.PureModel;
import org.finos.legend.pure.m3.module.Module;
import org.finos.legend.pure.m3.module.pdbModule.archive.CompressedArchiveReader;
import org.finos.legend.pure.m3.module.pdbModule.archive.PdbLoader;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * A proxy to a pre-compiled .pdb archive, configurable for two modes:
 *
 * <ul>
 *   <li><b>COMPILATION</b> — extensions can read the archive to build
 *       language-specific indices (e.g. the Pure function index).
 *       Elements are not loaded until explicitly requested.</li>
 *   <li><b>EXECUTION</b> — loads elements lazily on demand by reading
 *       the archive entry at the corresponding package location.</li>
 * </ul>
 *
 * <p>No file handles are held open between reads — the underlying
 * archive is opened briefly for each element read and closed
 * immediately, so this module does not require explicit lifecycle
 * management.</p>
 */
public class PDBModule implements Module
{
    public enum Mode
    {
        COMPILATION,
        EXECUTION
    }

    private final CompressedArchiveReader archive;
    private final PdbLoader loader;
    private final Mode mode;
    private final String name;
    private final String packagePattern;
    private final List<String> dependencies;
    private MetadataAccess resolver;
    private MutableList<MetadataAccessExtension> metadataAccessExtensions;

    /**
     * Open a .pdb archive in the specified mode.
     *
     * @param pdbPath path to the .pdb file
     * @param mode    the operating mode
     */
    public PDBModule(Path pdbPath, Mode mode) throws IOException
    {
        this(pdbPath, mode, pdbPath.getFileName().toString(), "*", List.of());
    }

    /**
     * Open a .pdb archive in the specified mode with metadata.
     *
     * @param pdbPath        path to the .pdb file
     * @param mode           the operating mode
     * @param name           the module name
     * @param packagePattern the package pattern
     * @param dependencies   the module dependencies
     */
    public PDBModule(Path pdbPath, Mode mode, String name, String packagePattern, List<String> dependencies) throws IOException
    {
        this.archive = new CompressedArchiveReader(pdbPath);
        this.loader = new PdbLoader(pdbPath);
        this.mode = mode;
        this.name = name;
        this.packagePattern = packagePattern;
        this.dependencies = dependencies;
    }

    @Override
    public void setPureModel(PureModel model)
    {
        ScopedMetadataAccess scope = new ScopedMetadataAccess(this, model);
        this.loader.setResolver(scope);
        this.loader.setExtensions(model.extensions());
        this.resolver = scope;
        this.metadataAccessExtensions = model.extensions().collect(e -> e.buildMetadataExtensionForModule(this)).select(Objects::nonNull);
    }

    /**
     * Return the archive reader. Extensions can use this to read
     * language-specific sections (e.g. the function index).
     */
    public CompressedArchiveReader archive()
    {
        return archive;
    }

    /**
     * Return the metadata resolver set during {@link #setPureModel}.
     * Extensions can use this to resolve FlatBuffer types into
     * real metamodel types.
     */
    public MetadataAccess resolver()
    {
        return resolver;
    }

    @Override
    public <T extends MetadataAccessExtension> MutableList<T> getMetadataAccessExtension(Class<T> clz)
    {
        return this.metadataAccessExtensions.selectInstancesOf(clz);
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

    /**
     * Get an element by its fully-qualified path.
     * Elements are loaded lazily from the archive in both modes.
     */
    @Override
    public PackageableElement getElement(String path)
    {
        return loader.resolve(path);
    }

    /**
     * Check if an element exists in the archive.
     */
    @Override
    public boolean hasElement(String path)
    {
        return loader.hasElement(path);
    }

    /**
     * Get all element paths in the archive.
     */
    @Override
    public Set<String> elementPaths()
    {
        return loader.elementPaths();
    }

    /**
     * @return the operating mode
     */
    public Mode mode()
    {
        return mode;
    }
}

