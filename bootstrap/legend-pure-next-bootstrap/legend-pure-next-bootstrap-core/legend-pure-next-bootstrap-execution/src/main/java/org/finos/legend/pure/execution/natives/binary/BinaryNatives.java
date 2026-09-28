// Copyright (c) 2020-present, Goldman Sachs
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

package org.finos.legend.pure.execution.natives.binary;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import meta.pure.metamodel.valuespecification.ValueSpecification;
import org.finos.legend.pure.execution._E_ValueSpecification;
import org.finos.legend.pure.execution.natives.NativeRegistry.LazyNativeImpl;
import org.finos.legend.pure.execution.natives.NativeRegistry.NativeImpl;
import org.finos.legend.pure.m3.module.MetadataAccess;

/**
 * The {@code Binary} primitive — a host byte array — and the byte-level
 * primitives backing the self-hosted Pure PDB reader and writer.
 *
 * <p>Pure carries a binary LITERAL (`0x1F8B08`) as its hex text on the
 * AtomicValue, which is what lets it round-trip through a .pdb with no wire
 * format change. The RUNTIME value is always a {@code byte[]}: the hex text is
 * decoded once, at the AtomicValue funnel in
 * {@link _E_ValueSpecification#unwrap}, so there is exactly one runtime
 * representation of a Binary. Contrast the date types, where the literal stays
 * a String and every date native re-parses it.</p>
 *
 * <p>A {@code Byte} is a constrained extension of Integer
 * ({@code meta::pure::functions::binary::Byte}), so it is a plain long here and
 * feeds arithmetic with no conversion. These natives produce bytes that are
 * unsigned 0..255 BY CONSTRUCTION and therefore do not evaluate the
 * constraint — only an explicit {@code cast(@Byte)} does that.</p>
 */
public class BinaryNatives
{
    public static void register(Map<String, NativeImpl> natives,
                                Map<String, LazyNativeImpl> lazyNatives,
                                MetadataAccess resolver)
    {
        // binarySize(Binary[1]) : Integer[1] — O(1), the builder's write cursor.
        natives.put("binarySize_Binary_1__Integer_1_", (args, eval, genericType, multiplicity) ->
                _E_ValueSpecification.wrap((long) bytes(args.get(0)).length, genericType, multiplicity, resolver));

        // binaryAt(Binary[1], Integer[1]) : Byte[1] — O(1), the reader's inner
        // loop. Unsigned by construction, so no constraint evaluation.
        natives.put("binaryAt_Binary_1__Integer_1__Byte_1_", (args, eval, genericType, multiplicity) ->
        {
            byte[] b = bytes(args.get(0));
            int i = index(args.get(1), b.length, "binaryAt");
            return _E_ValueSpecification.wrap((long) (b[i] & 0xFF), genericType, multiplicity, resolver);
        });

        // binaryLeUInt / binaryLeInt — the reader's innermost loop.
        natives.put("binaryLeUInt_Binary_1__Integer_1__Integer_1__Integer_1_", (args, eval, genericType, multiplicity) ->
                _E_ValueSpecification.wrap(
                        leUInt(bytes(args.get(0)), (int) asLong(args.get(1)), (int) asLong(args.get(2))),
                        genericType, multiplicity, resolver));

        natives.put("binaryLeInt_Binary_1__Integer_1__Integer_1__Integer_1_", (args, eval, genericType, multiplicity) ->
                _E_ValueSpecification.wrap(
                        leInt(bytes(args.get(0)), (int) asLong(args.get(1)), (int) asLong(args.get(2))),
                        genericType, multiplicity, resolver));

        // binaryUtf8(Binary[1], Integer[1], Integer[1]) : String[1] — decode a
        // RANGE, so reading a string out of a buffer needs no slice first.
        natives.put("binaryUtf8_Binary_1__Integer_1__Integer_1__String_1_", (args, eval, genericType, multiplicity) ->
                _E_ValueSpecification.wrap(
                        new String(bytes(args.get(0)), (int) asLong(args.get(1)), (int) asLong(args.get(2)),
                                StandardCharsets.UTF_8),
                        genericType, multiplicity, resolver));

        // binarySlice(Binary[1], Integer[1], Integer[1]) : Binary[1] — half-open.
        natives.put("binarySlice_Binary_1__Integer_1__Integer_1__Binary_1_", (args, eval, genericType, multiplicity) ->
        {
            byte[] b = bytes(args.get(0));
            long from = asLong(args.get(1));
            long to = asLong(args.get(2));
            if (from < 0 || to > b.length || from > to)
            {
                throw new IllegalArgumentException(
                        "binarySlice [" + from + ", " + to + ") out of bounds for a Binary of " + b.length + " byte(s)");
            }
            return _E_ValueSpecification.wrap(
                    java.util.Arrays.copyOfRange(b, (int) from, (int) to), genericType, multiplicity, resolver);
        });

        // binaryConcat(Binary[*]) : Binary[1] — one pass, one allocation.
        natives.put("binaryConcat_Binary_MANY__Binary_1_", (args, eval, genericType, multiplicity) ->
        {
            Object raw = _E_ValueSpecification.unwrap(args.get(0));
            List<?> parts = raw instanceof List<?> l ? l : java.util.Collections.singletonList(raw);
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
            return _E_ValueSpecification.wrap(out, genericType, multiplicity, resolver);
        });

        // binaryZeros(Integer[1]) : Binary[1] — padding, one allocation.
        natives.put("binaryZeros_Integer_1__Binary_1_", (args, eval, genericType, multiplicity) ->
        {
            long n = asLong(args.get(0));
            if (n < 0)
            {
                throw new IllegalArgumentException("binaryZeros expects a non-negative count, got " + n);
            }
            return _E_ValueSpecification.wrap(new byte[(int) n], genericType, multiplicity, resolver);
        });

        // binaryEquals(Binary[1], Binary[1]) : Boolean[1] — content equality.
        natives.put("binaryEquals_Binary_1__Binary_1__Boolean_1_", (args, eval, genericType, multiplicity) ->
                _E_ValueSpecification.wrap(
                        java.util.Arrays.equals(bytes(args.get(0)), bytes(args.get(1))),
                        genericType, multiplicity, resolver));

        // binaryToHex(Binary[1]) : String[1] — uppercase, no `0x` prefix.
        natives.put("binaryToHex_Binary_1__String_1_", (args, eval, genericType, multiplicity) ->
                _E_ValueSpecification.wrap(toHex(bytes(args.get(0))), genericType, multiplicity, resolver));

        // intToLeBytes(Integer[1], Integer[1]) : Binary[1] — little-endian
        // two's complement in exactly `width` bytes.
        natives.put("intToLeBytes_Integer_1__Integer_1__Binary_1_", (args, eval, genericType, multiplicity) ->
        {
            long value = asLong(args.get(0));
            int width = (int) asLong(args.get(1));
            return _E_ValueSpecification.wrap(intToLe(value, width), genericType, multiplicity, resolver);
        });

        // floatToLeBytes(Float[1]) : Binary[1] — the 8 IEEE-754 bytes, exactly
        // what FlatBufferBuilder.addDouble writes for an fbs `double`.
        natives.put("floatToLeBytes_Float_1__Binary_1_", (args, eval, genericType, multiplicity) ->
        {
            double value = ((Number) _E_ValueSpecification.unwrap(args.get(0))).doubleValue();
            return _E_ValueSpecification.wrap(
                    intToLe(Double.doubleToLongBits(value), 8), genericType, multiplicity, resolver);
        });

        // leBytesToFloat(Binary[1]) : Float[1] — the inverse.
        natives.put("leBytesToFloat_Binary_1__Float_1_", (args, eval, genericType, multiplicity) ->
        {
            byte[] b = bytes(args.get(0));
            long bits = 0;
            for (int i = 0; i < b.length && i < 8; i++)
            {
                bits |= (b[i] & 0xFFL) << (8 * i);
            }
            return _E_ValueSpecification.wrap(Double.longBitsToDouble(bits), genericType, multiplicity, resolver);
        });

        // stringToUtf8Bytes(String[1]) : Binary[1].
        natives.put("stringToUtf8Bytes_String_1__Binary_1_", (args, eval, genericType, multiplicity) ->
        {
            String s = (String) _E_ValueSpecification.unwrap(args.get(0));
            return _E_ValueSpecification.wrap(
                    s.getBytes(StandardCharsets.UTF_8), genericType, multiplicity, resolver);
        });

        // utf8BytesToString(Binary[1]) : String[1] — the inverse.
        natives.put("utf8BytesToString_Binary_1__String_1_", (args, eval, genericType, multiplicity) ->
                _E_ValueSpecification.wrap(
                        new String(bytes(args.get(0)), StandardCharsets.UTF_8),
                        genericType, multiplicity, resolver));
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
        long acc = b[pos + width - 1]; // signed, so the sign propagates
        for (int i = width - 2; i >= 0; i--)
        {
            acc = (acc * 256) + (b[pos + i] & 0xFF);
        }
        return acc;
    }

    /**
     * Little-endian two's-complement bytes of {@code value} in exactly
     * {@code width} bytes.
     */
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

    private static int digit(char c)
    {
        int d = Character.digit(c, 16);
        if (d < 0)
        {
            throw new IllegalArgumentException("Not a hex digit in a Binary literal: '" + c + "'");
        }
        return d;
    }

    private static long asLong(ValueSpecification vs)
    {
        return ((Number) _E_ValueSpecification.unwrap(vs)).longValue();
    }

    private static int index(ValueSpecification vs, int length, String who)
    {
        long i = asLong(vs);
        if (i < 0 || i >= length)
        {
            throw new IllegalArgumentException(
                    who + " index " + i + " out of bounds for a Binary of " + length + " byte(s)");
        }
        return (int) i;
    }

    private static byte[] bytes(ValueSpecification vs)
    {
        return asBytes(_E_ValueSpecification.unwrap(vs));
    }

    private static byte[] asBytes(Object v)
    {
        if (v instanceof byte[] b)
        {
            return b;
        }
        throw new IllegalArgumentException(
                "Expected a Binary (byte[]), got " + (v == null ? "null" : v.getClass().getName()));
    }
}
