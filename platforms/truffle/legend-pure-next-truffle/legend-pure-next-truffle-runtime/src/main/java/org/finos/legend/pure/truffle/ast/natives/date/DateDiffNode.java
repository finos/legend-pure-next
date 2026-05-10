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

package org.finos.legend.pure.truffle.ast.natives.date;

import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.NodeInfo;
import org.finos.legend.pure.truffle.ast.PureNode;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

/**
 * {@code dateDiff(Date[1], Date[1], DurationUnit[1]) : Integer[1]}
 * -- difference between two dates in the given unit.
 */
@NodeInfo(shortName = "dateDiff")
public final class DateDiffNode extends PureNode
{
    private static final String SIG = "dateDiff_Date_1__Date_1__DurationUnit_1__Integer_1_";

    @Child
    private PureNode date1Arg;

    @Child
    private PureNode date2Arg;

    @Child
    private PureNode unitArg;

    public DateDiffNode(PureNode date1Arg, PureNode date2Arg, PureNode unitArg)
    {
        this.date1Arg = date1Arg;
        this.date2Arg = date2Arg;
        this.unitArg = unitArg;
    }

    @Override
    public Object executeGeneric(VirtualFrame frame)
    {
        Object d1 = date1Arg.executeGeneric(frame);
        Object d2 = date2Arg.executeGeneric(frame);
        Object unit = unitArg.executeGeneric(frame);
        return doDiff(d1, d2, unit);
    }

    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    private static long doDiff(Object d1, Object d2, Object unit)
    {
        String d1Str = DateHelper.asDateString(d1, SIG);
        String d2Str = DateHelper.asDateString(d2, SIG);
        String unitName = resolveUnitName(unit);

        LocalDateTime ldt1 = DateHelper.parseDate(d1Str);
        LocalDateTime ldt2 = DateHelper.parseDate(d2Str);

        // Pure dateDiff counts calendar-part transitions, not elapsed periods.
        // Result sign: positive if d2 > d1, negative if d2 < d1, zero if equal.
        long absResult;
        switch (unitName)
        {
            case "YEARS":
                absResult = Math.abs((long) ldt1.getYear() - ldt2.getYear());
                break;
            case "MONTHS":
            {
                long m1 = ldt1.getYear() * 12L + ldt1.getMonthValue();
                long m2 = ldt2.getYear() * 12L + ldt2.getMonthValue();
                absResult = Math.abs(m1 - m2);
                break;
            }
            case "WEEKS":
            {
                long absDiffDays = Math.abs(ChronoUnit.DAYS.between(ldt1.toLocalDate(), ldt2.toLocalDate()));
                int startDay = ldt1.get(java.time.temporal.ChronoField.DAY_OF_WEEK);
                int sundayIdx = startDay == 7 ? 1 : startDay + 1;
                int noDaysTillSunday;
                if (ldt1.isBefore(ldt2))
                {
                    noDaysTillSunday = 7 - (sundayIdx - 1);
                }
                else
                {
                    noDaysTillSunday = sundayIdx - 1;
                }
                if (noDaysTillSunday > absDiffDays)
                {
                    absResult = 0;
                }
                else
                {
                    long fullWeeks = (absDiffDays - noDaysTillSunday) / 7;
                    long partialWeek = noDaysTillSunday > 0 ? 1 : 0;
                    absResult = fullWeeks + partialWeek;
                }
                break;
            }
            case "DAYS":
                absResult = Math.abs(ChronoUnit.DAYS.between(ldt1.toLocalDate(), ldt2.toLocalDate()));
                break;
            case "HOURS":
                absResult = Math.abs(ChronoUnit.MILLIS.between(ldt1, ldt2)) / 3600_000L;
                break;
            case "MINUTES":
                absResult = Math.abs(ChronoUnit.MILLIS.between(ldt1, ldt2)) / 60_000L;
                break;
            case "SECONDS":
                absResult = Math.abs(ChronoUnit.MILLIS.between(ldt1, ldt2)) / 1000L;
                break;
            case "MILLISECONDS":
                absResult = Math.abs(ChronoUnit.MILLIS.between(ldt1, ldt2));
                break;
            case "MICROSECONDS":
                absResult = Math.abs(ChronoUnit.MICROS.between(ldt1, ldt2));
                break;
            case "NANOSECONDS":
                absResult = Math.abs(ChronoUnit.NANOS.between(ldt1, ldt2));
                break;
            default:
                throw new RuntimeException("Unknown duration unit: " + unitName);
        }
        int sign = ldt1.isEqual(ldt2) ? 0 : ldt1.isBefore(ldt2) ? 1 : -1;
        return sign * absResult;
    }

    static String resolveUnitName(Object unit)
    {
        if (org.finos.legend.pure.truffle.runtime.dynobj.PureObj.pureTypeIs(unit,
                "meta::pure::metamodel::type::Enum"))
        {
            return (String) org.finos.legend.pure.truffle.runtime.dynobj.PureObj.read(unit, "name");
        }
        if (unit instanceof String s)
        {
            // Enum values may arrive as paths: "meta::...::DurationUnit.YEARS"
            int dot = s.lastIndexOf('.');
            return dot >= 0 ? s.substring(dot + 1) : s;
        }
        return String.valueOf(unit);
    }
}
