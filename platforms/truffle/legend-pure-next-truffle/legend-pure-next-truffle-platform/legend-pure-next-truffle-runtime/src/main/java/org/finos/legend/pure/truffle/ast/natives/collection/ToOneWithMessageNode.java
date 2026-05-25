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

package org.finos.legend.pure.truffle.ast.natives.collection;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.NodeInfo;
import org.finos.legend.pure.truffle.ast.PureNode;
import org.finos.legend.pure.truffle.ast.natives.string.StringHelper;

/**
 * {@code toOne(T[*], String[1]) : T[1]} — returns the single element, or
 * throws with the caller-supplied message if the argument has anything other
 * than exactly one value.
 */
@NodeInfo(shortName = "toOne")
public final class ToOneWithMessageNode extends PureNode
{
    private static final String SIG = "toOne_T_MANY__String_1__T_1_";

    @Child
    private PureNode arg;
    @Child
    private PureNode messageArg;

    public ToOneWithMessageNode(PureNode arg, PureNode messageArg)
    {
        this.arg = arg;
        this.messageArg = messageArg;
    }

    @Override
    public Object executeGeneric(VirtualFrame frame)
    {
        Object v = arg.executeGeneric(frame);
        int size = CollectionHelper.size(v);
        if (size != 1)
        {
            CompilerDirectives.transferToInterpreter();
            String msg = StringHelper.asString(messageArg.executeGeneric(frame), SIG);
            throw new org.finos.legend.pure.truffle.ast.PureException(msg, this);
        }
        return CollectionHelper.at(v, 0);
    }
}
