// Copyright 2024 Goldman Sachs
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

package org.finos.legend.pure.truffle.execution.natives.io;

import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.NodeInfo;
import org.finos.legend.pure.truffle.execution.ast.PureNode;
import org.finos.legend.pure.truffle.execution.natives.string.StringHelper;
import org.finos.legend.pure.truffle.execution.types.LongSequence;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * {@code readFileBytes(String[1]) : Integer[*]} — reads a file's raw bytes (each 0..255).
 */
@NodeInfo(shortName = "readFileBytes")
public final class ReadFileBytesNode extends PureNode
{
    private static final String SIG = "readFileBytes_String_1__Integer_MANY_";

    @Child
    private PureNode pathArg;

    public ReadFileBytesNode(PureNode pathArg)
    {
        this.pathArg = pathArg;
    }

    @Override
    public Object executeGeneric(VirtualFrame frame)
    {
        String path = StringHelper.asString(pathArg.executeGeneric(frame), SIG);
        return readFileBytes(path);
    }

    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    private static Object readFileBytes(String path)
    {
        try
        {
            byte[] content = Files.readAllBytes(Path.of(path));
            // LongSequence (unboxed), as the binary natives return byte lists.
            long[] bytes = new long[content.length];
            for (int i = 0; i < content.length; i++)
            {
                bytes[i] = content[i] & 0xFF;
            }
            return new LongSequence(bytes);
        }
        catch (IOException e)
        {
            throw new RuntimeException("Failed to read file: " + path, e);
        }
    }
}
