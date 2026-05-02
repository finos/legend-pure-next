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

/**
 * {@code unescapePureString(String[1]) : String[1]}.
 *
 * <p>Char-by-char unescape of Pure string-literal escape sequences. The Pure-side
 * chained-replace alternative is not self-host stable: each iteration of the
 * Pure compiler re-compiling itself distorts the patterns it stores for the
 * next iteration. This native mirrors {@code ValueSpecificationCompiler.unescapePureString}
 * on the Java bootstrap side so both runtimes produce identical String values.</p>
 */
@NodeInfo(shortName = "unescapePureString")
public final class UnescapePureStringNode extends PureNode
{
    private static final String SIG = "unescapePureString_String_1__String_1_";

    @Child
    private PureNode strArg;

    public UnescapePureStringNode(PureNode strArg)
    {
        this.strArg = strArg;
    }

    @Override
    public Object executeGeneric(VirtualFrame frame)
    {
        String s = StringHelper.asString(strArg.executeGeneric(frame), SIG);
        if (s.indexOf('\\') < 0)
        {
            return s;
        }
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++)
        {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length())
            {
                char next = s.charAt(i + 1);
                switch (next)
                {
                    case '\'': sb.append('\''); i++; break;
                    case '\\': sb.append('\\'); i++; break;
                    case 'n':  sb.append('\n'); i++; break;
                    case 't':  sb.append('\t'); i++; break;
                    case 'r':  sb.append('\r'); i++; break;
                    case 'u':
                        int hexStart = i + 2;
                        int hexEnd = hexStart;
                        while (hexEnd < s.length() && hexEnd - hexStart < 4
                                && Character.digit(s.charAt(hexEnd), 16) >= 0)
                        {
                            hexEnd++;
                        }
                        if (hexEnd > hexStart)
                        {
                            sb.append((char) Integer.parseInt(s.substring(hexStart, hexEnd), 16));
                            i = hexEnd - 1;
                        }
                        else
                        {
                            sb.append(c);
                        }
                        break;
                    default:   sb.append(c); break;
                }
            }
            else
            {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
