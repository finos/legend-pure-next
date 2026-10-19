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

package org.finos.legend.pure.platform.java.runtime;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.AbstractList;
import java.util.List;
import java.util.RandomAccess;

/**
 * A Pure {@code Integer[*]} of bytes over a byte[]: an unmodifiable {@code List<Long>} view that boxes only the
 * element read and slices without copying. A whole file as boxed Longs would cost dozens of bytes per byte —
 * a PDB is megabytes.
 */
public final class PureBytes extends AbstractList<Long> implements RandomAccess
{
    public byte[] data;
    public int offset;
    public int length;

    public static PureBytes of(byte[] data, int offset, int length)
    {
        PureBytes b = new PureBytes();
        b.data = data;
        b.offset = offset;
        b.length = length;
        return b;
    }

    public static PureBytes read(String path)
    {
        try
        {
            byte[] bytes = Files.readAllBytes(Path.of(path));
            return of(bytes, 0, bytes.length);
        }
        catch (IOException e)
        {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public Long get(int i)
    {
        if (i < 0 || i >= length)
        {
            throw new IndexOutOfBoundsException("Index " + i + " out of bounds for length " + length);
        }
        return (long) (data[offset + i] & 0xFF);
    }

    @Override
    public int size()
    {
        return length;
    }

    @Override
    public List<Long> subList(int from, int to)
    {
        if (from < 0 || to > length || from > to)
        {
            throw new IndexOutOfBoundsException("fromIndex = " + from + ", toIndex = " + to);
        }
        return of(data, offset + from, to - from);
    }
}
