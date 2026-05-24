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
 * {@code regexpCount(string, regexp, regexpParameters):Integer[1]} —
 * non-overlapping match count.
 */
@NodeInfo(shortName = "regexpCount")
public final class RegexpCountNode extends PureNode
{
    private static final String SIG = "regexpCount_String_1__String_1__RegexpParameter_$1_MANY$__Integer_1_";

    @Child
    private PureNode strArg;

    @Child
    private PureNode regexpArg;

    @Child
    private PureNode paramsArg;

    public RegexpCountNode(PureNode strArg, PureNode regexpArg, PureNode paramsArg)
    {
        this.strArg = strArg;
        this.regexpArg = regexpArg;
        this.paramsArg = paramsArg;
    }

    @Override
    public long executeLong(VirtualFrame frame)
    {
        String s = StringHelper.asString(strArg.executeGeneric(frame), SIG);
        String regex = StringHelper.asString(regexpArg.executeGeneric(frame), SIG);
        int flags = RegexpHelper.flagsFor(paramsArg.executeGeneric(frame));
        return count(s, regex, flags);
    }

    @Override
    public Object executeGeneric(VirtualFrame frame)
    {
        return executeLong(frame);
    }

    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    private static long count(String s, String regex, int flags)
    {
        Matcher m = RegexpHelper.compile(regex, flags).matcher(s);
        long c = 0;
        while (m.find()) { c++; }
        return c;
    }
}
