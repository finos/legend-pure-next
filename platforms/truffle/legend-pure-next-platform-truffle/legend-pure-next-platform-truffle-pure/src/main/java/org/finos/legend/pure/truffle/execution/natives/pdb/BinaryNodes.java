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

package org.finos.legend.pure.truffle.execution.natives.pdb;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.NodeInfo;
import java.nio.charset.StandardCharsets;
import org.finos.legend.pure.truffle.execution.ast.PureNode;
import org.finos.legend.pure.truffle.execution.natives.math.IntegerHelper;
import org.finos.legend.pure.truffle.execution.types.PureBinary;
import org.finos.legend.pure.truffle.execution.types.PureSequence;

/**
 * {@code meta::pure::functions::binary} on Truffle. A Binary is a host
 * {@code byte[]}; a Byte is a constrained extension of Integer and so is a
 * plain long, produced here unsigned 0..255 BY CONSTRUCTION — these natives
 * never evaluate the Byte constraint, only an explicit {@code cast(@Byte)}
 * does.
 */
public final class BinaryNodes
{
    private BinaryNodes()
    {
    }

    /** One-argument binary node: the argument is the buffer. */
    abstract static class Unary extends PureNode
    {
        @Child
        PureNode arg;

        Unary(PureNode arg)
        {
            this.arg = arg;
        }

        final byte[] buffer(VirtualFrame frame)
        {
            return PureBinary.asBytes(arg.executeGeneric(frame));
        }
    }

    @NodeInfo(shortName = "binarySize")
    public static final class Size extends Unary
    {
        public Size(PureNode arg)
        {
            super(arg);
        }

        @Override
        public Object executeGeneric(VirtualFrame frame)
        {
            // sizeOf, not buffer(): a Binary under construction is a
            // PrependByteSequence, and measuring must not materialise it.
            return (long) PureBinary.sizeOf(arg.executeGeneric(frame));
        }
    }

    @NodeInfo(shortName = "binaryToHex")
    public static final class ToHex extends Unary
    {
        public ToHex(PureNode arg)
        {
            super(arg);
        }

        @Override
        public Object executeGeneric(VirtualFrame frame)
        {
            return hex(buffer(frame));
        }

        @CompilerDirectives.TruffleBoundary
        private static String hex(byte[] b)
        {
            return PureBinary.toHex(b);
        }
    }

    @NodeInfo(shortName = "utf8BytesToString")
    public static final class Utf8ToString extends Unary
    {
        public Utf8ToString(PureNode arg)
        {
            super(arg);
        }

        @Override
        public Object executeGeneric(VirtualFrame frame)
        {
            return decode(buffer(frame));
        }

        @CompilerDirectives.TruffleBoundary
        private static String decode(byte[] b)
        {
            return new String(b, StandardCharsets.UTF_8);
        }
    }

    @NodeInfo(shortName = "leBytesToFloat")
    public static final class LeBytesToFloat extends Unary
    {
        public LeBytesToFloat(PureNode arg)
        {
            super(arg);
        }

        @Override
        public Object executeGeneric(VirtualFrame frame)
        {
            byte[] b = buffer(frame);
            long bits = 0;
            for (int i = 0; i < b.length && i < 8; i++)
            {
                bits |= (b[i] & 0xFFL) << (8 * i);
            }
            return Double.longBitsToDouble(bits);
        }
    }

    @NodeInfo(shortName = "binaryAt")
    public static final class At extends PureNode
    {
        private static final String SIG = "binaryAt_Binary_1__Integer_1__Byte_1_";

        @Child
        private PureNode binaryArg;

        @Child
        private PureNode indexArg;

        public At(PureNode binaryArg, PureNode indexArg)
        {
            this.binaryArg = binaryArg;
            this.indexArg = indexArg;
        }

        @Override
        public Object executeGeneric(VirtualFrame frame)
        {
            byte[] b = PureBinary.asBytes(binaryArg.executeGeneric(frame));
            long i = IntegerHelper.asLong(indexArg.executeGeneric(frame), SIG);
            if (i < 0 || i >= b.length)
            {
                throw PureBinary.outOfBounds("binaryAt", i, b.length);
            }
            return (long) (b[(int) i] & 0xFF);
        }
    }

    /** {@code binaryLeUInt/binaryLeInt} — the PDB reader's innermost loop. */
    @NodeInfo(shortName = "binaryLe")
    public static final class LeInt extends PureNode
    {
        private static final String SIG = "binaryLeUInt_Binary_1__Integer_1__Integer_1__Integer_1_";

        private final boolean signed;

        @Child
        private PureNode binaryArg;

        @Child
        private PureNode posArg;

        @Child
        private PureNode widthArg;

        public LeInt(PureNode binaryArg, PureNode posArg, PureNode widthArg, boolean signed)
        {
            this.binaryArg = binaryArg;
            this.posArg = posArg;
            this.widthArg = widthArg;
            this.signed = signed;
        }

        @Override
        public Object executeGeneric(VirtualFrame frame)
        {
            byte[] b = PureBinary.asBytes(binaryArg.executeGeneric(frame));
            int pos = (int) IntegerHelper.asLong(posArg.executeGeneric(frame), SIG);
            int width = (int) IntegerHelper.asLong(widthArg.executeGeneric(frame), SIG);
            return signed ? PureBinary.leInt(b, pos, width) : PureBinary.leUInt(b, pos, width);
        }
    }

    @NodeInfo(shortName = "binaryUtf8")
    public static final class Utf8Range extends PureNode
    {
        private static final String SIG = "binaryUtf8_Binary_1__Integer_1__Integer_1__String_1_";

        @Child
        private PureNode binaryArg;

        @Child
        private PureNode posArg;

        @Child
        private PureNode lenArg;

        public Utf8Range(PureNode binaryArg, PureNode posArg, PureNode lenArg)
        {
            this.binaryArg = binaryArg;
            this.posArg = posArg;
            this.lenArg = lenArg;
        }

        @Override
        public Object executeGeneric(VirtualFrame frame)
        {
            byte[] b = PureBinary.asBytes(binaryArg.executeGeneric(frame));
            int pos = (int) IntegerHelper.asLong(posArg.executeGeneric(frame), SIG);
            int len = (int) IntegerHelper.asLong(lenArg.executeGeneric(frame), SIG);
            return decode(b, pos, len);
        }

        @CompilerDirectives.TruffleBoundary
        private static String decode(byte[] b, int pos, int len)
        {
            return new String(b, pos, len, StandardCharsets.UTF_8);
        }
    }

    @NodeInfo(shortName = "binarySlice")
    public static final class Slice extends PureNode
    {
        private static final String SIG = "binarySlice_Binary_1__Integer_1__Integer_1__Binary_1_";

        @Child
        private PureNode binaryArg;

        @Child
        private PureNode fromArg;

        @Child
        private PureNode toArg;

        public Slice(PureNode binaryArg, PureNode fromArg, PureNode toArg)
        {
            this.binaryArg = binaryArg;
            this.fromArg = fromArg;
            this.toArg = toArg;
        }

        @Override
        public Object executeGeneric(VirtualFrame frame)
        {
            byte[] b = PureBinary.asBytes(binaryArg.executeGeneric(frame));
            long from = IntegerHelper.asLong(fromArg.executeGeneric(frame), SIG);
            long to = IntegerHelper.asLong(toArg.executeGeneric(frame), SIG);
            return PureBinary.slice(b, from, to);
        }
    }

    @NodeInfo(shortName = "binaryEquals")
    public static final class Equals extends PureNode
    {
        @Child
        private PureNode a;

        @Child
        private PureNode b;

        public Equals(PureNode a, PureNode b)
        {
            this.a = a;
            this.b = b;
        }

        @Override
        public Object executeGeneric(VirtualFrame frame)
        {
            return java.util.Arrays.equals(
                    PureBinary.asBytes(a.executeGeneric(frame)),
                    PureBinary.asBytes(b.executeGeneric(frame)));
        }
    }

    @NodeInfo(shortName = "binaryZeros")
    public static final class Zeros extends PureNode
    {
        private static final String SIG = "binaryZeros_Integer_1__Binary_1_";

        @Child
        private PureNode arg;

        public Zeros(PureNode arg)
        {
            this.arg = arg;
        }

        @Override
        public Object executeGeneric(VirtualFrame frame)
        {
            long n = IntegerHelper.asLong(arg.executeGeneric(frame), SIG);
            return PureBinary.zeros(n);
        }
    }

    /**
     * {@code binaryConcat(Binary[*])} — one pass, one allocation. The argument
     * is a collection, or a bare Binary when Pure lifted a single value.
     */
    @NodeInfo(shortName = "binaryConcat")
    public static final class Concat extends PureNode
    {
        @Child
        private PureNode arg;

        public Concat(PureNode arg)
        {
            this.arg = arg;
        }

        @Override
        public Object executeGeneric(VirtualFrame frame)
        {
            return join(arg.executeGeneric(frame));
        }

        @CompilerDirectives.TruffleBoundary
        private static Object join(Object raw)
        {
            if (PureBinary.isBinary(raw))
            {
                return raw;
            }
            java.util.List<Object> parts = new java.util.ArrayList<>();
            if (raw instanceof PureSequence seq)
            {
                parts.addAll(seq.toList());
            }
            else if (raw instanceof java.util.List<?> list)
            {
                parts.addAll(list);
            }
            else if (raw != null)
            {
                parts.add(raw);
            }
            // The PDB writer's `fbb::prepend` is exactly `[newBytes, buffer]`, and it
            // runs once per scalar, offset, pad byte, vtable and string in the
            // archive. Copying the buffer there is what made an 8.4MB write
            // quadratic, so that shape claims front reserve instead
            // (PrependByteSequence). Everything else concatenates plainly.
            if (parts.size() == 2 && PureBinary.isBinary(parts.get(0)) && PureBinary.isBinary(parts.get(1)))
            {
                byte[] head = PureBinary.asBytes(parts.get(0));
                Object tail = parts.get(1);
                return tail instanceof org.finos.legend.pure.truffle.execution.types.PrependByteSequence pbs
                        ? pbs.prepend(head)
                        : org.finos.legend.pure.truffle.execution.types.PrependByteSequence.seed(head, PureBinary.asBytes(tail));
            }
            return PureBinary.concat(parts);
        }
    }

    @NodeInfo(shortName = "intToLeBytes")
    public static final class IntToLe extends PureNode
    {
        private static final String SIG = "intToLeBytes_Integer_1__Integer_1__Binary_1_";

        @Child
        private PureNode valueArg;

        @Child
        private PureNode widthArg;

        public IntToLe(PureNode valueArg, PureNode widthArg)
        {
            this.valueArg = valueArg;
            this.widthArg = widthArg;
        }

        @Override
        public Object executeGeneric(VirtualFrame frame)
        {
            long value = IntegerHelper.asLong(valueArg.executeGeneric(frame), SIG);
            int width = (int) IntegerHelper.asLong(widthArg.executeGeneric(frame), SIG);
            return PureBinary.intToLe(value, width);
        }
    }

    @NodeInfo(shortName = "floatToLeBytes")
    public static final class FloatToLe extends PureNode
    {
        @Child
        private PureNode arg;

        public FloatToLe(PureNode arg)
        {
            this.arg = arg;
        }

        @Override
        public Object executeGeneric(VirtualFrame frame)
        {
            Object v = arg.executeGeneric(frame);
            double d = v instanceof Double dd ? dd : ((Number) v).doubleValue();
            return PureBinary.intToLe(Double.doubleToLongBits(d), 8);
        }
    }

    @NodeInfo(shortName = "stringToUtf8Bytes")
    public static final class StringToUtf8 extends PureNode
    {
        @Child
        private PureNode arg;

        public StringToUtf8(PureNode arg)
        {
            this.arg = arg;
        }

        @Override
        public Object executeGeneric(VirtualFrame frame)
        {
            return encode(String.valueOf(arg.executeGeneric(frame)));
        }

        @CompilerDirectives.TruffleBoundary
        private static byte[] encode(String s)
        {
            return s.getBytes(StandardCharsets.UTF_8);
        }
    }
}
