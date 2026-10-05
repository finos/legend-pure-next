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
 * The {@code Binary} primitive on Truffle — a host {@code byte[]}.
 *
 * <p>Pure carries a binary LITERAL (`0x1F8B08`) as its hex text on the
 * AtomicValue, which is what lets it round-trip through a .pdb with no wire
 * format change. The RUNTIME value is always a {@code byte[]}: the hex text is
 * decoded once, when the AST is lowered, so there is exactly one runtime
 * representation of a Binary. Contrast the date types, where the literal stays
 * a String and every date native re-parses it.</p>
 *
 * <p>Deliberately self-contained — Truffle does not depend on the bootstrap
 * interpreter, so the decoder lives here rather than being shared with it.</p>
 */
public final class PureBinary
{
    private PureBinary()
    {
    }

    /**
     * Decode the hex text of a binary literal. The grammar admits only whole
     * hex PAIRS (`0x1F8` is a lex error), so an odd length here would mean the
     * literal reached us from something other than the parser.
     */
    public static byte[] decodeHex(String hex)
    {
        int n = hex.length();
        if ((n & 1) != 0)
        {
            throw new IllegalArgumentException("Binary literal must have an even number of hex digits: 0x" + hex);
        }
        byte[] out = new byte[n / 2];
        for (int i = 0; i < out.length; i++)
        {
            out[i] = (byte) ((digit(hex.charAt(2 * i)) << 4) | digit(hex.charAt(2 * i + 1)));
        }
        return out;
    }

    /** Unsigned little-endian integer of {@code width} bytes at {@code pos}. */
    public static long leUInt(byte[] b, int pos, int width)
    {
        long acc = 0;
        for (int i = width - 1; i >= 0; i--)
        {
            acc = (acc * 256) + (b[pos + i] & 0xFF);
        }
        return acc;
    }

    /** Two's-complement little-endian integer: the top byte carries the sign. */
    public static long leInt(byte[] b, int pos, int width)
    {
        if (width == 0)
        {
            return 0;
        }
        long acc = b[pos + width - 1];
        for (int i = width - 2; i >= 0; i--)
        {
            acc = (acc * 256) + (b[pos + i] & 0xFF);
        }
        return acc;
    }

    private static final char[] HEX = "0123456789ABCDEF".toCharArray();

    public static String toHex(byte[] b)
    {
        char[] out = new char[b.length * 2];
        for (int i = 0; i < b.length; i++)
        {
            out[2 * i] = HEX[(b[i] >> 4) & 0xF];
            out[2 * i + 1] = HEX[b[i] & 0xF];
        }
        return new String(out);
    }

    /** Little-endian two's-complement bytes of {@code value} in {@code width} bytes. */
    public static byte[] intToLe(long value, int width)
    {
        byte[] out = new byte[width];
        long v = value;
        for (int i = 0; i < width; i++)
        {
            out[i] = (byte) (v & 0xFF);
            v >>= 8;
        }
        return out;
    }

    public static byte[] slice(byte[] b, long from, long to)
    {
        if (from < 0 || to > b.length || from > to)
        {
            throw new IllegalArgumentException(
                    "binarySlice [" + from + ", " + to + ") out of bounds for a Binary of " + b.length + " byte(s)");
        }
        return java.util.Arrays.copyOfRange(b, (int) from, (int) to);
    }

    public static byte[] zeros(long n)
    {
        if (n < 0)
        {
            throw new IllegalArgumentException("binaryZeros expects a non-negative count, got " + n);
        }
        return new byte[(int) n];
    }

    public static byte[] concat(java.util.List<Object> parts)
    {
        int total = 0;
        for (Object p : parts)
        {
            total += asBytes(p).length;
        }
        byte[] out = new byte[total];
        int at = 0;
        for (Object p : parts)
        {
            byte[] part = asBytes(p);
            System.arraycopy(part, 0, out, at, part.length);
            at += part.length;
        }
        return out;
    }

    /**
     * A Binary's bytes. Two representations reach here: a plain {@code byte[]},
     * and the front-reserved {@link PrependByteSequence} the PDB writer builds —
     * materialised on demand, which for a write is once, at the end.
     */
    public static byte[] asBytes(Object v)
    {
        if (v instanceof byte[] b)
        {
            return b;
        }
        if (v instanceof PrependByteSequence pbs)
        {
            return pbs.toByteArray();
        }
        throw new IllegalArgumentException(
                "Expected a Binary (byte[]), got " + (v == null ? "null" : v.getClass().getName()));
    }

    /** True for either Binary representation. */
    public static boolean isBinary(Object v)
    {
        return v instanceof byte[] || v instanceof PrependByteSequence;
    }

    /** A Binary's length WITHOUT materialising it — the writer measures constantly. */
    public static int sizeOf(Object v)
    {
        if (v instanceof byte[] b)
        {
            return b.length;
        }
        if (v instanceof PrependByteSequence pbs)
        {
            return pbs.size();
        }
        throw new IllegalArgumentException(
                "Expected a Binary, got " + (v == null ? "null" : v.getClass().getName()));
    }

    public static RuntimeException outOfBounds(String who, long i, int length)
    {
        return new IllegalArgumentException(
                who + " index " + i + " out of bounds for a Binary of " + length + " byte(s)");
    }

    private static int digit(char c)
    {
        int d = Character.digit(c, 16);
        if (d < 0)
        {
            throw new IllegalArgumentException("Not a hex digit in a Binary literal: '" + c + "'");
        }
        return d;
    }
}
