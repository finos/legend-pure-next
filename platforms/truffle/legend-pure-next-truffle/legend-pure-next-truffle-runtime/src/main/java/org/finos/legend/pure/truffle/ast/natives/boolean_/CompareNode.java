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

package org.finos.legend.pure.truffle.ast.natives.boolean_;

import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.NodeInfo;
import org.finos.legend.pure.truffle.ast.PureNode;
import org.finos.legend.pure.truffle.types.PureDate;

/**
 * {@code compare(T[1], T[1]) : Integer[1]}.
 *
 * <p>Compares two values and returns {@code -1}, {@code 0}, or {@code 1}.
 * For numbers, converts both to {@link java.math.BigDecimal} and uses
 * {@code compareTo}. For dates, normalizes and compares as strings.
 * For all other types, uses string representation comparison.</p>
 */
@NodeInfo(shortName = "compare")
public final class CompareNode extends PureNode
{
    @Child
    private PureNode left;

    @Child
    private PureNode right;

    private final Object genericType;
    private final Object multiplicity;

    public CompareNode(PureNode left, PureNode right, Object genericType, Object multiplicity)
    {
        this.left = left;
        this.right = right;
        this.genericType = genericType;
        this.multiplicity = multiplicity;
    }

    @Override
    public Object executeGeneric(VirtualFrame frame)
    {
        Object a = left.executeGeneric(frame);
        Object b = right.executeGeneric(frame);
        return doCompare(a, b);
    }

    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    private static long doCompare(Object rawA, Object rawB)
    {
        // Normalize: if one side is PureDate and the other is a date-like String, treat both as dates
        if (rawA instanceof PureDate && rawB instanceof String s && org.finos.legend.pure.truffle.ast.natives.string.ToStringNode.isDateString(s))
        {
            rawB = PureDate.of(s, s.contains("T") ? "DateTime" : "Date");
        }
        else if (rawB instanceof PureDate && rawA instanceof String s && org.finos.legend.pure.truffle.ast.natives.string.ToStringNode.isDateString(s))
        {
            rawA = PureDate.of(s, s.contains("T") ? "DateTime" : "Date");
        }
        if (rawA instanceof PureDate pdA && rawB instanceof PureDate pdB)
        {
            try
            {
                java.time.LocalDateTime dtA = org.finos.legend.pure.truffle.ast.natives.date.DateHelper.parseDate(pdA.dateString());
                java.time.LocalDateTime dtB = org.finos.legend.pure.truffle.ast.natives.date.DateHelper.parseDate(pdB.dateString());
                return (long) Math.signum(dtA.compareTo(dtB));
            }
            catch (Exception e)
            {
                // Fallback: try numeric year comparison for large years
                try
                {
                    long yearA = Long.parseLong(pdA.dateString().split("-")[0]);
                    long yearB = Long.parseLong(pdB.dateString().split("-")[0]);
                    if (yearA != yearB) return (long) Math.signum(yearA - yearB);
                }
                catch (NumberFormatException ignored) {}
                return (long) Math.signum(pdA.dateString().compareTo(pdB.dateString()));
            }
        }
        // Mixed types: PureDate vs non-PureDate — compare by class name
        if (rawA instanceof PureDate || rawB instanceof PureDate)
        {
            String classA = rawA instanceof PureDate ? "Date" : rawA.getClass().getName();
            String classB = rawB instanceof PureDate ? "Date" : rawB.getClass().getName();
            return (long) Math.signum(classA.compareTo(classB));
        }

        Object a = rawA;
        Object b = rawB;

        // Number comparison
        if (a instanceof Number nA && b instanceof Number nB)
        {
            java.math.BigDecimal bdA = a instanceof java.math.BigDecimal
                    ? (java.math.BigDecimal) a
                    : new java.math.BigDecimal(a.toString());
            java.math.BigDecimal bdB = b instanceof java.math.BigDecimal
                    ? (java.math.BigDecimal) b
                    : new java.math.BigDecimal(b.toString());
            return (long) Math.signum(bdA.compareTo(bdB));
        }

        // Date comparison (dates are stored as Strings)
        if (a instanceof String sA && b instanceof String sB)
        {
            // Try date comparison if both look like dates
            if (looksLikeDate(sA) && looksLikeDate(sB))
            {
                String n1 = org.finos.legend.pure.truffle.ast.natives.date.DateHelper.normalizePureDate(sA);
                String n2 = org.finos.legend.pure.truffle.ast.natives.date.DateHelper.normalizePureDate(sB);
                return (long) Math.signum(comparePureDates(n1, n2));
            }
            return (long) Math.signum(sA.compareTo(sB));
        }

        // Different types: compare by type name first for consistent ordering
        if (a != null && b != null && !a.getClass().equals(b.getClass()))
        {
            int typeCmp = a.getClass().getName().compareTo(b.getClass().getName());
            if (typeCmp != 0)
            {
                return (long) Math.signum(typeCmp);
            }
        }
        // Same type fallback: string representation
        return (long) Math.signum(String.valueOf(a).compareTo(String.valueOf(b)));
    }

    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    private static boolean looksLikeDate(String s)
    {
        if (s == null || s.length() < 4 || s.length() > 30)
        {
            return false;
        }
        // Must start with digits (year), then optional -MM-DD or T
        for (int i = 0; i < s.length(); i++)
        {
            char c = s.charAt(i);
            if (c == '-' || c == 'T')
            {
                return true;
            }
            if (!Character.isDigit(c))
            {
                return false;
            }
        }
        // All digits → year-only date (e.g. "2014", "10999")
        return true;
    }

    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    private static int comparePureDates(String d1, String d2)
    {
        int dash1 = d1.indexOf('-', d1.startsWith("-") ? 1 : 0);
        int dash2 = d2.indexOf('-', d2.startsWith("-") ? 1 : 0);

        String y1 = dash1 > 0 ? d1.substring(0, dash1) : d1;
        String y2 = dash2 > 0 ? d2.substring(0, dash2) : d2;

        try
        {
            int year1 = Integer.parseInt(y1);
            int year2 = Integer.parseInt(y2);
            if (year1 != year2)
            {
                return Integer.compare(year1, year2);
            }
            String rest1 = dash1 > 0 ? d1.substring(dash1) : "";
            String rest2 = dash2 > 0 ? d2.substring(dash2) : "";
            return rest1.compareTo(rest2);
        }
        catch (NumberFormatException e)
        {
            return d1.compareTo(d2);
        }
    }
}
