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

/**
 * A {@code Binary} that can be PREPENDED to cheaply — the byte counterpart of
 * {@link PrependLongSequence}, for the self-hosted PDB writer.
 *
 * <p>{@code fbb::prepend} puts a few bytes in front of the buffer for every
 * scalar, offset, pad byte, vtable and string in the archive, threading one
 * builder value through a fold. With {@code Binary} as a plain {@code byte[]},
 * each of those copied the whole buffer: writing an 8.4MB compiler.pdb became
 * quadratic and exhausted a 30GB heap.</p>
 *
 * <p>A value is a window {@code buf[start .. buf.length)} over a shared backing
 * array whose slots below {@code start} are unclaimed reserve. {@link #prepend}
 * writes into that reserve and returns a NEW value with a lower {@code start};
 * the old value is untouched in every observable way, because its window never
 * included the claimed slots. Value semantics are preserved exactly — only the
 * dominant linear case turns from O(size) per prepend into O(k) for k bytes.</p>
 *
 * <p>The hazard is branching: two prepends onto the SAME value would claim the
 * same slots. The first claims them and sets {@code extended}; a later prepend
 * onto that value sees the flag and falls back to a full copy — correct, just
 * not fast. Same rule as the long version.</p>
 *
 * <p>Thread-safety: the Pure interpreter is single-threaded, so {@code extended}
 * is a plain field. If parallel Pure execution lands, the claim needs a CAS.</p>
 *
 * <p>Every consumer reaches bytes through {@link PureBinary#asBytes}, which
 * materialises this; {@link PureBinary#sizeOf} stays O(1) so the writer never
 * materialises just to measure.</p>
 */
public final class PrependByteSequence
{
    private final byte[] buf;
    private final int start;
    private boolean extended;

    private PrependByteSequence(byte[] buf, int start, boolean extended)
    {
        this.buf = buf;
        this.start = start;
        this.extended = extended;
    }

    /** Seed from plain content, right-aligned in a buffer with front reserve. */
    public static PrependByteSequence seed(byte[] prefix, byte[] content)
    {
        int size = prefix.length + content.length;
        // Reserve grows with content so per-write reseeding amortizes away;
        // capped so huge buffers don't double their footprint forever.
        int reserve = Math.min(Math.max(size, 256), 1 << 20);
        byte[] buf = new byte[reserve + size];
        System.arraycopy(prefix, 0, buf, reserve, prefix.length);
        System.arraycopy(content, 0, buf, reserve + prefix.length, content.length);
        return new PrependByteSequence(buf, reserve, false);
    }

    /**
     * The binary {@code prefix ++ this}. In place into the reserve when this
     * value is the unclaimed frontier and the reserve fits; otherwise a fresh
     * seed (the branched or undersized case, a full copy).
     */
    public PrependByteSequence prepend(byte[] prefix)
    {
        if (!this.extended && this.start >= prefix.length)
        {
            this.extended = true;
            int newStart = this.start - prefix.length;
            System.arraycopy(prefix, 0, this.buf, newStart, prefix.length);
            return new PrependByteSequence(this.buf, newStart, false);
        }
        return seed(prefix, toByteArray());
    }

    public int size()
    {
        return this.buf.length - this.start;
    }

    public byte get(int index)
    {
        return this.buf[this.start + index];
    }

    public byte[] toByteArray()
    {
        byte[] out = new byte[size()];
        System.arraycopy(this.buf, this.start, out, 0, out.length);
        return out;
    }
}
