// Copyright 2024 Goldman Sachs
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

package org.finos.legend.pure.truffle.ast.natives.io;

import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.NodeInfo;
import org.finos.legend.pure.truffle.ast.PureNode;
import org.finos.legend.pure.truffle.ast.natives.string.StringHelper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * {@code readFile(String[1]) : String[1]} — reads the contents of a file.
 */
@NodeInfo(shortName = "readFile")
public final class ReadFileNode extends PureNode
{
    private static final String SIG = "readFile_String_1__String_1_";

    @Child
    private PureNode pathArg;

    public ReadFileNode(PureNode pathArg)
    {
        this.pathArg = pathArg;
    }

    @Override
    public Object executeGeneric(VirtualFrame frame)
    {
        String path = StringHelper.asString(pathArg.executeGeneric(frame), SIG);
        return readFile(path);
    }

    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    private static String readFile(String path)
    {
        try
        {
            return Files.readString(Path.of(path));
        }
        catch (IOException e)
        {
            throw new RuntimeException("Failed to read file: " + path, e);
        }
    }
}
