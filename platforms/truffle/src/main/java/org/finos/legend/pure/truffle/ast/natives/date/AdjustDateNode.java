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
import org.finos.legend.pure.truffle.ast.natives.math.IntegerHelper;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * {@code adjust(Date[1], Integer[1], DurationUnit[1]) : Date[1]}
 * -- adds or subtracts a duration from a date.
 */
@NodeInfo(shortName = "adjustDate")
public final class AdjustDateNode extends PureNode
{
    private static final String SIG = "adjust_Date_1__Integer_1__DurationUnit_1__Date_1_";

    @Child
    private PureNode dateArg;

    @Child
    private PureNode amountArg;

    @Child
    private PureNode unitArg;

    public AdjustDateNode(PureNode dateArg, PureNode amountArg, PureNode unitArg)
    {
        this.dateArg = dateArg;
        this.amountArg = amountArg;
        this.unitArg = unitArg;
    }

    @Override
    public Object executeGeneric(VirtualFrame frame)
    {
        Object date = dateArg.executeGeneric(frame);
        Object amount = amountArg.executeGeneric(frame);
        Object unit = unitArg.executeGeneric(frame);
        return doAdjust(date, amount, unit);
    }

    @TruffleBoundary
    private static String doAdjust(Object date, Object amount, Object unit)
    {
        String dateStr = DateHelper.asDateString(date, SIG);
        long amt = IntegerHelper.asLong(amount, SIG);
        String unitName = DateDiffNode.resolveUnitName(unit);

        LocalDateTime ldt = DateHelper.parseDate(dateStr);

        LocalDateTime adjusted = switch (unitName)
        {
            case "YEARS" -> ldt.plusYears(amt);
            case "MONTHS" -> ldt.plusMonths(amt);
            case "WEEKS" -> ldt.plusWeeks(amt);
            case "DAYS" -> ldt.plusDays(amt);
            case "HOURS" -> ldt.plusHours(amt);
            case "MINUTES" -> ldt.plusMinutes(amt);
            case "SECONDS" -> ldt.plusSeconds(amt);
            case "MILLISECONDS" -> ldt.plusNanos(amt * 1_000_000);
            case "MICROSECONDS" -> ldt.plusNanos(amt * 1_000);
            case "NANOSECONDS" -> ldt.plusNanos(amt);
            default -> throw new RuntimeException("Unknown duration unit: " + unitName);
        };

        // Format back to Pure date format matching input precision
        if (dateStr.contains("T"))
        {
            if (dateStr.contains("."))
            {
                return adjusted.format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS"));
            }
            return adjusted.format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"));
        }
        if (dateStr.length() <= 10)
        {
            return adjusted.format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
        }
        return adjusted.format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"));
    }
}
