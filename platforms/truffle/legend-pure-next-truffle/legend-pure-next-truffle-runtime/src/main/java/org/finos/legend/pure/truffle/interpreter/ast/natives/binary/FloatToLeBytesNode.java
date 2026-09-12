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
import org.finos.legend.pure.truffle.types.LongSequence;

/**
 * {@code floatToLeBytes(Float[1]) : Integer[*]} — IEEE-754 double bits of a
 * Float, little-endian (each 0..255): exactly the 8 bytes
 * {@code FlatBufferBuilder.addDouble} writes for an fbs {@code double}.
 */
@NodeInfo(shortName = "floatToLeBytes")
public final class FloatToLeBytesNode extends PureNode
{
    private static final String SIG = "floatToLeBytes_Float_1__Integer_MANY_";

    @Child
    private PureNode valueArg;

    public FloatToLeBytesNode(PureNode valueArg)
    {
        this.valueArg = valueArg;
    }

    @Override
    public Object executeGeneric(VirtualFrame frame)
    {
        Object raw = valueArg.executeGeneric(frame);
        if (!(raw instanceof Number n))
        {
            throw new ClassCastException(SIG + " expected Float, got: " + (raw == null ? "null" : raw.getClass().getName()));
        }
        long bits = Double.doubleToLongBits(n.doubleValue());
        // LongSequence (unboxed) — see IntToLeBytesNode.
        long[] bytes = new long[8];
        for (int i = 0; i < 8; i++)
        {
            bytes[i] = (bits >>> (8 * i)) & 0xFFL;
        }
        return new LongSequence(bytes);
    }
}
