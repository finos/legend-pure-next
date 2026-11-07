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
import java.util.Objects;

/**
 * An immutable hash array mapped trie (Bagwell's HAMT, as Clojure's PersistentHashMap) of {@link Entry}s, keyed
 * by Java {@code equals}/{@code hashCode} like {@link java.util.HashMap}, {@code null} key included.
 *
 * <p>A node is a 32-bit bitmap of the occupied hash fragments (5 bits per level) and a dense array of its
 * children, each an entry or a deeper node; entries whose full hashes are equal share a collision node. An
 * update copies the nodes on one root-to-leaf path, O(log32 n), and shares all the others, so every earlier
 * version stays valid and unchanged: no node is ever written once a trie holds it.</p>
 */
final class PersistentHashTrie
{
    private static final int BITS = 5;
    private static final int MASK = (1 << BITS) - 1;

    static final PersistentHashTrie EMPTY = new PersistentHashTrie(null);

    /** A key, its value and its insertion position in the {@link PersistentOrderedMap} holding it. */
    static final class Entry
    {
        final Object key;
        final int hash;
        final Object value;
        final int seq;

        Entry(Object key, int hash, Object value, int seq)
        {
            this.key = key;
            this.hash = hash;
            this.value = value;
            this.seq = seq;
        }

        boolean hasKey(Object otherKey, int otherHash)
        {
            return hash == otherHash && Objects.equals(key, otherKey);
        }
    }

    private static final class BitmapNode
    {
        final int bitmap;
        // Entry or node per set bit, in bit order.
        final Object[] children;

        BitmapNode(int bitmap, Object[] children)
        {
            this.bitmap = bitmap;
            this.children = children;
        }

        int indexOf(int bit)
        {
            return Integer.bitCount(bitmap & (bit - 1));
        }
    }

    private static final class CollisionNode
    {
        final int hash;
        final Entry[] entries;

        CollisionNode(int hash, Entry[] entries)
        {
            this.hash = hash;
            this.entries = entries;
        }
    }

    // A BitmapNode, or null when empty.
    private final BitmapNode root;

    private PersistentHashTrie(BitmapNode root)
    {
        this.root = root;
    }

    static int hash(Object key)
    {
        int h = key == null ? 0 : key.hashCode();
        return h ^ (h >>> 16);
    }

    private static int bit(int hash, int shift)
    {
        return 1 << ((hash >>> shift) & MASK);
    }

    Entry find(Object key, int hash)
    {
        Object node = root;
        int shift = 0;
        while (node != null)
        {
            if (node instanceof BitmapNode b)
            {
                int bit = bit(hash, shift);
                if ((b.bitmap & bit) == 0)
                {
                    return null;
                }
                Object child = b.children[b.indexOf(bit)];
                if (child instanceof Entry e)
                {
                    return e.hasKey(key, hash) ? e : null;
                }
                node = child;
                shift += BITS;
            }
            else
            {
                CollisionNode c = (CollisionNode) node;
                if (c.hash != hash)
                {
                    return null;
                }
                for (Entry e : c.entries)
                {
                    if (e.hasKey(key, hash))
                    {
                        return e;
                    }
                }
                return null;
            }
        }
        return null;
    }

    /** This trie with {@code entry}, replacing the entry of an equal key if there is one. */
    PersistentHashTrie with(Entry entry)
    {
        return new PersistentHashTrie((BitmapNode) with(root, 0, entry));
    }

    private static Object with(Object node, int shift, Entry entry)
    {
        if (node == null)
        {
            return new BitmapNode(bit(entry.hash, shift), new Object[]{entry});
        }
        if (node instanceof CollisionNode c)
        {
            if (c.hash == entry.hash)
            {
                return withCollision(c, entry);
            }
            // A different hash reaching a collision node: put the node under a bitmap at this level, then add.
            return with(new BitmapNode(bit(c.hash, shift), new Object[]{c}), shift, entry);
        }
        BitmapNode b = (BitmapNode) node;
        int bit = bit(entry.hash, shift);
        int index = b.indexOf(bit);
        if ((b.bitmap & bit) == 0)
        {
            Object[] children = new Object[b.children.length + 1];
            System.arraycopy(b.children, 0, children, 0, index);
            children[index] = entry;
            System.arraycopy(b.children, index, children, index + 1, b.children.length - index);
            return new BitmapNode(b.bitmap | bit, children);
        }
        Object child = b.children[index];
        Object newChild;
        if (child instanceof Entry existing)
        {
            newChild = existing.hasKey(entry.key, entry.hash) ? entry : merge(existing, entry, shift + BITS);
        }
        else
        {
            newChild = with(child, shift + BITS, entry);
        }
        Object[] children = b.children.clone();
        children[index] = newChild;
        return new BitmapNode(b.bitmap, children);
    }

    private static CollisionNode withCollision(CollisionNode c, Entry entry)
    {
        for (int i = 0; i < c.entries.length; i++)
        {
            if (c.entries[i].hasKey(entry.key, entry.hash))
            {
                Entry[] entries = c.entries.clone();
                entries[i] = entry;
                return new CollisionNode(c.hash, entries);
            }
        }
        Entry[] entries = Arrays.copyOf(c.entries, c.entries.length + 1);
        entries[c.entries.length] = entry;
        return new CollisionNode(c.hash, entries);
    }

    // The node holding two entries of different keys from `shift` down. Their fragments agree above `shift`;
    // different hashes differ by shift 30 at the latest, so the shift never passes 32.
    private static Object merge(Entry a, Entry b, int shift)
    {
        if (a.hash == b.hash)
        {
            return new CollisionNode(a.hash, new Entry[]{a, b});
        }
        int fragmentA = (a.hash >>> shift) & MASK;
        int fragmentB = (b.hash >>> shift) & MASK;
        if (fragmentA == fragmentB)
        {
            return new BitmapNode(1 << fragmentA, new Object[]{merge(a, b, shift + BITS)});
        }
        Object[] children = fragmentA < fragmentB ? new Object[]{a, b} : new Object[]{b, a};
        return new BitmapNode((1 << fragmentA) | (1 << fragmentB), children);
    }

    /** This trie without the entry of {@code key}; this trie itself when there is none. */
    PersistentHashTrie without(Object key, int hash)
    {
        Object newRoot = without(root, 0, key, hash);
        return newRoot == root ? this : new PersistentHashTrie((BitmapNode) newRoot);
    }

    // The node without the key: the same node when absent, null when nothing is left. A node left holding a
    // single entry is replaced by that entry in its parent.
    private static Object without(Object node, int shift, Object key, int hash)
    {
        if (node == null)
        {
            return null;
        }
        if (node instanceof CollisionNode c)
        {
            return withoutCollision(c, key, hash);
        }
        BitmapNode b = (BitmapNode) node;
        int bit = bit(hash, shift);
        if ((b.bitmap & bit) == 0)
        {
            return b;
        }
        int index = b.indexOf(bit);
        Object child = b.children[index];
        Object newChild;
        if (child instanceof Entry e)
        {
            if (!e.hasKey(key, hash))
            {
                return b;
            }
            newChild = null;
        }
        else
        {
            newChild = without(child, shift + BITS, key, hash);
            if (newChild == child)
            {
                return b;
            }
            newChild = singleEntryOf(newChild);
        }
        if (newChild != null)
        {
            Object[] children = b.children.clone();
            children[index] = newChild;
            return new BitmapNode(b.bitmap, children);
        }
        if (b.children.length == 1)
        {
            return null;
        }
        Object[] children = new Object[b.children.length - 1];
        System.arraycopy(b.children, 0, children, 0, index);
        System.arraycopy(b.children, index + 1, children, index, children.length - index);
        return new BitmapNode(b.bitmap ^ bit, children);
    }

    private static Object withoutCollision(CollisionNode c, Object key, int hash)
    {
        if (c.hash != hash)
        {
            return c;
        }
        for (int i = 0; i < c.entries.length; i++)
        {
            if (c.entries[i].hasKey(key, hash))
            {
                Entry[] entries = new Entry[c.entries.length - 1];
                System.arraycopy(c.entries, 0, entries, 0, i);
                System.arraycopy(c.entries, i + 1, entries, i, entries.length - i);
                return new CollisionNode(c.hash, entries);
            }
        }
        return c;
    }

    // The entry a node holds when it holds only one, else the node.
    private static Object singleEntryOf(Object node)
    {
        if (node instanceof BitmapNode b && b.children.length == 1 && b.children[0] instanceof Entry e)
        {
            return e;
        }
        if (node instanceof CollisionNode c && c.entries.length == 1)
        {
            return c.entries[0];
        }
        return node;
    }
}
