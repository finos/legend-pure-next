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

package org.finos.legend.pure.truffle.interpreter.ast.natives.binary;

import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.NodeInfo;
import org.finos.legend.pure.truffle.interpreter.ast.PureNode;
import org.finos.legend.pure.truffle.interpreter.ast.natives.collection.CollectionHelper;
import org.finos.legend.pure.truffle.interpreter.ast.natives.math.IntegerHelper;

/**
 * {@code leBytesToFloat(Integer[*]) : Float[1]} — decode 8 little-endian
 * bytes (each 0..255) as an IEEE-754 double; inverse of floatToLeBytes.
 * Byte-level primitive backing binary deserialization (the self-hosted PDB
 * reader).
 */
@NodeInfo(shortName = "leBytesToFloat")
public final class LeBytesToFloatNode extends PureNode
{
    private static final String SIG = "leBytesToFloat_Integer_MANY__Float_1_";

    @Child
    private PureNode bytesArg;

    public LeBytesToFloatNode(PureNode bytesArg)
    {
        this.bytesArg = bytesArg;
    }

    @Override
    public Object executeGeneric(VirtualFrame frame)
    {
        return decode(bytesArg.executeGeneric(frame));
    }

    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    private static Object decode(Object bytesSeq)
    {
        int n = CollectionHelper.size(bytesSeq);
        if (n != 8)
        {
            throw new IllegalArgumentException("leBytesToFloat expects exactly 8 bytes, got " + n);
        }
        long bits = 0L;
        for (int i = 7; i >= 0; i--)
        {
            bits = (bits << 8) | (IntegerHelper.asLong(CollectionHelper.at(bytesSeq, i), SIG) & 0xFFL);
        }
        return Double.longBitsToDouble(bits);
    }
}
