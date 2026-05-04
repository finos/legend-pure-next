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

import java.io.IOException;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Reads compiled Pure model elements from a ZIP archive.
 * Each ZIP entry contains a FlatBuffer blob for a single element.
 * Entry names follow the format: "elements/fully/qualified/path.TypeName".
 *
 * <p>Non-element entries (e.g. "functionIndex", "elementIndex") are
 * stored as named sections accessible via {@link #readSection(String)}.</p>
 *
 * <p>Elements are deserialized lazily — only when explicitly requested.
 * The archive file is opened briefly for each read and closed immediately
 * afterwards, so no file handles are held open between reads.</p>
 */
public class CompressedArchiveReader
{
    private final Path archivePath;
    // path -> entry name in the zip (for elements)
    private final Map<String, String> entryIndex;
    // path -> typeName
    private final Map<String, String> typeIndex;
    // named sections (e.g. "functionIndex", "elementIndex") -> entry name
    private final Map<String, String> sections;

    public CompressedArchiveReader(Path archivePath) throws IOException
    {
        this.archivePath = archivePath;
        this.entryIndex = new LinkedHashMap<>();
        this.typeIndex = new LinkedHashMap<>();
        this.sections = new LinkedHashMap<>();

        try (ZipFile zipFile = new ZipFile(archivePath.toFile()))
        {
            Enumeration<? extends ZipEntry> entries = zipFile.entries();
            while (entries.hasMoreElements())
            {
                ZipEntry entry = entries.nextElement();
                String entryName = entry.getName();

                if (entryName.startsWith("elements/"))
                {
                    String effectiveName = entryName.substring("elements/".length());
                    int dotIdx = effectiveName.lastIndexOf('.');
                    if (dotIdx > 0)
                    {
                        String pathPart = effectiveName.substring(0, dotIdx);
                        String typeName = effectiveName.substring(dotIdx + 1);
                        String path = pathPart.replace("/", "::");
                        entryIndex.put(path, entryName);
                        typeIndex.put(path, typeName);
                    }
                }
                else
                {
                    // Non-element entries are named sections
                    sections.put(entryName, entryName);
                }
            }
        }
    }

    /**
     * Number of elements in the archive.
     */
    public int elementCount()
    {
        return entryIndex.size();
    }

    /**
     * Check if an element with the given path exists.
     */
    public boolean hasElement(String path)
    {
        return entryIndex.containsKey(path);
    }

    /**
     * Get the type name of an element (e.g. "Class", "Enumeration").
     */
    public String getElementType(String path)
    {
        return typeIndex.get(path);
    }

    /**
     * Get all element paths.
     */
    public Set<String> elementPaths()
    {
        return entryIndex.keySet();
    }

    /**
     * Read raw bytes for an element by path.
     */
    public byte[] readEntryBytes(String path)
    {
        String entryName = entryIndex.get(path);
        return readZipEntry(entryName);
    }

    /**
     * Read raw bytes for a named section (e.g. "functionIndex", "elementIndex").
     * Returns null if the section does not exist.
     */
    public byte[] readSection(String name)
    {
        String entryName = sections.get(name);
        return readZipEntry(entryName);
    }

    private byte[] readZipEntry(String entryName)
    {
        if (entryName == null)
        {
            return null;
        }
        try (ZipFile zipFile = new ZipFile(archivePath.toFile()))
        {
            ZipEntry entry = zipFile.getEntry(entryName);
            if (entry == null)
            {
                return null;
            }
            return zipFile.getInputStream(entry).readAllBytes();
        }
        catch (IOException e)
        {
            throw new RuntimeException("Failed to read entry: " + entryName, e);
        }
    }
}

