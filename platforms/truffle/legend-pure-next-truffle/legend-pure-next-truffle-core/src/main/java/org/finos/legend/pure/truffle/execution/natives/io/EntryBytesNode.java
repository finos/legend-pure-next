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

/** {@code entryBytes(Integer[*], String[1]) : Integer[*]} — one entry, inflated. */
@NodeInfo(shortName = "entryBytes")
public final class EntryBytesNode extends PureNode
{
    private static final String SIG = "entryBytes_Binary_1__String_1__Binary_1_";

    @Child
    private PureNode archiveArg;
    @Child
    private PureNode nameArg;

    public EntryBytesNode(PureNode archiveArg, PureNode nameArg)
    {
        this.archiveArg = archiveArg;
        this.nameArg = nameArg;
    }

    @Override
    public Object executeGeneric(VirtualFrame frame)
    {
        Object archive = archiveArg.executeGeneric(frame);
        String name = StringHelper.asString(nameArg.executeGeneric(frame), SIG);
        return read(archive, name);
    }

    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    private static Object read(Object archive, String name)
    {
        return ArchiveHelper.entry(ArchiveHelper.bytes(archive), name);
    }
}
