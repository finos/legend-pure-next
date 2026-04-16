package org.finos.legend.pure.m3.module.pdbModule.archive;

/**
 * A named entry to include in a .pdb archive.
 * Used for both element entries (e.g. "Class") and
 * extra sections (e.g. "functionIndex").
 *
 * @param name the entry name / type discriminator
 * @param data the serialized bytes
 */
public record PDBArchiveSection(String name, byte[] data)
{
}
