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
import org.finos.legend.pure.truffle.interpreter.ast.natives.math.IntegerHelper;
import org.finos.legend.pure.truffle.types.LongSequence;

/**
 * {@code intToLeBytes(Integer[1], Integer[1]) : Integer[*]} — little-endian
 * two's-complement bytes (each 0..255) of a signed integer in exactly
 * {@code width} bytes. Byte-level primitive backing binary serialization
 * (the self-hosted PDB writer); byte-identity across platforms is the contract.
 */
@NodeInfo(shortName = "intToLeBytes")
public final class IntToLeBytesNode extends PureNode
{
    private static final String SIG = "intToLeBytes_Integer_1__Integer_1__Integer_MANY_";

    @Child
    private PureNode valueArg;

    @Child
    private PureNode widthArg;

    public IntToLeBytesNode(PureNode valueArg, PureNode widthArg)
    {
        this.valueArg = valueArg;
        this.widthArg = widthArg;
    }

    @Override
    public Object executeGeneric(VirtualFrame frame)
    {
        long value = IntegerHelper.asLong(valueArg.executeGeneric(frame), SIG);
        int width = (int) IntegerHelper.asLong(widthArg.executeGeneric(frame), SIG);
        // LongSequence (unboxed) so downstream concatenate/take/drop stay on
        // their long[] fast paths — byte assemblies are this native's use.
        long[] bytes = new long[width];
        long v = value;
        for (int i = 0; i < width; i++)
        {
            bytes[i] = v & 0xFFL;
            v >>= 8;
        }
        return new LongSequence(bytes);
    }
}
