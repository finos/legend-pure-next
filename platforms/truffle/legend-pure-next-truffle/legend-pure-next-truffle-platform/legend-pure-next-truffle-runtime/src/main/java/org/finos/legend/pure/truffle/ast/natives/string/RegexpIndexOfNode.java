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

import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.NodeInfo;
import org.finos.legend.pure.truffle.ast.PureNode;

import java.util.regex.Matcher;

/**
 * {@code regexpIndexOf(string, regexp, groupNumber, regexpParameters):Integer[1]} —
 * position of the first match (or specified group), or {@code -1} when no match.
 */
@NodeInfo(shortName = "regexpIndexOf")
public final class RegexpIndexOfNode extends PureNode
{
    private static final String SIG = "regexpIndexOf_String_1__String_1__Integer_1__RegexpParameter_$1_MANY$__Integer_1_";

    @Child
    private PureNode strArg;

    @Child
    private PureNode regexpArg;

    @Child
    private PureNode groupArg;

    @Child
    private PureNode paramsArg;

    public RegexpIndexOfNode(PureNode strArg, PureNode regexpArg, PureNode groupArg, PureNode paramsArg)
    {
        this.strArg = strArg;
        this.regexpArg = regexpArg;
        this.groupArg = groupArg;
        this.paramsArg = paramsArg;
    }

    @Override
    public long executeLong(VirtualFrame frame)
    {
        String s = StringHelper.asString(strArg.executeGeneric(frame), SIG);
        String regex = StringHelper.asString(regexpArg.executeGeneric(frame), SIG);
        long group = asLong(groupArg.executeGeneric(frame));
        int flags = RegexpHelper.flagsFor(paramsArg.executeGeneric(frame));
        return indexOf(s, regex, (int) group, flags);
    }

    @Override
    public Object executeGeneric(VirtualFrame frame)
    {
        return executeLong(frame);
    }

    private static long asLong(Object v)
    {
        if (v instanceof Long l) return l;
        if (v instanceof Number n) return n.longValue();
        if (v instanceof org.finos.legend.pure.truffle.types.PureSequence s && s.size() == 1) return asLong(s.getBoxed(0));
        return 0L;
    }

    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    private static long indexOf(String s, String regex, int group, int flags)
    {
        Matcher m = RegexpHelper.compile(regex, flags).matcher(s);
        if (!m.find()) return -1L;
        return m.start(group);
    }
}
