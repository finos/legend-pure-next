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
import org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.multiplicity.Multiplicity;
import org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.type.generics.GenericType;
import org.finos.legend.pure.truffle.ast.PureNode;
import org.finos.legend.pure.truffle.ast.natives.collection.CollectionHelper;
import org.finos.legend.pure.truffle.types.PureDate;

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
            rawArgs[i] = item;
            if (rawArgs[i] == null)
            {
                rawArgs[i] = "";
            }
        }
        StringBuilder sb = new StringBuilder();
        int argIdx = 0;
        int len = formatStr.length();
        for (int i = 0; i < len; i++)
        {
            char c = formatStr.charAt(i);
            if (c != '%' || i + 1 >= len)
            {
                sb.append(c);
                continue;
            }
            char next = formatStr.charAt(i + 1);
            if (next == '%')
            {
                sb.append('%');
                i++;
                continue;
            }
            if (argIdx >= rawArgs.length)
            {
                sb.append(c);
                continue;
            }

            // Parse format specifier after %
            if (next == 's' || next == 'd')
            {
                sb.append(pureToString(rawArgs[argIdx++]));
                i++;
            }
            else if (next == 'f')
            {
                sb.append(formatFloat(rawArgs[argIdx++], -1));
                i++;
            }
            else if (next == 'r')
            {
                sb.append(toRepresentation(rawArgs[argIdx++]));
                i++;
            }
            else if (next == 't')
            {
                // %t or %t{pattern}
                i++;
                if (i + 1 < len && formatStr.charAt(i + 1) == '{')
                {
                    int closeIdx = formatStr.indexOf('}', i + 2);
                    if (closeIdx >= 0)
                    {
                        String pattern = formatStr.substring(i + 2, closeIdx);
                        sb.append(formatDateWithPattern(rawArgs[argIdx++], pattern));
                        i = closeIdx;
                    }
                    else
                    {
                        sb.append(pureToString(rawArgs[argIdx++]));
                    }
                }
                else
                {
                    sb.append(pureToString(rawArgs[argIdx++]));
                }
            }
            else if (next == '.' || (next >= '0' && next <= '9'))
            {
                // Parse extended format: %05d, %.2f, etc.
                int specStart = i + 1;
                int j = specStart;
                while (j < len && (formatStr.charAt(j) == '.' || (formatStr.charAt(j) >= '0' && formatStr.charAt(j) <= '9')))
                {
                    j++;
                }
                if (j < len)
                {
                    char specChar = formatStr.charAt(j);
                    String specBody = formatStr.substring(specStart, j);
                    if (specChar == 'f')
                    {
                        int precision = -1;
                        if (specBody.startsWith("."))
                        {
                            precision = Integer.parseInt(specBody.substring(1));
                        }
                        sb.append(formatFloat(rawArgs[argIdx++], precision));
                        i = j;
                    }
                    else if (specChar == 'd')
                    {
                        sb.append(formatInt(rawArgs[argIdx++], specBody));
                        i = j;
                    }
                    else
                    {
                        sb.append(pureToString(rawArgs[argIdx++]));
                        i = j;
                    }
                }
                else
                {
                    sb.append(pureToString(rawArgs[argIdx++]));
                    i = j - 1;
                }
            }
            else
            {
                sb.append(pureToString(rawArgs[argIdx++]));
                i++;
            }
        }
        return sb.toString();
    }

    private static String formatFloat(Object v, int precision)
    {
        double d;
        if (v instanceof Double dd)
        {
            d = dd;
        }
        else if (v instanceof Float ff)
        {
            d = ff;
        }
        else if (v instanceof Number n)
        {
            d = n.doubleValue();
        }
        else
        {
            return pureToString(v);
        }
        if (precision >= 0)
        {
            // Use HALF_DOWN rounding to match Pure's behavior
            java.math.BigDecimal bd = java.math.BigDecimal.valueOf(d)
                    .setScale(precision, java.math.RoundingMode.HALF_DOWN);
            return bd.toPlainString();
        }
        return pureToString(v);
    }

    private static String formatInt(Object v, String spec)
    {
        long val;
        if (v instanceof Long l)
        {
            val = l;
        }
        else if (v instanceof Number n)
        {
            val = n.longValue();
        }
        else
        {
            return pureToString(v);
        }
        // Parse spec like "05" → width=5, zero-padded
        if (spec.startsWith("0") && spec.length() > 1)
        {
            int width = Integer.parseInt(spec.substring(1));
            String sign = val < 0 ? "-" : "";
            String digits = String.valueOf(Math.abs(val));
            while (digits.length() < width)
            {
                digits = "0" + digits;
            }
            return sign + digits;
        }
        return String.valueOf(val);
    }

    private static String formatDateWithPattern(Object v, String pattern)
    {
        String dateStr = v instanceof PureDate pd ? pd.dateString() : pureToString(v);
        // Parse the Pure date string and format with the given pattern
        // Handle optional timezone prefix: [EST]pattern, [CET]pattern
        String tz = null;
        String actualPattern = pattern;
        if (actualPattern.startsWith("["))
        {
            int closeIdx = actualPattern.indexOf(']');
            if (closeIdx > 0)
            {
                tz = actualPattern.substring(1, closeIdx);
                actualPattern = actualPattern.substring(closeIdx + 1);
            }
        }
        try
        {
            java.time.temporal.TemporalAccessor parsed;
            if (dateStr.contains("T"))
            {
                // DateTime — parse flexibly
                java.time.format.DateTimeFormatter parser = new java.time.format.DateTimeFormatterBuilder()
                        .appendPattern("yyyy-MM-dd'T'HH:mm:ss")
                        .optionalStart().appendPattern(".SSS").optionalEnd()
                        .optionalStart().appendPattern("XXX").optionalEnd()
                        .parseDefaulting(java.time.temporal.ChronoField.OFFSET_SECONDS, 0)
                        .toFormatter();
                java.time.ZonedDateTime zdt = java.time.ZonedDateTime.parse(dateStr, parser);
                if (tz != null)
                {
                    zdt = zdt.withZoneSameInstant(java.time.ZoneId.of(tz, java.time.ZoneId.SHORT_IDS));
                }
                parsed = zdt;
            }
            else
            {
                // StrictDate
                parsed = java.time.LocalDate.parse(dateStr, java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd"));
            }
            // Convert Pure's "literal" (double quotes) to Java's 'literal' (single quotes)
            String javaPattern = actualPattern.replace('"', '\'');
            return java.time.format.DateTimeFormatter.ofPattern(javaPattern).format(parsed);
        }
        catch (Exception e)
        {
            return dateStr;
        }
    }

    private static String pureToString(Object v)
    {
        return ToStringNode.pureToString(v);
    }

    private static String toRepresentation(Object v)
    {
        if (v instanceof PureDate pd)
        {
            return "%" + ToStringNode.normalizeDateString(pd.dateString());
        }
        if (v instanceof String s)
        {
            // Date strings get % prefix, not quote wrapping
            if (ToStringNode.isDateString(s))
            {
                return "%" + ToStringNode.normalizeDateString(s);
            }
            return "'" + s.replace("\\", "\\\\").replace("'", "\\'") + "'";
        }
        // Date representation: prefix with %
        String str = pureToString(v);
        if (ToStringNode.isDateString(str))
        {
            return "%" + str;
        }
        return str;
    }
}
