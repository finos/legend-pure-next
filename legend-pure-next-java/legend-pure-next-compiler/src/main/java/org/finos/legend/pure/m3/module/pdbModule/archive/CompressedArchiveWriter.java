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

import com.google.flatbuffers.FlatBufferBuilder;
import meta.pure.metamodel.PackageableElement;
import org.finos.legend.pure.m3.module.Module;
import org.finos.legend.pure.m3.module.pdbModule.fbs.ElementIndex;
import org.finos.legend.pure.m3.module.pdbModule.fbs.ElementIndexEntry;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Writes compiled Pure model elements into a ZIP archive.
 * Each element is serialized as an individual FlatBuffer blob,
 * stored as a ZIP entry keyed by "TypeName/path::to::element".
 *
 * <p>This enables lazy, per-element deserialization: the reader
 * can extract a single entry from the ZIP without loading the
 * entire archive into memory.</p>
 *
 * <p>All element type handling is delegated to {@link PDBExtension}
 * implementations via {@link PDBExtension#serialize}.</p>
 */
public class CompressedArchiveWriter
{
    /**
     * Write all elements into a ZIP archive, using extensions for
     * element serialization, element index filtering, and
     * language-specific archive sections.
     *
     * @param elements   the elements to serialize
     * @param extensions PDB extensions providing serialization and archive sections
     * @param module     the module being serialized (passed to extensions)
     * @param outputPath the output archive path
     */
    public void write(Iterable<? extends PackageableElement> elements,
                      List<? extends PDBExtension> extensions,
                      Module module,
                      Path outputPath) throws IOException
    {
        try (OutputStream fos = Files.newOutputStream(outputPath);
             ZipOutputStream zos = new ZipOutputStream(fos))
        {
            zos.setLevel(Deflater.BEST_COMPRESSION);

            List<String[]> elementEntries = new ArrayList<>();

            for (PackageableElement element : elements)
            {
                String path = elementPath(element);

                // Delegate serialization to extensions
                String typeName = null;
                byte[] data = null;
                for (PDBExtension ext : extensions)
                {
                    PDBArchiveSection entry = ext.serialize(element);
                    if (entry != null)
                    {
                        typeName = entry.name();
                        data = entry.data();
                        break;
                    }
                }
                if (data == null)
                {
                    continue; // No extension handles this element
                }

                elementEntries.add(new String[]{path, typeName});

                // Entry name format: "elements/meta/pure/metamodel/type/ElementName.TypeName"
                String entryPath = "elements/" + path.replace("::", "/") + "." + typeName;
                ZipEntry entry = new ZipEntry(entryPath);
                zos.putNextEntry(entry);
                zos.write(data);
                zos.closeEntry();
            }

            writeElementIndex(zos, elementEntries);

            // Write extension-contributed archive sections
            for (PDBExtension ext : extensions)
            {
                for (PDBArchiveSection section : ext.archiveSections(module))
                {
                    ZipEntry zipEntry = new ZipEntry(section.name());
                    zos.putNextEntry(zipEntry);
                    zos.write(section.data());
                    zos.closeEntry();
                }
            }
        }
    }

    /**
     * Write the element index as a single ElementIndex FlatBuffer blob.
     */
    private void writeElementIndex(ZipOutputStream zos, List<String[]> entries) throws IOException
    {
        if (entries.isEmpty())
        {
            return;
        }
        FlatBufferBuilder builder = new FlatBufferBuilder(4096);
        int[] entryOffsets = new int[entries.size()];
        for (int i = 0; i < entries.size(); i++)
        {
            int pathOffset = builder.createString(entries.get(i)[0]);
            int typeOffset = builder.createString(entries.get(i)[1]);
            entryOffsets[i] = ElementIndexEntry
                    .createElementIndexEntry(builder, pathOffset, typeOffset);
        }
        int elementsVectorOffset = ElementIndex
                .createElementsVector(builder, entryOffsets);
        int indexOffset = ElementIndex
                .createElementIndex(builder, elementsVectorOffset);
        builder.finish(indexOffset);

        ZipEntry zipEntry = new ZipEntry("elementIndex");
        zos.putNextEntry(zipEntry);
        zos.write(builder.sizedByteArray());
        zos.closeEntry();
    }

    private String elementPath(PackageableElement element)
    {
        if (element._package() != null)
        {
            String parentPath = packagePath(element._package());
            return parentPath.isEmpty() ? element._name() : parentPath + "::" + element._name();
        }
        return element._name();
    }

    private String packagePath(meta.pure.metamodel.Package pkg)
    {
        if (pkg._package() != null && pkg._name() != null && !pkg._name().isEmpty())
        {
            String parentPath = packagePath(pkg._package());
            return parentPath.isEmpty() ? pkg._name() : parentPath + "::" + pkg._name();
        }
        return "";
    }
}
