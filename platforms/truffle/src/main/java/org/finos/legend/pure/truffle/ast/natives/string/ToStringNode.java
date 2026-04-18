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

@NodeInfo(shortName = "toStr")
public final class ToStringNode extends PureNode
{
    @Child
    private PureNode arg;

    private final GenericType genericType;
    private final Multiplicity multiplicity;

    public ToStringNode(PureNode arg, GenericType genericType, Multiplicity multiplicity)
    {
        this.arg = arg;
        this.genericType = genericType;
        this.multiplicity = multiplicity;
    }

    @Override
    public Object executeGeneric(VirtualFrame frame)
    {
        Object v = arg.executeGeneric(frame);
        return convert(v);
    }

    @TruffleBoundary
    private static String convert(Object v)
    {
        Object normalized = org.finos.legend.pure.truffle.types.ValueNormalizer.normalize(v);
        if (normalized == null)
        {
            return "";
        }
        return pureToString(normalized);
    }

    static String pureToString(Object v)
    {
        if (v == null)
        {
            return "";
        }
        // Unwrap AtomicValue — dates come as AV wrapping a date string
        if (v instanceof meta.pure.metamodel.valuespecification.AtomicValue av)
        {
            Object inner = av._value();
            if (inner instanceof String s && isDateString(s))
            {
                return normalizeDateString(s);
            }
            if (inner != null)
            {
                return pureToString(inner);
            }
            return "";
        }
        // Named metamodel elements — return just the name
        if (v instanceof meta.pure.metamodel.PackageableElement pe && pe._name() != null)
        {
            return pe._name();
        }
        // For class instances (Any), try to invoke Pure's toString() QP
        if (v instanceof meta.pure.metamodel.type.Any any)
        {
            var eval = org.finos.legend.pure.truffle.runtime.StandaloneEvaluatorHolder.current();
            if (eval != null)
            {
                try
                {
                    Object result = eval.accessProperty(any, "toString");
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
        return PureValuePrinter.printForOutput(v);
    }

    static boolean isDateString(String s)
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

    static String normalizeDateString(String s)
    {
        // Strip leading + for large years
        String input = s.startsWith("+") ? s.substring(1) : s;
        try
        {
            if (input.contains("T"))
            {
                String[] parts = input.split("T", 2);
                String datePart = normalizeStrictDate(parts[0]);
                String timePart = normalizeTimePart(parts[1]);
                return datePart + "T" + timePart;
            }
            else
            {
                return normalizeStrictDate(input);
            }
        }
        catch (Exception e)
        {
            return input;
        }
    }

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

    static String formatStrictDate(java.time.LocalDate ld)
    {
        // Pure format: yyyy-MM-dd (no + prefix for large years)
        String s = String.format("%04d-%02d-%02d", ld.getYear(), ld.getMonthValue(), ld.getDayOfMonth());
        return s;
    }

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
