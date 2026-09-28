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
import org.finos.legend.pure.truffle.execution.types.ObjectSequence;

/** {@code entryNames(Integer[*]) : String[*]} — every entry of a .pdb archive. */
@NodeInfo(shortName = "entryNames")
public final class EntryNamesNode extends PureNode
{
    @Child
    private PureNode archiveArg;

    public EntryNamesNode(PureNode archiveArg)
    {
        this.archiveArg = archiveArg;
    }

    @Override
    public Object executeGeneric(VirtualFrame frame)
    {
        return names(archiveArg.executeGeneric(frame));
    }

    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    private static Object names(Object archive)
    {
        return new ObjectSequence(ArchiveHelper.names(ArchiveHelper.bytes(archive)).toArray());
    }
}
