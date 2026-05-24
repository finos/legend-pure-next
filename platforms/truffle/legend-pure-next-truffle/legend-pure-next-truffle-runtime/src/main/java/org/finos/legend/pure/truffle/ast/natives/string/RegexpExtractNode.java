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
import org.finos.legend.pure.truffle.types.ObjectSequence;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;

/**
 * {@code regexpExtract(string, regexp, extractAll, groupNumber, regexpParameters):String[*]}
 * — returns the substring(s) of group {@code groupNumber} (0 = the whole
 * match). When {@code extractAll=true}, every match contributes one element.
 */
@NodeInfo(shortName = "regexpExtract")
public final class RegexpExtractNode extends PureNode
{
    private static final String SIG = "regexpExtract_String_1__String_1__Boolean_1__Integer_1__RegexpParameter_$1_MANY$__String_MANY_";

    @Child
    private PureNode strArg;

    @Child
    private PureNode regexpArg;

    @Child
    private PureNode extractAllArg;

    @Child
    private PureNode groupArg;

    @Child
    private PureNode paramsArg;

    public RegexpExtractNode(PureNode strArg, PureNode regexpArg, PureNode extractAllArg,
                             PureNode groupArg, PureNode paramsArg)
    {
        this.strArg = strArg;
        this.regexpArg = regexpArg;
        this.extractAllArg = extractAllArg;
        this.groupArg = groupArg;
        this.paramsArg = paramsArg;
    }

    @Override
    public Object executeGeneric(VirtualFrame frame)
    {
        String s = StringHelper.asString(strArg.executeGeneric(frame), SIG);
        String regex = StringHelper.asString(regexpArg.executeGeneric(frame), SIG);
        boolean extractAll = asBoolean(extractAllArg.executeGeneric(frame));
        long group = asLong(groupArg.executeGeneric(frame));
        int flags = RegexpHelper.flagsFor(paramsArg.executeGeneric(frame));
        return extract(s, regex, extractAll, (int) group, flags);
    }

    private static boolean asBoolean(Object v)
    {
        if (v instanceof Boolean b) return b;
        if (v instanceof org.finos.legend.pure.truffle.types.PureSequence s && s.size() == 1) return asBoolean(s.getBoxed(0));
        return false;
    }

    private static long asLong(Object v)
    {
        if (v instanceof Long l) return l;
        if (v instanceof Number n) return n.longValue();
        if (v instanceof org.finos.legend.pure.truffle.types.PureSequence s && s.size() == 1) return asLong(s.getBoxed(0));
        return 0L;
    }

    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    private static Object extract(String s, String regex, boolean extractAll, int group, int flags)
    {
        Matcher m = RegexpHelper.compile(regex, flags).matcher(s);
        if (!extractAll)
        {
            if (!m.find()) return org.finos.legend.pure.truffle.types.PureSequence.EMPTY;
            String found = m.group(group);
            return found == null ? org.finos.legend.pure.truffle.types.PureSequence.EMPTY : new ObjectSequence(new Object[]{found});
        }
        List<Object> results = new ArrayList<>();
        while (m.find())
        {
            String g = m.group(group);
            if (g != null) results.add(g);
        }
        return new ObjectSequence(results.toArray());
    }
}
