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

import java.util.regex.Pattern;

/**
 * {@code matches(string:String[1], regexp:String[1]):Boolean[1]} —
 * full-string regex match. Equivalent to {@link Pattern#matches(String, CharSequence)}.
 */
@NodeInfo(shortName = "matches")
public final class MatchesNode extends PureNode
{
    private static final String SIG = "matches_String_1__String_1__Boolean_1_";

    @Child
    private PureNode strArg;

    @Child
    private PureNode regexpArg;

    public MatchesNode(PureNode strArg, PureNode regexpArg)
    {
        this.strArg = strArg;
        this.regexpArg = regexpArg;
    }

    @Override
    public boolean executeBoolean(VirtualFrame frame)
    {
        String s = StringHelper.asString(strArg.executeGeneric(frame), SIG);
        String regex = StringHelper.asString(regexpArg.executeGeneric(frame), SIG);
        return matches(s, regex);
    }

    @Override
    public Object executeGeneric(VirtualFrame frame)
    {
        return executeBoolean(frame);
    }

    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    private static boolean matches(String s, String regex)
    {
        return Pattern.matches(regex, s);
    }
}
