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

package org.finos.legend.pure.truffle.types;

/**
 * Persistent {@code Integer[*]} sequence optimized for LINEAR front-extension —
 * the access pattern of the self-hosted PDB writer's FlatBuffers builder,
 * which prepends a few bytes per field write while threading one builder
 * value through a fold.
 *
 * <p>A value is a window {@code buf[start .. buf.length)} over a shared
 * backing array whose slots below {@code start} are unclaimed reserve space.
 * {@link #prepend} writes the new elements into that reserve and returns a
 * NEW value with a lower {@code start} — the old value is untouched in every
 * observable way, because its window never included the claimed slots. This
 * preserves value semantics exactly; it only turns the dominant linear case
 * from O(size) copy per prepend into O(k) for k new elements.</p>
 *
 * <p>The one hazard is branching: two prepends onto the SAME value would
 * both want the same reserve slots. The first claims them and sets
 * {@link #extended}; a later prepend onto the same value sees the flag and
 * falls back to a full copy (today's behavior — correct, just not fast).
 * Views produced by {@link #dropView} are born {@code extended} because the
 * region in front of them belongs to an older value's window.</p>
 *
 * <p>Thread-safety: the Pure interpreter is single-threaded, so
 * {@code extended} is a plain field. If parallel Pure execution ever lands,
 * the claim must become a CAS.</p>
 */
public final class PrependLongSequence extends PureSequence
{
    private final long[] buf;
    private final int start;
    private boolean extended;

    private PrependLongSequence(long[] buf, int start, boolean extended)
    {
        this.buf = buf;
        this.start = start;
        this.extended = extended;
    }

    /** Seed from plain content, right-aligned in a buffer with front reserve. */
    public static PrependLongSequence seed(long[] prefix, long[] content)
    {
        int size = prefix.length + content.length;
        // Reserve grows with content so per-write reseeding amortizes away;
        // capped so huge buffers don't double their footprint forever.
        int reserve = Math.min(Math.max(size, 256), 1 << 20);
        long[] buf = new long[reserve + size];
        System.arraycopy(prefix, 0, buf, reserve, prefix.length);
        System.arraycopy(content, 0, buf, reserve + prefix.length, content.length);
        return new PrependLongSequence(buf, reserve, false);
    }

    /**
     * The sequence {@code prefix ++ this}. In-place into the reserve when
     * this value is the unclaimed frontier and the reserve fits; otherwise a
     * fresh seed (full copy — the branched/undersized case).
     */
    public PrependLongSequence prepend(long[] prefix)
    {
        if (!extended && start >= prefix.length)
        {
            extended = true;
            int newStart = start - prefix.length;
            System.arraycopy(prefix, 0, buf, newStart, prefix.length);
            return new PrependLongSequence(buf, newStart, false);
        }
        return seed(prefix, toLongArray());
    }

    /**
     * The sequence without its first {@code n} elements, as an O(1) view.
     * Born {@code extended}: the region in front of the view belongs to this
     * value's window, so the view must never claim it.
     */
    public PrependLongSequence dropView(int n)
    {
        return new PrependLongSequence(buf, start + n, true);
    }

    /** O(1) view over an existing plain sequence (also born extended). */
    public static PrependLongSequence viewOf(LongSequence ls, int from)
    {
        return new PrependLongSequence(ls.backingValues(), from, true);
    }

    public long[] toLongArray()
    {
        long[] out = new long[size()];
        System.arraycopy(buf, start, out, 0, out.length);
        return out;
    }

    public long getLong(int index)
    {
        return buf[start + index];
    }

    @Override
    public int size()
    {
        return buf.length - start;
    }

    @Override
    public boolean isEmpty()
    {
        return start == buf.length;
    }

    @Override
    public Object getBoxed(int index)
    {
        return buf[start + index];
    }

    @Override
    public Object[] toBoxedArray()
    {
        Object[] boxed = new Object[size()];
        for (int i = 0; i < boxed.length; i++)
        {
            boxed[i] = buf[start + i];
        }
        return boxed;
    }

    @Override
    public String toString()
    {
        return "PrependLongSequence[size=" + size() + "]";
    }
}
