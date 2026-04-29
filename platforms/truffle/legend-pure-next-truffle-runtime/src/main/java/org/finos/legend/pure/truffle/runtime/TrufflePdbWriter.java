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

package org.finos.legend.pure.truffle.runtime;

import com.google.flatbuffers.FlatBufferBuilder;
import org.finos.legend.pure.m3.module.pdbModule.fbs.ElementIndex;
import org.finos.legend.pure.m3.module.pdbModule.fbs.ElementIndexEntry;
import org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.PackageableElement;
import org.finos.legend.pure.truffle.runtime.codegen.GeneratedFlatBufferWriter;
import org.finos.legend.pure.truffle.runtime.helper._PackageableElement;

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
 * Writes truffle-namespace metamodel elements to a {@code .pdb} archive.
 *
 * <p>Mirrors {@code CompressedArchiveWriter} on the bootstrap side but accepts
 * truffle PDB types ({@code org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.*})
 * directly, so the runtime can serialise the in-memory graph it executes against
 * without translating back to bootstrap shapes first.</p>
 *
 * <p>Each element becomes one ZIP entry at
 * {@code elements/<package-path>/<name>.<TypeName>}, holding the raw FlatBuffer
 * bytes produced by the generated writer. A single {@code elementIndex} ZIP
 * entry holds an FBS-encoded path → typeName map for fast lookup.</p>
 */
public final class TrufflePdbWriter
{
    private TrufflePdbWriter()
    {
    }

    /**
     * Write the given elements to a {@code .pdb} archive at {@code outputPath}.
     * Required-property validation is on by default — call the overload to
     * disable it for round-tripping content already on disk.
     */
    public static void write(Iterable<? extends PackageableElement> elements, Path outputPath) throws IOException
    {
        write(elements, outputPath, true);
    }

    /**
     * Write the given elements to {@code outputPath}. When
     * {@code validateRequired} is false, the generated writer skips the
     * "[1] / [1..*] property must be non-null/non-empty" checks — useful when
     * round-tripping elements loaded from a PDB whose readers may have gaps.
     */
    public static void write(Iterable<? extends PackageableElement> elements, Path outputPath, boolean validateRequired) throws IOException
    {
        if (outputPath.getParent() != null)
        {
            Files.createDirectories(outputPath.getParent());
        }
        try (OutputStream fos = Files.newOutputStream(outputPath);
             ZipOutputStream zos = new ZipOutputStream(fos))
        {
            zos.setLevel(Deflater.BEST_COMPRESSION);

            List<String[]> indexEntries = new ArrayList<>();
            for (PackageableElement element : elements)
            {
                String typeName = elementTypeName(element);
                if (typeName == null)
                {
                    continue; // not an FBS-table type — skip
                }
                String path = _PackageableElement.path(element);

                FlatBufferBuilder builder = new FlatBufferBuilder(1024);
                GeneratedFlatBufferWriter writer = new GeneratedFlatBufferWriter(builder, validateRequired);
                int offset = writer.dispatchWrite(element);
                builder.finish(offset);
                byte[] data = builder.sizedByteArray();

                indexEntries.add(new String[]{path, typeName});

                String entryPath = "elements/" + path.replace("::", "/") + "." + typeName;
                ZipEntry entry = new ZipEntry(entryPath);
                zos.putNextEntry(entry);
                zos.write(data);
                zos.closeEntry();
            }

            writeElementIndex(zos, indexEntries);
        }
    }

    /**
     * Derive the bootstrap-side discriminator name from a runtime class.
     * {@code ClassImpl → "Class"}; classes whose simple name doesn't end in
     * {@code Impl} return {@code null} (caller skips them).
     */
    private static String elementTypeName(PackageableElement element)
    {
        String simple = element.getClass().getSimpleName();
        if (simple.endsWith("Impl"))
        {
            return simple.substring(0, simple.length() - "Impl".length());
        }
        if (simple.endsWith("FlatBufferWrapper"))
        {
            return simple.substring(0, simple.length() - "FlatBufferWrapper".length());
        }
        return null;
    }

    private static void writeElementIndex(ZipOutputStream zos, List<String[]> entries) throws IOException
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
            entryOffsets[i] = ElementIndexEntry.createElementIndexEntry(builder, pathOffset, typeOffset);
        }
        int vector = ElementIndex.createElementsVector(builder, entryOffsets);
        builder.finish(ElementIndex.createElementIndex(builder, vector));

        ZipEntry entry = new ZipEntry("elementIndex");
        zos.putNextEntry(entry);
        zos.write(builder.sizedByteArray());
        zos.closeEntry();
    }
}
