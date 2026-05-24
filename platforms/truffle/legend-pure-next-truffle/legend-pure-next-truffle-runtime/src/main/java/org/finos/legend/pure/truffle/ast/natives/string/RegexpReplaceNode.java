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
 * {@code regexpReplace(string, regexp, replacement, replaceAll, regexpParameters):String[1]} —
 * {@code replaceAll=true} replaces every match; {@code false} replaces only the first.
 */
@NodeInfo(shortName = "regexpReplace")
public final class RegexpReplaceNode extends PureNode
{
    private static final String SIG = "regexpReplace_String_1__String_1__String_1__Boolean_1__RegexpParameter_$1_MANY$__String_1_";

    @Child
    private PureNode strArg;

    @Child
    private PureNode regexpArg;

    @Child
    private PureNode replacementArg;

    @Child
    private PureNode replaceAllArg;

    @Child
    private PureNode paramsArg;

    public RegexpReplaceNode(PureNode strArg, PureNode regexpArg, PureNode replacementArg,
                             PureNode replaceAllArg, PureNode paramsArg)
    {
        this.strArg = strArg;
        this.regexpArg = regexpArg;
        this.replacementArg = replacementArg;
        this.replaceAllArg = replaceAllArg;
        this.paramsArg = paramsArg;
    }

    @Override
    public Object executeGeneric(VirtualFrame frame)
    {
        String s = StringHelper.asString(strArg.executeGeneric(frame), SIG);
        String regex = StringHelper.asString(regexpArg.executeGeneric(frame), SIG);
        String replacement = StringHelper.asString(replacementArg.executeGeneric(frame), SIG);
        boolean replaceAll = asBoolean(replaceAllArg.executeGeneric(frame));
        int flags = RegexpHelper.flagsFor(paramsArg.executeGeneric(frame));
        return replace(s, regex, replacement, replaceAll, flags);
    }

    private static boolean asBoolean(Object v)
    {
        if (v instanceof Boolean b) return b;
        if (v instanceof org.finos.legend.pure.truffle.types.PureSequence s && s.size() == 1) return asBoolean(s.getBoxed(0));
        return false;
    }

    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    private static String replace(String s, String regex, String replacement, boolean replaceAll, int flags)
    {
        Matcher m = RegexpHelper.compile(regex, flags).matcher(s);
        return replaceAll ? m.replaceAll(replacement) : m.replaceFirst(replacement);
    }
}
