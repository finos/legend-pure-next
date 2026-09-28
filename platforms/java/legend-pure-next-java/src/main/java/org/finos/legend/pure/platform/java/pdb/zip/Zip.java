// Copyright (c) 2020-present, Goldman Sachs
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

package org.finos.legend.pure.platform.java.pdb.zip;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * Generic ZIP reader (STORED + deflate entries). The central directory is
 * indexed up front but each entry is decompressed only on {@link #read}, so
 * opening an archive is cheap and only touched entries are inflated.
 *
 * <p>This is the host wiring under the PDB store: a .pdb is a ZIP of FlatBuffer
 * element blobs under {@code elements/} plus index sections. Nothing here is
 * PDB-specific, though — it's plain zip decoding, and it mirrors the JavaScript
 * platform's {@code pdbModule/zip/zip.js} so the two hosts line up.</p>
 *
 * <p>Unlike that one it needs no bundled inflater: {@code java.util.zip} is
 * synchronous, which is what the reader requires. The format itself stays in
 * Pure — this moves only the container.</p>
 */
public final class Zip
{
    private static final int EOCD_SIG = 0x06054b50;
    private static final int CD_SIG = 0x02014b50;
    private static final int LOCAL_SIG = 0x04034b50;

    private final byte[] bytes;
    private final Map<String, Entry> entries = new LinkedHashMap<>();

    public Zip(byte[] bytes)
    {
        this.bytes = bytes;
        int eocd = findEndOfCentralDirectory();
        int count = u16(eocd + 10);
        int offset = (int) u32(eocd + 16);
        for (int i = 0; i < count; i++)
        {
            if (u32(offset) != CD_SIG)
            {
                throw new IllegalStateException("zip: bad central directory entry at " + offset);
            }
            int method = u16(offset + 10);
            long compressed = u32(offset + 20);
            long size = u32(offset + 24);
            int nameLength = u16(offset + 28);
            int extraLength = u16(offset + 30);
            int commentLength = u16(offset + 32);
            int localHeader = (int) u32(offset + 42);
            String name = new String(bytes, offset + 46, nameLength, java.nio.charset.StandardCharsets.UTF_8);
            entries.put(name, new Entry(name, method, localHeader, (int) compressed, (int) size));
            offset += 46 + nameLength + extraLength + commentLength;
        }
    }

    /** Every entry, in central-directory order. */
    public Map<String, Entry> entries()
    {
        return entries;
    }

    /** An entry's bytes, inflated now (deflate) or copied out (stored). */
    public byte[] read(Entry entry)
    {
        int local = entry.localHeader;
        if (u32(local) != LOCAL_SIG)
        {
            throw new IllegalStateException("zip: bad local header for " + entry.name);
        }
        int start = local + 30 + u16(local + 26) + u16(local + 28);
        if (entry.method == 0)
        {
            byte[] stored = new byte[entry.size];
            System.arraycopy(bytes, start, stored, 0, entry.size);
            return stored;
        }
        if (entry.method != 8)
        {
            throw new IllegalStateException("zip: unsupported compression method " + entry.method + " for " + entry.name);
        }
        byte[] out = new byte[entry.size];
        Inflater inflater = new Inflater(true);
        try
        {
            inflater.setInput(bytes, start, entry.compressedSize);
            int done = 0;
            while (done < entry.size && !inflater.finished())
            {
                int read = inflater.inflate(out, done, entry.size - done);
                if (read == 0 && (inflater.needsInput() || inflater.needsDictionary()))
                {
                    break;
                }
                done += read;
            }
            if (done != entry.size)
            {
                throw new IllegalStateException("zip: " + entry.name + " inflated to " + done + " of " + entry.size + " bytes");
            }
        }
        catch (DataFormatException e)
        {
            throw new IllegalStateException("zip: " + entry.name + " is not valid deflate data", e);
        }
        finally
        {
            inflater.end();
        }
        return out;
    }

    private int findEndOfCentralDirectory()
    {
        // The EOCD is last, after a comment of up to 64k.
        for (int i = bytes.length - 22; i >= 0 && i >= bytes.length - 22 - 0xFFFF; i--)
        {
            if (u32(i) == EOCD_SIG)
            {
                return i;
            }
        }
        throw new IllegalStateException("zip: no end-of-central-directory record");
    }

    private int u16(int at)
    {
        return (bytes[at] & 0xFF) | ((bytes[at + 1] & 0xFF) << 8);
    }

    private long u32(int at)
    {
        return (bytes[at] & 0xFFL)
                | ((bytes[at + 1] & 0xFFL) << 8)
                | ((bytes[at + 2] & 0xFFL) << 16)
                | ((bytes[at + 3] & 0xFFL) << 24);
    }

    /** One central-directory record: where the bytes are, and how big. */
    public static final class Entry
    {
        public final String name;
        final int method;
        final int localHeader;
        final int compressedSize;
        final int size;

        Entry(String name, int method, int localHeader, int compressedSize, int size)
        {
            this.name = name;
            this.method = method;
            this.localHeader = localHeader;
            this.compressedSize = compressedSize;
            this.size = size;
        }
    }
}
