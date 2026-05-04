package org.finos.legend.pure.m3.module.pdbModule.archive;

import meta.pure.metamodel.PackageableElement;
import org.finos.legend.pure.m3.module.MetadataAccess;
import org.finos.legend.pure.m3.module.Module;

import java.util.List;

/**
 * Extension point for contributing language-specific data to .pdb archives.
 *
 * <p>Implementations can provide additional ZIP entries (e.g. a function index)
 * and custom element serialization / deserialization handlers.</p>
 */
public interface PDBExtension
{
    /**
     * Extra ZIP entries to write into the .pdb archive.
     * Each section provides a ZIP entry name and the serialized bytes.
     *
     * @param module the module being serialized
     * @return archive sections to include, or an empty list
     */
    default List<PDBArchiveSection> archiveSections(Module module)
    {
        return List.of();
    }

    /**
     * Deserialize a FlatBuffer entry by type name.
     * Return {@code null} if this extension does not handle the given type.
     *
     * @param typeName the type discriminator (e.g. "UserDefinedFunction")
     * @param data     the raw FlatBuffer bytes
     * @param resolver the metadata access for cross-reference resolution
     * @return the deserialized element, or {@code null}
     */
    default PackageableElement deserialize(String typeName, byte[] data, MetadataAccess resolver)
    {
        return null;
    }

    /**
     * Serialize an element to a FlatBuffer blob with a type name.
     * Return {@code null} if this extension does not handle the given element.
     *
     * @param element the element to serialize
     * @return a {@link PDBArchiveSection} with type name and bytes, or {@code null}
     */
    default PDBArchiveSection serialize(PackageableElement element)
    {
        return null;
    }
}
