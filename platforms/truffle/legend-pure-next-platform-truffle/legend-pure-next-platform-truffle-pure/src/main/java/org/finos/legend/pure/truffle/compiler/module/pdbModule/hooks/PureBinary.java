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

package org.finos.legend.pure.truffle.compiler.module.pdbModule.hooks;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * The Binary functions the generated PDB reader calls — Truffle's own copies of the Java platform's runtime
 * library methods (platforms/java/…/runtime/PureBinary). Unsigned bytes, little-endian, as FlatBuffers is.
 */
public final class PureBinary
{
    private PureBinary()
    {
    }

    public static long at(byte[] bytes, long index)
    {
        if (index < 0 || index >= bytes.length)
        {
            throw new IllegalArgumentException("binaryAt index " + index + " out of bounds for a Binary of " + bytes.length + " byte(s)");
        }
        return bytes[(int) index] & 0xFF;
    }

    public static String utf8String(byte[] bytes, long pos, long length)
    {
        return new String(bytes, (int) pos, (int) length, StandardCharsets.UTF_8);
    }

    public static double leBytesToFloat(byte[] bytes)
    {
        long bits = 0L;
        for (int i = 0; i < bytes.length && i < 8; i++)
        {
            bits |= (bytes[i] & 0xFFL) << (8 * i);
        }
        return Double.longBitsToDouble(bits);
    }

    public static long leUInt(byte[] bytes, long pos, long width)
    {
        int p = (int) pos;
        long acc = 0;
        for (int i = (int) width - 1; i >= 0; i--)
        {
            acc = (acc * 256) + (bytes[p + i] & 0xFF);
        }
        return acc;
    }

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

    public static byte[] slice(byte[] bytes, long from, long to)
    {
        if (from < 0 || to > bytes.length || from > to)
        {
            throw new IllegalArgumentException("binarySlice [" + from + ", " + to + ") out of bounds for a Binary of " + bytes.length + " byte(s)");
        }
        return Arrays.copyOfRange(bytes, (int) from, (int) to);
    }
}
