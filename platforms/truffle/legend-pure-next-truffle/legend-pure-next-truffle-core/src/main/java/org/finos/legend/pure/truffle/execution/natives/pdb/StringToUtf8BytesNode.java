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

import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.NodeInfo;
import org.finos.legend.pure.truffle.execution.ast.PureNode;
import org.finos.legend.pure.truffle.execution.natives.string.StringHelper;
import org.finos.legend.pure.truffle.execution.types.LongSequence;

import java.nio.charset.StandardCharsets;

/**
 * {@code stringToUtf8Bytes(String[1]) : Integer[*]} — UTF-8 bytes (each
 * 0..255) of a string. Byte-level primitive backing binary serialization
 * (the self-hosted PDB writer); byte-identity across platforms is the contract.
 */
@NodeInfo(shortName = "stringToUtf8Bytes")
public final class StringToUtf8BytesNode extends PureNode
{
    private static final String SIG = "stringToUtf8Bytes_String_1__Integer_MANY_";

    @Child
    private PureNode strArg;

    public StringToUtf8BytesNode(PureNode strArg)
    {
        this.strArg = strArg;
    }

    @Override
    public Object executeGeneric(VirtualFrame frame)
    {
        String s = StringHelper.asString(strArg.executeGeneric(frame), SIG);
        return toUtf8(s);
    }

    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    private static Object toUtf8(String s)
    {
        byte[] utf8 = s.getBytes(StandardCharsets.UTF_8);
        // LongSequence (unboxed) so downstream concatenate/take/drop stay on
        // their long[] fast paths — byte assemblies are this native's use.
        long[] bytes = new long[utf8.length];
        for (int i = 0; i < utf8.length; i++)
        {
            bytes[i] = utf8[i] & 0xFF;
        }
        return new LongSequence(bytes);
    }
}
