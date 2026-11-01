// Copyright 2024 Goldman Sachs
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

package org.finos.legend.pure.m3.module.pdbModule.archive;

import com.google.flatbuffers.FlatBufferBuilder;
import org.eclipse.collections.impl.utility.Iterate;
import platform.pure.metamodel.PackageableElement;
import org.finos.legend.pure.m3.module.Module;
import org.finos.legend.pure.m3.module.ModuleManifest;
import org.finos.legend.pure.m3.module.pdbModule.fbs.ElementIndex;
import org.finos.legend.pure.m3.module.pdbModule.fbs.ElementIndexEntry;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
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
    private String progressLabel;

    /**
     * Shows a progress bar labelled {@code label} while the elements are serialized: what a CLI compile
     * shows while it writes a PDB. Drawn like Pure's {@code platform::pure::ui::progress} bar, whose
     * "37%  (59/155)" counter the test-all display reads. Without it the writer prints nothing.
     */
    public CompressedArchiveWriter withProgress(String label)
    {
        this.progressLabel = label;
        return this;
    }

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
            // LinkedHashSet: iteration is the element WRITE order, which
            // extensions use to emit their sections (functionIndex) in the
            // same deterministic order as the element entries.
            java.util.Set<String> writtenPaths = new java.util.LinkedHashSet<>();

            // Serialize first WITHOUT writing: entry order is part of the wire
            // contract — entries (and hence elementIndex/functionIndex) are
            // SORTED by path so archives are deterministic regardless of the
            // producing compiler's registration order. The writer writes what
            // it is given faithfully — notably the stored root ('::') entry
            // and the lean split's canonical-but-empty test packages (their
            // children live in the companion -tests PDB); filtering belongs
            // to the element COLLECTORS, not here.
            java.util.TreeMap<String, Object[]> pending = new java.util.TreeMap<>();
            int total = progressLabel == null ? 0 : Iterate.sizeOf(elements);
            int done = 0;
            startProgress(total);
            for (PackageableElement element : elements)
            {
                String path = elementPath(element);
                tickProgress(++done, total, path);
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
                pending.put(path, new Object[]{typeName, data});
            }
            finishProgress();

            for (java.util.Map.Entry<String, Object[]> e : pending.entrySet())
            {
                String path = e.getKey();
                String typeName = (String) e.getValue()[0];
                byte[] data = (byte[]) e.getValue()[1];
                elementEntries.add(new String[]{path, typeName});
                writtenPaths.add(path);
                // Entry name format: "elements/platform/pure/metamodel/type/ElementName.TypeName"
                String entryPath = "elements/" + path.replace("::", "/") + "." + typeName;
                ZipEntry entry = newEntry(entryPath);
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
            // built from elements directly when there's no SourceModule).
            for (PDBArchiveSection section : additionalSections)
            {
                writeSection(zos, section);
            }
        }
    }

    // Every entry carries the same timestamp, so the same content always gives the same archive
    // bytes. Left unset, ZipOutputStream stamps the current time and every rebuild rewrites every
    // PDB. 1980-01-01 00:00:02 is the first DOS instant after the epoch: the JDK treats the epoch
    // itself as its "before 1980" sentinel and then adds an extended-timestamp field holding the
    // time-zone-dependent Unix time. Any later instant is stored as DOS date/time only.
    private static final LocalDateTime ENTRY_TIME = LocalDateTime.of(1980, 1, 1, 0, 0, 2);

    private static ZipEntry newEntry(String name)
    {
        ZipEntry entry = new ZipEntry(name);
        entry.setTimeLocal(ENTRY_TIME);
        return entry;
    }

    private static void writeManifest(ZipOutputStream zos, ModuleManifest manifest) throws IOException
    {
        ZipEntry entry = newEntry(ModuleManifest.ARCHIVE_SECTION);
        zos.putNextEntry(entry);
        zos.write(manifest.toBytes());
        zos.closeEntry();
    }

    private static void writeSection(ZipOutputStream zos, PDBArchiveSection section) throws IOException
    {
        ZipEntry zipEntry = newEntry(section.name());
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

        ZipEntry zipEntry = newEntry("elementIndex");
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

    // The progress bar (see withProgress), drawn as platform::pure::ui::progress draws it: a header, then a
    // bar line and a status line redrawn in place.
    private void startProgress(int total)
    {
        if (progressLabel != null)
        {
            System.out.print(progressLabel + " (" + total + " elements)\n\n\n\u001B[2A");
            System.out.flush();
        }
    }

    private void tickProgress(int done, int total, String current)
    {
        if (progressLabel == null || total == 0)
        {
            return;
        }
        int pct = done * 100 / total;
        if (done == 1 || done == total || pct != (done - 1) * 100 / total)
        {
            int filled = done * 30 / total;
            String status = current.length() > 78 ? "…" + current.substring(current.length() - 77) : current;
            System.out.print("\r\u001B[K[" + "█".repeat(filled) + "░".repeat(30 - filled) + "] " + pct + "%  (" + done + "/" + total + ")"
                    + "\u001B[B\r\u001B[K  " + status + "\u001B[K\u001B[A\r");
            System.out.flush();
        }
    }

    private void finishProgress()
    {
        if (progressLabel != null)
        {
            System.out.print("\u001B[B\r\u001B[K\u001B[B\r");
            System.out.flush();
        }
    }
}
