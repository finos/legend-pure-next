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

@NodeInfo(shortName = "lengthStr")
public final class LengthStringNode extends PureNode
{
    private static final String SIG = "length_String_1__Integer_1_";

    @Child
    private PureNode arg;

    public LengthStringNode(PureNode arg)
    {
        this.arg = arg;
    }

    @Override
    public long executeLong(VirtualFrame frame)
    {
        String s = StringHelper.asString(arg.executeGeneric(frame), SIG);
        return s.length();
    }

    @Override
    public Object executeGeneric(VirtualFrame frame)
    {
        return executeLong(frame);
    }
}
