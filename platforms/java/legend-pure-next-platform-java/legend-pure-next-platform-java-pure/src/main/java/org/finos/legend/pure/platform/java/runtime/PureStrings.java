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

import java.math.BigDecimal;
import java.time.temporal.Temporal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure's string functions translated code calls: {@code toString}, {@code toRepresentation} and {@code print}'s
 * forms of a value (as the JS runtime prints them), {@code chunk}, and the regexp functions.
 */
public final class PureStrings
{
    private PureStrings()
    {
    }

    /** {@code toString}: a Float as Pure prints one, a Pair as {@code <first, second>}, an element as its name. */
    public static String toString(Object v)
    {
        String number = numberString(v);
        if (number != null)
        {
            return number;
        }
        if (v instanceof Map.Entry)
        {
            return "<" + ((Map.Entry<?, ?>) v).getKey() + ", " + ((Map.Entry<?, ?>) v).getValue() + ">";
        }
        String name = elementName(v);
        return name != null ? name : String.valueOf(v);
    }

    /**
     * {@code toRepresentation}: Pure literal syntax, as the JS runtime's __toRepresentation — a String
     * single-quoted with \ ' newline tab and CR escaped, a date with its `%`, a Float as Pure prints one.
     */
    public static String toRepresentation(Object v)
    {
        if (v == null)
        {
            return "";
        }
        if (v instanceof String)
        {
            return "'" + ((String) v).replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n").replace("\t", "\\t").replace("\r", "\\r") + "'";
        }
        if (v instanceof Temporal || v.getClass().getSimpleName().equals("PureDate"))
        {
            return "%" + v;
        }
        String number = numberString(v);
        return number != null ? number : String.valueOf(v);
    }

    /**
     * {@code print(value)}: written to standard output, and returned. The format follows the JS runtime's
     * __pureFormat: a collection prints one element per line (empty prints nothing), a String prints bare at the
     * top level and quoted when nested, a Float as Pure prints one, and an instance as its class name followed
     * by one indented {@code property: value} line per non-empty property.
     */
    public static String print(Object v)
    {
        return PureOut.write(printed(v, 0));
    }

    private static String printed(Object v, int depth)
    {
        if (v == null)
        {
            return "";
        }
        if (v instanceof List)
        {
            List<String> parts = new ArrayList<>();
            for (Object e : (List<?>) v)
            {
                parts.add(printed(e, depth));
            }
            return String.join("\n", parts);
        }
        if (v instanceof String)
        {
            return depth == 0 ? (String) v : "'" + v + "'";
        }
        String number = numberString(v);
        if (number != null)
        {
            return number;
        }
        if (v instanceof org.finos.legend.pure.platform.java.compiler.module.pdbModule.PureEnumConstant constant)
        {
            return constant.name();
        }
        if (v instanceof Enum)
        {
            return ((Enum<?>) v).name();
        }
        if (v instanceof LazyObject)
        {
            return printedInstance((LazyObject) v, depth);
        }
        return String.valueOf(v);
    }

    private static String printedInstance(LazyObject lo, int depth)
    {
        String path = lo._purePath();
        StringBuilder sb = new StringBuilder(path.substring(path.lastIndexOf(':') + 1));
        for (String pn : lo._propertyNames())
        {
            List<Object> fvs = pn.equals("classifierGenericType") || pn.equals("elementOverride") ? List.of() : lo.__values(pn);
            if (!fvs.isEmpty())
            {
                Object fv = fvs.size() == 1 ? fvs.get(0) : fvs;
                sb.append('\n').append("  ".repeat(depth + 1)).append(pn).append(": ").append(printed(fv, depth + 1));
            }
        }
        return sb.toString();
    }

    /**
     * A Decimal in plain notation; a Float as Pure prints one, matching the JS runtime's __floatStr: an
     * integral value keeps one fractional digit (134210000.0), an exponent form is expanded to plain notation
     * (0.000000013421, never 1.3421E-8), and NaN / Infinity print as-is (BigDecimal would throw on them).
     * Null for anything else.
     */
    static String numberString(Object v)
    {
        if (v instanceof BigDecimal)
        {
            return ((BigDecimal) v).toPlainString();
        }
        if (!(v instanceof Double))
        {
            return null;
        }
        double d = (Double) v;
        if (Double.isNaN(d) || Double.isInfinite(d))
        {
            return Double.toString(d);
        }
        String s = new BigDecimal(Double.toString(d)).stripTrailingZeros().toPlainString();
        return s.contains(".") ? s : s + ".0";
    }

    /**
     * A packageable element prints as its name: {@code STR_Person->toString()} is 'STR_Person', not the debug
     * form an object prints. The package tells an element apart from an instance that merely has a name.
     */
    private static String elementName(Object v)
    {
        if (v instanceof LazyObject && !((LazyObject) v).__values("package").isEmpty() && !((LazyObject) v).__values("name").isEmpty())
        {
            return String.valueOf(((LazyObject) v).__values("name").get(0));
        }
        return null;
    }

    /** {@code chunk(s, size)}: consecutive pieces of {@code size} characters, the last possibly shorter. */
    public static List<String> chunk(String s, long size)
    {
        int sz = (int) size;
        List<String> out = new ArrayList<>();
        for (int i = 0; i < s.length(); i += sz)
        {
            out.add(s.substring(i, Math.min(i + sz, s.length())));
        }
        return out;
    }

    /** {@code regexpIndexOf(s, regex[, group])}: where the first match (of {@code group}) starts, or -1. */
    public static long regexpIndexOf(String s, String regex, long group)
    {
        Matcher m = Pattern.compile(regex).matcher(s);
        return m.find() ? m.start((int) group) : -1;
    }

    /** {@code regexpCount(s, regex[, flags])}: the number of non-overlapping matches. */
    public static long regexpCount(String s, String regex, int flags)
    {
        Matcher m = Pattern.compile(regex, flags).matcher(s);
        long count = 0;
        while (m.find())
        {
            count++;
        }
        return count;
    }

    /** {@code regexpExtract(s, regex, extractAll[, group])}: every match (of {@code group}), or the first one. */
    public static List<String> regexpExtract(String s, String regex, boolean extractAll, long group)
    {
        Matcher m = Pattern.compile(regex).matcher(s);
        List<String> out = new ArrayList<>();
        if (extractAll)
        {
            while (m.find())
            {
                out.add(m.group((int) group));
            }
        }
        else if (m.find())
        {
            out.add(m.group((int) group));
        }
        return out;
    }
}
