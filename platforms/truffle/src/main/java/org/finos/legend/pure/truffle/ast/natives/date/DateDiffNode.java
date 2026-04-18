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

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
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

    @TruffleBoundary
    private static long doDiff(Object d1, Object d2, Object unit)
    {
        String d1Str = DateHelper.asDateString(d1, SIG);
        String d2Str = DateHelper.asDateString(d2, SIG);
        String unitName = resolveUnitName(unit);

        LocalDateTime ldt1 = DateHelper.parseDate(d1Str);
        LocalDateTime ldt2 = DateHelper.parseDate(d2Str);

        return switch (unitName)
        {
            case "YEARS" -> ChronoUnit.YEARS.between(ldt1, ldt2);
            case "MONTHS" -> ChronoUnit.MONTHS.between(ldt1, ldt2);
            case "WEEKS" -> ChronoUnit.WEEKS.between(ldt1, ldt2);
            case "DAYS" -> ChronoUnit.DAYS.between(ldt1, ldt2);
            case "HOURS" -> ChronoUnit.HOURS.between(ldt1, ldt2);
            case "MINUTES" -> ChronoUnit.MINUTES.between(ldt1, ldt2);
            case "SECONDS" -> ChronoUnit.SECONDS.between(ldt1, ldt2);
            case "MILLISECONDS" -> ChronoUnit.MILLIS.between(ldt1, ldt2);
            case "MICROSECONDS" -> ChronoUnit.MICROS.between(ldt1, ldt2);
            case "NANOSECONDS" -> ChronoUnit.NANOS.between(ldt1, ldt2);
            default -> throw new RuntimeException("Unknown duration unit: " + unitName);
        };
    }

    static String resolveUnitName(Object unit)
    {
        if (unit instanceof meta.pure.metamodel.type.Enum e)
        {
            return e._name();
        }
        if (unit instanceof String s)
        {
            return s;
        }
        if (unit instanceof meta.pure.metamodel.valuespecification.AtomicValue av)
        {
            Object inner = av._value();
            if (inner instanceof meta.pure.metamodel.type.Enum e)
            {
                return e._name();
            }
            if (inner instanceof String s)
            {
                return s;
            }
        }
        return String.valueOf(unit);
    }
}
