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

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.NodeInfo;
import meta.pure.metamodel.multiplicity.Multiplicity;
import meta.pure.metamodel.type.generics.GenericType;
import org.finos.legend.pure.execution.PureValuePrinter;
import org.finos.legend.pure.truffle.ast.PureNode;
import org.finos.legend.pure.truffle.ast.natives.collection.CollectionHelper;

/**
 * {@code format(String[1], Any[*]) : String[1]} -- printf-style formatting
 * with Pure format specifiers (%s, %d, %r, %f, %t, etc.).
 */
@NodeInfo(shortName = "format")
public final class FormatNode extends PureNode
{
    @Child
    private PureNode formatArg;

    @Child
    private PureNode argsArg;

    private final GenericType genericType;
    private final Multiplicity multiplicity;

    public FormatNode(PureNode formatArg, PureNode argsArg, GenericType genericType, Multiplicity multiplicity)
    {
        this.formatArg = formatArg;
        this.argsArg = argsArg;
        this.genericType = genericType;
        this.multiplicity = multiplicity;
    }

    @Override
    public Object executeGeneric(VirtualFrame frame)
    {
        Object fmt = formatArg.executeGeneric(frame);
        Object args = argsArg.executeGeneric(frame);
        return doFormat(fmt, args);
    }

    @TruffleBoundary
    private static String doFormat(Object fmt, Object args)
    {
        String formatStr = StringHelper.asString(fmt, "format");
        int sz = CollectionHelper.size(args);
        Object[] rawArgs = new Object[sz];
        for (int i = 0; i < sz; i++)
        {
            Object item = CollectionHelper.at(args, i);
            // Normalize truffle types (PureSequence->List, AtomicValue->raw, PureNull->null)
            rawArgs[i] = org.finos.legend.pure.truffle.types.ValueNormalizer.normalize(item);
            if (rawArgs[i] == null)
            {
                rawArgs[i] = "";
            }
        }
        // Simple %s/%d replacement
        StringBuilder sb = new StringBuilder();
        int argIdx = 0;
        for (int i = 0; i < formatStr.length(); i++)
        {
            char c = formatStr.charAt(i);
            if (c == '%' && i + 1 < formatStr.length())
            {
                char next = formatStr.charAt(i + 1);
                if (next == '%')
                {
                    sb.append('%');
                    i++;
                }
                else if (argIdx < rawArgs.length)
                {
                    if (next == 's' || next == 'd' || next == 't')
                    {
                        sb.append(pureToString(rawArgs[argIdx++]));
                        i++;
                    }
                    else if (next == 'r')
                    {
                        sb.append(toRepresentation(rawArgs[argIdx++]));
                        i++;
                    }
                    else
                    {
                        sb.append(pureToString(rawArgs[argIdx++]));
                        i++;
                    }
                }
                else
                {
                    sb.append(c);
                }
            }
            else
            {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static String pureToString(Object v)
    {
        return ToStringNode.pureToString(v);
    }

    private static String toRepresentation(Object v)
    {
        if (v instanceof String s)
        {
            return "'" + s.replace("'", "\\'") + "'";
        }
        return pureToString(v);
    }
}
