// Copyright 2026 Goldman Sachs
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

package org.finos.legend.pure.truffle.ast.natives.string;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.NodeInfo;
import org.finos.legend.pure.truffle.ast.PureNode;
import org.finos.legend.pure.truffle.types.ObjectSequence;

@NodeInfo(shortName = "chunk")
public final class ChunkNode extends PureNode
{
    private static final String SIG = "chunk_String_1__Integer_1__String_MANY_";

    @Child
    private PureNode strArg;

    @Child
    private PureNode sizeArg;

    public ChunkNode(PureNode strArg, PureNode sizeArg)
    {
        this.strArg = strArg;
        this.sizeArg = sizeArg;
    }

    @Override
    public Object executeGeneric(VirtualFrame frame)
    {
        String s = StringHelper.asString(strArg.executeGeneric(frame), SIG);
        int size = (int) sizeArg.executeLong(frame);
        return doChunk(s, size);
    }

    @CompilerDirectives.TruffleBoundary
    private static Object doChunk(String s, int size)
    {
        int len = s.length();
        if (len == 0)
        {
            return new ObjectSequence(new String[0]);
        }
        String[] parts = new String[(len + size - 1) / size];
        int idx = 0;
        for (int i = 0; i < len; i += size)
        {
            parts[idx++] = s.substring(i, Math.min(i + size, len));
        }
        return new ObjectSequence(parts);
    }
}
