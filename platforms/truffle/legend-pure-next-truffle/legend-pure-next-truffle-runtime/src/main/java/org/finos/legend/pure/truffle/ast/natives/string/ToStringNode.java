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
import org.finos.legend.pure.truffle.ast.PureNode;
import org.finos.legend.pure.truffle.types.PureDate;

@NodeInfo(shortName = "toStr")
public final class ToStringNode extends PureNode
{
    @Child
    private PureNode arg;

    @Child
    private org.finos.legend.pure.truffle.ast.PropertyReadNode toStringReader = new org.finos.legend.pure.truffle.ast.PropertyReadNode();

    private final Object genericType;
    private final Object multiplicity;

    public ToStringNode(PureNode arg, Object genericType, Object multiplicity)
    {
        this.arg = arg;
        this.genericType = genericType;
        this.multiplicity = multiplicity;
    }

    @Override
    public Object executeGeneric(VirtualFrame frame)
    {
        Object v = arg.executeGeneric(frame);
        return convert(v, toStringReader);
    }

    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    private static String convert(Object v, org.finos.legend.pure.truffle.ast.PropertyReadNode reader)
    {
        if (v == null)
        {
            return "";
        }
        return pureToString(v, reader);
    }

    static String pureToString(Object v)
    {
        return pureToString(v, null);
    }

    /**
     * Pure-source representation of an enum value: "<EnumerationPath>.<ValueName>"
     * (e.g. "meta::pure::...::LA_GeographicEntityType.CITY"). Returns null when
     * {@code v} isn't recognisable as a Pure enum value — caller should fall
     * back to the regular toString form. Used by {@code %r} (toRepresentation)
     * since enum values in Pure source are always written in qualified form.
     */
    @TruffleBoundary
    static String enumQualifiedPath(Object v)
    {
        if (v == null) return null;
        String pureType = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.pureTypeOf(v);
        if (v instanceof java.lang.Enum<?> javaEnum && pureType != null && pureType.endsWith("Enum"))
        {
            return pureType.substring(0, pureType.length() - "Enum".length()) + "." + javaEnum.name();
        }
        if ("meta::pure::metamodel::type::Enum".equals(pureType))
        {
            Object name = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.read(v, "name");
            if (name instanceof String s)
            {
                Object cgt = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.read(v, "classifierGenericType");
                if (cgt != null)
                {
                    Object enumType = org.finos.legend.pure.truffle.runtime.helper._GenericType.type(cgt);
                    if (enumType != null)
                    {
                        var resolver = org.finos.legend.pure.truffle.PureLanguage.get(null).resolver();
                        String enumPath = org.finos.legend.pure.truffle.runtime.helper._PackageableElement.path(enumType, resolver);
                        if (enumPath != null && !enumPath.isEmpty())
                        {
                            return enumPath + "." + s;
                        }
                    }
                }
                return s;
            }
        }
        return null;
    }

    // @TruffleBoundary — recursive over arbitrary structure with substring,
    // StringBuilder, and reflection (toString-QP dispatch). Same JDK
    // inlining-budget hazard as normalizeDateString. Cheap to call across
    // a boundary; not on a tight inner loop.
    @TruffleBoundary
    static String pureToString(Object v, org.finos.legend.pure.truffle.ast.PropertyReadNode reader)
    {
        if (v == null)
        {
            return "";
        }
        if (v instanceof PureDate pd)
        {
            return normalizeDateString(pd.dateString());
        }
        String pureType = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.pureTypeOf(v);
        // Pure's `toString` of an enum value returns just the value name
        // (e.g. "CITY"), matching the standard convention. Callers wanting
        // the fully-qualified "<EnumerationPath>.<ValueName>" form (the
        // toRepresentation/%r convention) must use enumQualifiedPath below.
        if (v instanceof java.lang.Enum<?> javaEnum && pureType != null && pureType.endsWith("Enum"))
        {
            return javaEnum.name();
        }
        if ("meta::pure::metamodel::type::Enum".equals(pureType))
        {
            Object name = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.read(v, "name");
            if (name instanceof String s)
            {
                return s;
            }
        }
        // List — [a, b, c]
        if ("meta::pure::functions::collection::List".equals(pureType))
        {
            Object valuesObj = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.read(v, "values");
            if (!(valuesObj instanceof org.finos.legend.pure.truffle.types.PureSequence values) || values.isEmpty())
            {
                return "[]";
            }
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < values.size(); i++)
            {
                if (i > 0) sb.append(", ");
                sb.append(pureToString(values.getBoxed(i), reader));
            }
            return sb.append("]").toString();
        }
        // Pair — <a, b>
        if ("meta::pure::functions::collection::Pair".equals(pureType))
        {
            return "<" + pureToString(org.finos.legend.pure.truffle.runtime.dynobj.PureObj.read(v, "first"), reader)
                    + ", " + pureToString(org.finos.legend.pure.truffle.runtime.dynobj.PureObj.read(v, "second"), reader) + ">";
        }
        // Named metamodel elements (PE-typed) — return just the name. We've
        // already handled Enum above, so this catches Class/Property/etc.
        if (pureType != null)
        {
            Object name = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.read(v, "name");
            if (name instanceof String s && !s.isEmpty())
            {
                return s;
            }
            // For class instances (Any), try to invoke Pure's toString() QP
            if (reader != null)
            {
                try
                {
                    Object result = reader.execute(v, "toString");
                    if (result instanceof String s)
                    {
                        return s;
                    }
                }
                catch (Exception ignored)
                {
                    // No toString QP — fall through
                }
            }
        }
        // Enum value string (format "package::EnumType.VALUE") → return just the value name
        if (v instanceof String s && s.contains("::") && s.contains("."))
        {
            int dotIdx = s.lastIndexOf('.');
            if (dotIdx > 0 && dotIdx < s.length() - 1)
            {
                // Verify the prefix looks like a Pure enum path (contains ::)
                String prefix = s.substring(0, dotIdx);
                if (prefix.contains("::"))
                {
                    return s.substring(dotIdx + 1);
                }
            }
        }
        // String that looks like a date — normalize padding
        if (v instanceof String s && isDateString(s))
        {
            return normalizeDateString(s);
        }
        // Date formatting
        if (v instanceof java.time.LocalDate ld)
        {
            return formatStrictDate(ld);
        }
        if (v instanceof java.time.ZonedDateTime zdt)
        {
            return formatDateTime(zdt);
        }
        // Float/Double: use Double.toString for shortest representation, avoid scientific notation
        if (v instanceof Double d)
        {
            // Normalize -0.0 to 0.0
            if (d == 0.0) d = 0.0;
            String s = Double.toString(d);
            if (s.contains("E") || s.contains("e"))
            {
                String plain = new java.math.BigDecimal(s).toPlainString();
                // Ensure .0 suffix for integer values
                if (!plain.contains("."))
                {
                    plain += ".0";
                }
                return plain;
            }
            return s;
        }
        if (v instanceof Float f)
        {
            // Convert to double for consistent formatting
            return pureToString((double) f);
        }
        // BigDecimal: use toPlainString to avoid scientific notation
        if (v instanceof java.math.BigDecimal bd)
        {
            return bd.toPlainString();
        }
        return String.valueOf(v);
    }

    public static boolean isDateString(String s)
    {
        // Quick heuristic: matches yyyy-M-d, +yyyy-M-d, or yyyy-M-dTh:m:s patterns
        if (s.length() < 6)
        {
            return false;
        }
        int start = 0;
        if (s.charAt(0) == '+' || s.charAt(0) == '-')
        {
            start = 1; // skip sign prefix for large years
        }
        if (start >= s.length() || !Character.isDigit(s.charAt(start)))
        {
            return false;
        }
        int dashIdx = s.indexOf('-', start);
        return dashIdx >= start + 1 && dashIdx <= start + 10;
    }

    // @TruffleBoundary — heavy String.substring usage drags in JDK
    // bounds-check, formatter, locale, and reflection (ClassRepository
    // /SignatureParser) into PE, blowing the inlining budget.
    @TruffleBoundary
    static String normalizeDateString(String s)
    {
        String input = s.startsWith("+") ? s.substring(1) : s;
        try
        {
            int tIdx = input.indexOf('T');
            if (tIdx < 0)
            {
                return normalizeStrictDate(input);
            }

            String datePart = input.substring(0, tIdx);
            String timeAndTz = input.substring(tIdx + 1);

            // Extract timezone if present
            String timePart;
            String tzStr = null;
            int plusIdx = timeAndTz.lastIndexOf('+');
            int minusIdx = timeAndTz.lastIndexOf('-');
            int tzIdx = Math.max(plusIdx, minusIdx);
            if (tzIdx > 0)
            {
                timePart = timeAndTz.substring(0, tzIdx);
                tzStr = timeAndTz.substring(tzIdx);
            }
            else
            {
                timePart = timeAndTz;
            }

            // Parse date
            String[] dp = datePart.split("-");
            int year = Integer.parseInt(dp[0]);
            int month = dp.length > 1 ? Integer.parseInt(dp[1]) : 1;
            int day = dp.length > 2 ? Integer.parseInt(dp[2]) : 1;

            // Parse time
            String[] tp = timePart.split(":");
            int hour = Integer.parseInt(tp[0]);
            int minute = tp.length > 1 ? Integer.parseInt(tp[1]) : 0;
            boolean hasSeconds = tp.length > 2;
            int second = 0;
            String fracStr = "";
            if (hasSeconds)
            {
                String secPart = tp[2];
                int dotIdx = secPart.indexOf('.');
                if (dotIdx >= 0)
                {
                    second = Integer.parseInt(secPart.substring(0, dotIdx));
                    fracStr = secPart.substring(dotIdx);
                }
                else
                {
                    second = Integer.parseInt(secPart);
                }
            }

            // Convert timezone to UTC
            if (tzStr != null)
            {
                int tzSign = tzStr.charAt(0) == '-' ? -1 : 1;
                int tzHours = Integer.parseInt(tzStr.substring(1, 3));
                int tzMinutes = tzStr.length() > 3 ? Integer.parseInt(tzStr.substring(3, 5)) : 0;
                int tzOffsetMinutes = tzSign * (tzHours * 60 + tzMinutes);
                if (tzOffsetMinutes != 0)
                {
                    java.time.ZoneOffset offset = java.time.ZoneOffset.ofTotalSeconds(tzOffsetMinutes * 60);
                    java.time.LocalDateTime ldt = java.time.LocalDateTime.of(year, month, day, hour, minute, second);
                    java.time.OffsetDateTime odt = java.time.OffsetDateTime.of(ldt, offset);
                    java.time.OffsetDateTime utc = odt.withOffsetSameInstant(java.time.ZoneOffset.UTC);
                    year = utc.getYear();
                    month = utc.getMonthValue();
                    day = utc.getDayOfMonth();
                    hour = utc.getHour();
                    minute = utc.getMinute();
                    second = utc.getSecond();
                }
            }

            StringBuilder sb = new StringBuilder();
            sb.append(String.format("%04d-%02d-%02dT%02d:%02d", year, month, day, hour, minute));
            if (hasSeconds)
            {
                sb.append(String.format(":%02d", second));
                sb.append(fracStr);
            }
            return sb.toString();
        }
        catch (Exception e)
        {
            return input;
        }
    }

    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    private static String normalizeStrictDate(String s)
    {
        // Strip leading + for large years
        String input = s.startsWith("+") ? s.substring(1) : s;
        // Parse yyyy-M-d with lenient handling
        String[] parts = input.split("-");
        if (parts.length >= 3)
        {
            long year = Long.parseLong(parts[0]);
            int month = Integer.parseInt(parts[1]);
            int day = Integer.parseInt(parts[2]);
            return String.format("%d-%02d-%02d", year, month, day);
        }
        if (parts.length == 2)
        {
            // yyyy-MM only
            long year = Long.parseLong(parts[0]);
            int month = Integer.parseInt(parts[1]);
            return String.format("%d-%02d", year, month);
        }
        return input;
    }

    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    private static String normalizeTimePart(String s)
    {
        // Handle h:m:s.f or h:m:s format
        String[] parts = s.split(":");
        if (parts.length >= 3)
        {
            int hour = Integer.parseInt(parts[0]);
            int minute = Integer.parseInt(parts[1]);
            // Second might have fractional part
            String secPart = parts[2];
            String frac = "";
            if (secPart.contains("."))
            {
                int dotIdx = secPart.indexOf('.');
                frac = secPart.substring(dotIdx);
                secPart = secPart.substring(0, dotIdx);
            }
            int second = Integer.parseInt(secPart);
            return String.format("%02d:%02d:%02d", hour, minute, second) + frac;
        }
        return s;
    }

    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    static String formatStrictDate(java.time.LocalDate ld)
    {
        // Pure format: yyyy-MM-dd (no + prefix for large years)
        String s = String.format("%04d-%02d-%02d", ld.getYear(), ld.getMonthValue(), ld.getDayOfMonth());
        return s;
    }

    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    static String formatDateTime(java.time.ZonedDateTime zdt)
    {
        // Pure format: yyyy-MM-ddTHH:mm:ss.S (no + prefix, no timezone)
        String s = String.format("%04d-%02d-%02dT%02d:%02d:%02d",
                zdt.getYear(), zdt.getMonthValue(), zdt.getDayOfMonth(),
                zdt.getHour(), zdt.getMinute(), zdt.getSecond());
        int nano = zdt.getNano();
        if (nano > 0)
        {
            // Add fractional seconds
            String frac = String.valueOf(nano / 100_000_000.0).substring(1); // ".X"
            s += frac;
        }
        else
        {
            s += ".0";
        }
        return s;
    }
}
