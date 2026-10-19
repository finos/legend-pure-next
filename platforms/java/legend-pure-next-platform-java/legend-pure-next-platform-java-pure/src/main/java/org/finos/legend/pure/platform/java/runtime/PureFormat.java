// ©2026 JP Morgan Chase & Co. All rights reserved.
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

package org.finos.legend.pure.platform.java.runtime;

import org.finos.legend.pure.platform.java.compiler.module.pdbModule.LazyObject;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.temporal.ChronoField;
import java.time.temporal.Temporal;
import java.time.temporal.TemporalAccessor;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Pure's {@code format} ({@code %s %d %f %.2f %05d %r %t{pattern}}, {@code %%}) and the {@code toString} /
 * {@code toRepresentation} forms it prints values with.
 */
public final class PureFormat
{
    private static final String PAIR = "platform::pure::functions::collection::Pair";
    private static final String LIST = "platform::pure::functions::collection::List";

    private PureFormat()
    {
    }

    public static String format(String formatStr, Object args)
    {
        List<?> list = asList(args);
        Object[] rawArgs = new Object[list.size()];
        for (int i = 0; i < rawArgs.length; i++)
        {
            rawArgs[i] = list.get(i) == null ? "" : list.get(i);
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
            if (next == 's' || next == 'd')
            {
                sb.append(str(rawArgs[argIdx++]));
                i++;
            }
            else if (next == 'f')
            {
                sb.append(formatFloat(rawArgs[argIdx++], -1));
                i++;
            }
            else if (next == 'r')
            {
                sb.append(repr(rawArgs[argIdx++]));
                i++;
            }
            else if (next == 't')
            {
                i++;
                int closeIdx = i + 1 < len && formatStr.charAt(i + 1) == '{' ? formatStr.indexOf('}', i + 2) : -1;
                if (closeIdx >= 0)
                {
                    sb.append(formatDate(rawArgs[argIdx++], formatStr.substring(i + 2, closeIdx)));
                    i = closeIdx;
                }
                else
                {
                    sb.append(str(rawArgs[argIdx++]));
                }
            }
            else if (next == '.' || isDigit(next))
            {
                int j = i + 1;
                while (j < len && (formatStr.charAt(j) == '.' || isDigit(formatStr.charAt(j))))
                {
                    j++;
                }
                if (j < len)
                {
                    char specChar = formatStr.charAt(j);
                    String specBody = formatStr.substring(i + 1, j);
                    if (specChar == 'f')
                    {
                        sb.append(formatFloat(rawArgs[argIdx++], specBody.startsWith(".") ? Integer.parseInt(specBody.substring(1)) : -1));
                    }
                    else if (specChar == 'd')
                    {
                        sb.append(formatInt(rawArgs[argIdx++], specBody));
                    }
                    else
                    {
                        sb.append(str(rawArgs[argIdx++]));
                    }
                    i = j;
                }
                else
                {
                    sb.append(str(rawArgs[argIdx++]));
                    i = j - 1;
                }
            }
            else
            {
                sb.append(str(rawArgs[argIdx++]));
                i++;
            }
        }
        return sb.toString();
    }

    static Object field(Object o, String name)
    {
        try
        {
            Field f = o.getClass().getField(name);
            f.setAccessible(true);
            return f.get(o);
        }
        catch (ReflectiveOperationException e)
        {
            return null;
        }
    }

    /** A value as {@code toString} prints it. */
    public static String str(Object v)
    {
        if (v == null)
        {
            return "";
        }
        if (v instanceof String)
        {
            return (String) v;
        }
        if (v instanceof Double)
        {
            return doubleString((Double) v);
        }
        if (v instanceof Map.Entry)
        {
            return "<" + str(((Map.Entry<?, ?>) v).getKey()) + ", " + str(((Map.Entry<?, ?>) v).getValue()) + ">";
        }
        if (v instanceof Enum)
        {
            return ((Enum<?>) v).name();
        }
        String purePath = v instanceof LazyObject ? ((LazyObject) v)._purePath() : "";
        if (purePath.equals(PAIR))
        {
            LazyObject pair = (LazyObject) v;
            return "<" + str(first(pair.__values("first"))) + ", " + str(first(pair.__values("second"))) + ">";
        }
        if (purePath.equals(LIST) || v instanceof List)
        {
            return listString(asList(purePath.equals(LIST) ? ((LazyObject) v).__values("values") : v));
        }
        if (v instanceof LazyObject)
        {
            LazyObject lo = (LazyObject) v;
            // A packageable element prints as its name (`STR_Person->toString()`); an instance that merely HAS a
            // name property does not, so the package decides.
            if (!lo.__values("package").isEmpty() && !lo.__values("name").isEmpty())
            {
                return String.valueOf(lo.__values("name").get(0));
            }
        }
        return String.valueOf(v);
    }

    /** A value as {@code toRepresentation} prints it: strings quoted, dates with their `%`, enum values by path. */
    static String repr(Object v)
    {
        if (v instanceof String)
        {
            return "'" + ((String) v).replace("\\", "\\\\").replace("'", "\\'") + "'";
        }
        if (v.getClass().getSimpleName().equals("PureDate") || v instanceof Temporal)
        {
            return "%" + v;
        }
        if (v instanceof Enum)
        {
            try
            {
                Method m = v.getClass().getMethod("_purePath");
                m.setAccessible(true);
                return m.invoke(v) + "." + ((Enum<?>) v).name();
            }
            catch (ReflectiveOperationException e)
            {
                return ((Enum<?>) v).name();
            }
        }
        return str(v);
    }

    static String formatFloat(Object v, int precision)
    {
        if (!(v instanceof Number))
        {
            return str(v);
        }
        if (precision >= 0)
        {
            return BigDecimal.valueOf(((Number) v).doubleValue()).setScale(precision, RoundingMode.HALF_DOWN).toPlainString();
        }
        return str(v instanceof Double ? v : (Object) ((Number) v).doubleValue());
    }

    static String formatInt(Object v, String spec)
    {
        if (!(v instanceof Number))
        {
            return str(v);
        }
        long val = ((Number) v).longValue();
        if (spec.startsWith("0") && spec.length() > 1)
        {
            int width = Integer.parseInt(spec.substring(1));
            StringBuilder digits = new StringBuilder(String.valueOf(Math.abs(val)));
            while (digits.length() < width)
            {
                digits.insert(0, '0');
            }
            return (val < 0 ? "-" : "") + digits;
        }
        return String.valueOf(val);
    }

    /** {@code %t{pattern}}: a date in a java.time pattern, `[zone]` in front converting a date-time to that zone. */
    static String formatDate(Object v, String pattern)
    {
        String dateStr = str(v);
        String tz = null;
        String actualPattern = pattern;
        if (actualPattern.startsWith("[") && actualPattern.indexOf(']') > 0)
        {
            tz = actualPattern.substring(1, actualPattern.indexOf(']'));
            actualPattern = actualPattern.substring(actualPattern.indexOf(']') + 1);
        }
        try
        {
            TemporalAccessor parsed;
            if (dateStr.contains("T"))
            {
                DateTimeFormatter parser = new DateTimeFormatterBuilder()
                        .appendPattern("yyyy-MM-dd'T'HH:mm:ss")
                        .optionalStart().appendPattern(".SSS").optionalEnd()
                        .optionalStart().appendPattern("XXX").optionalEnd()
                        .parseDefaulting(ChronoField.OFFSET_SECONDS, 0)
                        .toFormatter();
                ZonedDateTime zdt = ZonedDateTime.parse(dateStr, parser);
                parsed = tz == null ? zdt : zdt.withZoneSameInstant(ZoneId.of(tz, ZoneId.SHORT_IDS));
            }
            else
            {
                parsed = LocalDate.parse(dateStr, DateTimeFormatter.ofPattern("yyyy-MM-dd"));
            }
            return DateTimeFormatter.ofPattern(actualPattern.replace('"', '\'')).format(parsed);
        }
        catch (RuntimeException e)
        {
            return dateStr;
        }
    }

    private static String doubleString(double d)
    {
        if (Double.isNaN(d) || Double.isInfinite(d))
        {
            return Double.toString(d);
        }
        String s = new BigDecimal(Double.toString(d)).stripTrailingZeros().toPlainString();
        return s.contains(".") ? s : s + ".0";
    }

    private static String listString(List<?> elements)
    {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < elements.size(); i++)
        {
            if (i > 0)
            {
                sb.append(", ");
            }
            sb.append(str(elements.get(i)));
        }
        return sb.append(']').toString();
    }

    private static List<?> asList(Object v)
    {
        return v instanceof List ? (List<?>) v : v == null ? Collections.emptyList() : Collections.singletonList(v);
    }

    private static Object first(List<?> values)
    {
        return values.isEmpty() ? null : values.get(0);
    }

    private static boolean isDigit(char c)
    {
        return c >= '0' && c <= '9';
    }
}
