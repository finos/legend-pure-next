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

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;

/**
 * Pure's {@code Binary} functions over a byte[]. A byte read back is an Integer (a long), unsigned: 0x8B is 139,
 * not the JVM's signed -117. Little-endian, as FlatBuffers (the PDB format) is.
 */
public final class PureBinary
{
    private PureBinary()
    {
    }

    /** {@code stringToUtf8Bytes}. */
    public static byte[] utf8Bytes(String s)
    {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    /** {@code utf8BytesToString}: the inverse of {@link #utf8Bytes}. */
    public static String utf8String(byte[] bytes)
    {
        return new String(bytes, StandardCharsets.UTF_8);
    }

    /** {@code binaryUtf8(b, pos, length)}: decode a RANGE, no slice first. */
    public static String utf8String(byte[] bytes, long pos, long length)
    {
        return new String(bytes, (int) pos, (int) length, StandardCharsets.UTF_8);
    }

    /** {@code binarySize}: the number of bytes, O(1). */
    public static long size(byte[] bytes)
    {
        return bytes.length;
    }

    /** {@code binaryAt(b, i)}: the byte at a zero-based index, O(1). */
    public static long at(byte[] bytes, long index)
    {
        if (index < 0 || index >= bytes.length)
        {
            throw new IllegalArgumentException("binaryAt index " + index + " out of bounds for a Binary of " + bytes.length + " byte(s)");
        }
        return bytes[(int) index] & 0xFF;
    }

    /** {@code intToLeBytes(value, width)}: {@code width} little-endian two's-complement bytes. */
    public static byte[] intToLeBytes(long value, long width)
    {
        byte[] out = new byte[(int) width];
        for (int i = 0; i < out.length; i++)
        {
            out[i] = (byte) ((value >> (8 * i)) & 0xFF);
        }
        return out;
    }

    /**
     * {@code floatToLeBytes}: the 8 IEEE-754 bytes, little-endian — exactly what FlatBufferBuilder.addDouble
     * writes for an fbs {@code double}. Logical shift, since the sign bit is payload here, not arithmetic.
     */
    public static byte[] floatToLeBytes(double value)
    {
        long bits = Double.doubleToLongBits(value);
        byte[] out = new byte[8];
        for (int i = 0; i < 8; i++)
        {
            out[i] = (byte) ((bits >>> (8 * i)) & 0xFF);
        }
        return out;
    }

    /** {@code leBytesToFloat}: the inverse of {@link #floatToLeBytes}. */
    public static double leBytesToFloat(byte[] bytes)
    {
        long bits = 0L;
        for (int i = 0; i < bytes.length && i < 8; i++)
        {
            bits |= (bytes[i] & 0xFFL) << (8 * i);
        }
        return Double.longBitsToDouble(bits);
    }

    /** {@code binaryLeUInt(b, pos, width)}: the PDB reader's innermost loop. */
    public static long leUInt(byte[] bytes, long pos, long width)
    {
        int p = (int) pos;
        int w = (int) width;
        long acc = 0;
        for (int i = w - 1; i >= 0; i--)
        {
            acc = (acc * 256) + (bytes[p + i] & 0xFF);
        }
        return acc;
    }

    /** {@code binaryLeInt(b, pos, width)}: as {@link #leUInt}, the top byte signed. */
    public static long leInt(byte[] bytes, long pos, long width)
    {
        int p = (int) pos;
        int w = (int) width;
        if (w == 0)
        {
            return 0L;
        }
        long acc = bytes[p + w - 1];
        for (int i = w - 2; i >= 0; i--)
        {
            acc = (acc * 256) + (bytes[p + i] & 0xFF);
        }
        return acc;
    }

    /** {@code binarySlice(b, from, toExclusive)}: a half-open range as a new Binary. */
    public static byte[] slice(byte[] bytes, long from, long to)
    {
        if (from < 0 || to > bytes.length || from > to)
        {
            throw new IllegalArgumentException("binarySlice [" + from + ", " + to + ") out of bounds for a Binary of " + bytes.length + " byte(s)");
        }
        return Arrays.copyOfRange(bytes, (int) from, (int) to);
    }

    /** {@code binaryConcat(parts)}: one pass, one allocation. */
    public static byte[] concat(List<?> parts)
    {
        int total = 0;
        for (Object part : parts)
        {
            total += ((byte[]) part).length;
        }
        byte[] out = new byte[total];
        int at = 0;
        for (Object part : parts)
        {
            byte[] bytes = (byte[]) part;
            System.arraycopy(bytes, 0, out, at, bytes.length);
            at += bytes.length;
        }
        return out;
    }

    /** {@code binaryZeros(n)}: padding, one allocation. */
    public static byte[] zeros(long count)
    {
        if (count < 0)
        {
            throw new IllegalArgumentException("binaryZeros expects a non-negative count, got " + count);
        }
        return new byte[(int) count];
    }

    /** {@code binaryToHex}: uppercase hex, no {@code 0x} prefix. */
    public static String toHex(byte[] bytes)
    {
        return HexFormat.of().withUpperCase().formatHex(bytes);
    }

    /** A Binary literal, written in the generated code as its hex. */
    public static byte[] fromHex(String hex)
    {
        return HexFormat.of().parseHex(hex);
    }
}
