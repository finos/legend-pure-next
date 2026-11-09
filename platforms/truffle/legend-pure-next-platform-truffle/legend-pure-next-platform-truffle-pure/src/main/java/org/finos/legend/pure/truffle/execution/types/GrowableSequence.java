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
package org.finos.legend.pure.truffle.execution.types;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import org.finos.legend.pure.truffle.execution.natives.collection.CollectionHelper;

import java.util.Arrays;

/**
 * A sequence the runtime grows in place: what an add, concatenate or reverse gives when the compiler proved its
 * receiver updatable in place (pure/specification/IN_PLACE_UPDATE_ANALYSIS.md) — the only reference to it, so no one can see it change.
 * Any other sequence is copied into one first ({@link #of}): the shared {@link PureSequence#EMPTY}, a literal, a view
 * over another value's storage, a value decoded from a PDB. After that, appends are amortized O(1).
 */
public final class GrowableSequence extends PureSequence
{
    private Object[] values;
    private int size;
    /**
     * Read-only from now on: held where another may see it — stored without being proved updatable in place, read from a field
     * by an ordinary read, or carried by a copy (pure/specification/IN_PLACE_UPDATE_ANALYSIS.md, Fields). Never undone.
     */
    private boolean frozen;

    public void freeze()
    {
        frozen = true;
    }

    private GrowableSequence(Object[] values, int size)
    {
        this.values = values;
        this.size = size;
    }

    /** {@code col} as a sequence this runtime may grow in place: itself when it is one, else a copy with room to grow. */
    @TruffleBoundary
    public static GrowableSequence of(Object col)
    {
        if (col instanceof GrowableSequence growable && !growable.frozen)
        {
            return growable;
        }
        int n = CollectionHelper.size(col);
        Object[] values = new Object[Math.max(8, n * 2)];
        for (int i = 0; i < n; i++)
        {
            values[i] = CollectionHelper.at(col, i);
        }
        return new GrowableSequence(values, n);
    }

    // One call per in-place operation, out of line: the nodes stay small for partial evaluation.

    @TruffleBoundary
    public static GrowableSequence append(Object col, Object value)
    {
        return of(col).add(value);
    }

    @TruffleBoundary
    public static GrowableSequence insert(Object col, int index, Object value)
    {
        return of(col).add(index, value);
    }

    @TruffleBoundary
    public static GrowableSequence appendAll(Object col, Object other)
    {
        return of(col).addAll(other);
    }

    @TruffleBoundary
    public static GrowableSequence reversed(Object col)
    {
        return of(col).reverse();
    }

    @TruffleBoundary
    public GrowableSequence add(Object value)
    {
        ensure(size + 1);
        values[size++] = value;
        return this;
    }

    @TruffleBoundary
    public GrowableSequence add(int index, Object value)
    {
        ensure(size + 1);
        System.arraycopy(values, index, values, index + 1, size - index);
        values[index] = value;
        size++;
        return this;
    }

    @TruffleBoundary
    public GrowableSequence addAll(Object other)
    {
        int n = CollectionHelper.size(other);
        ensure(size + n);
        for (int i = 0; i < n; i++)
        {
            values[size + i] = CollectionHelper.at(other, i);
        }
        size += n;
        return this;
    }

    @TruffleBoundary
    public GrowableSequence reverse()
    {
        for (int i = 0, j = size - 1; i < j; i++, j--)
        {
            Object t = values[i];
            values[i] = values[j];
            values[j] = t;
        }
        return this;
    }

    private void ensure(int capacity)
    {
        if (capacity > values.length)
        {
            values = Arrays.copyOf(values, Math.max(capacity, values.length * 2));
        }
    }

    @Override
    public int size()
    {
        return size;
    }

    @Override
    public boolean isEmpty()
    {
        return size == 0;
    }

    @Override
    public Object getBoxed(int index)
    {
        if (index >= size)
        {
            throw new IndexOutOfBoundsException("Index " + index + " out of bounds for length " + size);
        }
        return values[index];
    }

    @Override
    public Object[] toBoxedArray()
    {
        return Arrays.copyOf(values, size);
    }

    @Override
    public boolean equals(Object o)
    {
        if (this == o) return true;
        if (!(o instanceof PureSequence other)) return false;
        if (size != other.size()) return false;
        for (int i = 0; i < size; i++)
        {
            if (!java.util.Objects.equals(values[i], other.getBoxed(i))) return false;
        }
        return true;
    }

    @Override
    public int hashCode()
    {
        int h = 1;
        for (int i = 0; i < size; i++)
        {
            h = 31 * h + java.util.Objects.hashCode(values[i]);
        }
        return h;
    }
}
