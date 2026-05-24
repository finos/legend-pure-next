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
import org.finos.legend.pure.m3.module.ModuleManifest;
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
     * Write all elements into a ZIP archive, using extensions for element
     * serialization and language-specific archive sections.
     *
     * @param elements           the elements to serialize
     * @param extensions         PDB extensions providing serialization and archive sections
     * @param module             the module driving extension-contributed sections (e.g. function index)
     * @param manifest           the manifest embedded in the archive — its name/pattern/deps may
     *                           differ from {@code module}'s identity (e.g. compile-via-pure runs
     *                           against base PDB modules but tags the output with the source manifest)
     * @param additionalSections caller-provided archive sections, written after extension sections
     * @param outputPath         the output archive path
     */
    public void write(Iterable<? extends PackageableElement> elements,
                      List<? extends PDBExtension> extensions,
                      Module module,
                      ModuleManifest manifest,
                      List<PDBArchiveSection> additionalSections,
                      Path outputPath) throws IOException
    {
        try (OutputStream fos = Files.newOutputStream(outputPath);
             ZipOutputStream zos = new ZipOutputStream(fos))
        {
            zos.setLevel(Deflater.BEST_COMPRESSION);

            List<String[]> elementEntries = new ArrayList<>();
            // Collect the path-set of elements actually written so we can
            // restrict the extension-contributed sections (e.g. function
            // index) to the same scope. Without this, lean PDBs pick up
            // test-only function entries from the module's full metadata.
            java.util.Set<String> writtenPaths = new java.util.HashSet<>();

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
                writtenPaths.add(path);

                // Entry name format: "elements/meta/pure/metamodel/type/ElementName.TypeName"
                String entryPath = "elements/" + path.replace("::", "/") + "." + typeName;
                ZipEntry entry = new ZipEntry(entryPath);
                zos.putNextEntry(entry);
                zos.write(data);
                zos.closeEntry();
            }

            writeElementIndex(zos, elementEntries);

            // Embed the module manifest (name, package pattern, dependencies)
            // so the archive carries its own identity. Loaders read this on
            // open instead of guessing from filename or constructor args.
            writeManifest(zos, manifest);

            // Write extension-contributed archive sections, restricted to the
            // paths actually written above (so lean PDBs don't carry test
            // function-index entries from the module's full metadata).
            for (PDBExtension ext : extensions)
            {
                for (PDBArchiveSection section : ext.archiveSections(module, writtenPaths))
                {
                    writeSection(zos, section);
                }
            }

            // Write caller-provided additional sections (e.g. functionIndex
            // built from elements directly when there's no LocalModule).
            for (PDBArchiveSection section : additionalSections)
            {
                writeSection(zos, section);
            }
        }
    }

    private static void writeManifest(ZipOutputStream zos, ModuleManifest manifest) throws IOException
    {
        ZipEntry entry = new ZipEntry(ModuleManifest.ARCHIVE_SECTION);
        zos.putNextEntry(entry);
        zos.write(manifest.toBytes());
        zos.closeEntry();
    }

    private static void writeSection(ZipOutputStream zos, PDBArchiveSection section) throws IOException
    {
        ZipEntry zipEntry = new ZipEntry(section.name());
        zos.putNextEntry(zipEntry);
        zos.write(section.data());
        zos.closeEntry();
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

    // Delegates to the central pointer-aware helper. Pure-compiler skeletons
    // wrap their {@code package} field as a {@code PackagePointer} (see
    // {@code compiler.pure}'s pass-1 wrap), so a local walk via
    // {@code _name()} / {@code _package()} alone collapses the parent chain
    // to "" and yields a bare element name — producing duplicate
    // {@code elements/<bare>.<type>} zip entries for any two elements with
    // the same simple name in different packages.
    private String elementPath(PackageableElement element)
    {
        return org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._PackageableElement.path(element);
    }
}
