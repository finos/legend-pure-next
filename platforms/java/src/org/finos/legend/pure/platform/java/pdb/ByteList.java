// Copyright 2026 Goldman Sachs
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

package org.finos.legend.pure.platform.java.pdb;

import java.util.AbstractList;
import java.util.List;
import java.util.RandomAccess;

/** A file's bytes as the PDB reader's Integer[*]: unsigned, boxed only when read, sliced without copying. */
final class ByteList extends AbstractList<Long> implements RandomAccess
{
    private final byte[] data;
    private final int offset;
    private final int length;

    /** The same bytes, held as a byte[] rather than boxed in a List of Long. */
    static ByteList compact(List<?> values)
    {
        if (values instanceof ByteList)
        {
            return (ByteList) values;
        }
        byte[] bytes = new byte[values.size()];
        for (int i = 0; i < bytes.length; i++)
        {
            bytes[i] = (byte) ((Number) values.get(i)).longValue();
        }
        return new ByteList(bytes);
    }

    ByteList(byte[] data)
    {
        this(data, 0, data.length);
    }

    private ByteList(byte[] data, int offset, int length)
    {
        this.data = data;
        this.offset = offset;
        this.length = length;
    }

    @Override
    public Long get(int index)
    {
        if (index < 0 || index >= length)
        {
            throw new IndexOutOfBoundsException("Index " + index + " out of bounds for length " + length);
        }
        return (long) (data[offset + index] & 0xFF);
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
        return new ByteList(data, offset + from, to - from);
    }
}
