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

package org.finos.legend.pure.truffle.ast.natives.string;

import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.NodeInfo;
import org.finos.legend.pure.truffle.ast.PureNode;

@NodeInfo(shortName = "replace")
public final class ReplaceNode extends PureNode
{
    private static final String SIG = "replace_String_1__String_1__String_1__String_1_";

    @Child
    private PureNode strArg;

    @Child
    private PureNode toReplaceArg;

    @Child
    private PureNode replacementArg;

    public ReplaceNode(PureNode strArg, PureNode toReplaceArg, PureNode replacementArg)
    {
        this.strArg = strArg;
        this.toReplaceArg = toReplaceArg;
        this.replacementArg = replacementArg;
    }

    @Override
    public Object executeGeneric(VirtualFrame frame)
    {
        String s = StringHelper.asString(strArg.executeGeneric(frame), SIG);
        String toReplace = StringHelper.asString(toReplaceArg.executeGeneric(frame), SIG);
        String replacement = StringHelper.asString(replacementArg.executeGeneric(frame), SIG);
        return s.replace(toReplace, replacement);
    }
}
