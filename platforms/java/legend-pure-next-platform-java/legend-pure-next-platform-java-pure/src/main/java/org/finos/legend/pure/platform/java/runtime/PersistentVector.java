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

import java.util.Arrays;
import java.util.function.Consumer;

/**
 * An immutable vector: a 32-way trie of full 32-element leaves, plus a tail holding the last (up to 32)
 * elements, as Clojure's PersistentVector. {@link #append}, {@link #set} and {@link #get} are O(log32 n).
 *
 * <p>An update copies only the arrays on the path to the changed cell and shares every other array with the
 * vector it came from, so every earlier version stays valid and unchanged: no array is ever written once a
 * vector holds it.</p>
 */
public final class PersistentVector
{
    private static final int BITS = 5;
    private static final int WIDTH = 1 << BITS;
    private static final int MASK = WIDTH - 1;
    private static final Object[] NO_ELEMENTS = new Object[0];

    public static final PersistentVector EMPTY = new PersistentVector(0, BITS, new Object[WIDTH], NO_ELEMENTS);

    private final int count;
    // Levels below the root, in bits: the root's children are leaves when shift == BITS.
    private final int shift;
    private final Object[] root;
    private final Object[] tail;

    private PersistentVector(int count, int shift, Object[] root, Object[] tail)
    {
        this.count = count;
        this.shift = shift;
        this.root = root;
        this.tail = tail;
    }

    public int size()
    {
        return count;
    }

    // Index of the tail's first element: every element before it is in the trie.
    private int tailOffset()
    {
        return count < WIDTH ? 0 : ((count - 1) >>> BITS) << BITS;
    }

    public Object get(int i)
    {
        return leafFor(i)[i & MASK];
    }

    // The array holding element i: the tail, or a leaf of the trie.
    private Object[] leafFor(int i)
    {
        if (i < 0 || i >= count)
        {
            throw new IndexOutOfBoundsException("Index " + i + " out of bounds for size " + count);
        }
        if (i >= tailOffset())
        {
            return tail;
        }
        Object[] node = root;
        for (int level = shift; level > 0; level -= BITS)
        {
            node = (Object[]) node[(i >>> level) & MASK];
        }
        return node;
    }

    public PersistentVector append(Object value)
    {
        if (count - tailOffset() < WIDTH)
        {
            Object[] newTail = Arrays.copyOf(tail, tail.length + 1);
            newTail[tail.length] = value;
            return new PersistentVector(count + 1, shift, root, newTail);
        }
        // The tail is full: it becomes a leaf of the trie, and the value starts a new tail.
        Object[] newRoot;
        int newShift = shift;
        if ((count >>> BITS) > (1 << shift))
        {
            // The trie is full at this height: a new root holds the old one and a path to the new leaf.
            newRoot = new Object[WIDTH];
            newRoot[0] = root;
            newRoot[1] = newPath(shift, tail);
            newShift += BITS;
        }
        else
        {
            newRoot = pushTail(shift, root, tail);
        }
        return new PersistentVector(count + 1, newShift, newRoot, new Object[]{value});
    }

    // A copy of `parent` (at `level`) with `leaf` added as the trie's next leaf.
    private Object[] pushTail(int level, Object[] parent, Object[] leaf)
    {
        int subIndex = ((count - 1) >>> level) & MASK;
        Object[] result = parent.clone();
        Object child;
        if (level == BITS)
        {
            child = leaf;
        }
        else
        {
            Object[] existing = (Object[]) parent[subIndex];
            child = existing != null ? pushTail(level - BITS, existing, leaf) : newPath(level - BITS, leaf);
        }
        result[subIndex] = child;
        return result;
    }

    private static Object[] newPath(int level, Object[] leaf)
    {
        if (level == 0)
        {
            return leaf;
        }
        Object[] node = new Object[WIDTH];
        node[0] = newPath(level - BITS, leaf);
        return node;
    }

    public PersistentVector set(int i, Object value)
    {
        if (i < 0 || i >= count)
        {
            throw new IndexOutOfBoundsException("Index " + i + " out of bounds for size " + count);
        }
        if (i >= tailOffset())
        {
            Object[] newTail = tail.clone();
            newTail[i & MASK] = value;
            return new PersistentVector(count, shift, root, newTail);
        }
        return new PersistentVector(count, shift, setIn(shift, root, i, value), tail);
    }

    private static Object[] setIn(int level, Object[] node, int i, Object value)
    {
        Object[] result = node.clone();
        if (level == 0)
        {
            result[i & MASK] = value;
        }
        else
        {
            int subIndex = (i >>> level) & MASK;
            result[subIndex] = setIn(level - BITS, (Object[]) node[subIndex], i, value);
        }
        return result;
    }

    /** Every element in index order, a leaf at a time. */
    public void forEach(Consumer<Object> consumer)
    {
        int i = 0;
        while (i < count)
        {
            Object[] leaf = leafFor(i);
            int end = Math.min(leaf.length, count - i);
            for (int j = 0; j < end; j++)
            {
                consumer.accept(leaf[j]);
            }
            i += end;
        }
    }
}
