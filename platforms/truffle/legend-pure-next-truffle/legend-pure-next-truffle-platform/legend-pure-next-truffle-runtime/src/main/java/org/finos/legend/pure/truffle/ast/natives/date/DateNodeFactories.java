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

import org.finos.legend.pure.truffle.builder.NativeNodeRegistry;

import java.time.LocalDateTime;

/**
 * Registers specialized Truffle nodes for date natives:
 * component extractors (year, month, day, hour, minute, second, millis),
 * datePart, dateDiff, adjust, and currentTimeMillis.
 */
public final class DateNodeFactories
{
    private DateNodeFactories()
    {
    }

    public static void registerAll(NativeNodeRegistry registry)
    {
        registry.register("year_Date_1__Integer_1_",
                (args, gt, mul, fe) -> new DateComponentNode(args[0],
                        "year_Date_1__Integer_1_", LocalDateTime::getYear));

        registry.register("monthNumber_Date_1__Integer_1_",
                (args, gt, mul, fe) -> new DateComponentNode(args[0],
                        "monthNumber_Date_1__Integer_1_", LocalDateTime::getMonthValue));

        registry.register("dayOfMonth_Date_1__Integer_1_",
                (args, gt, mul, fe) -> new DateComponentNode(args[0],
                        "dayOfMonth_Date_1__Integer_1_", LocalDateTime::getDayOfMonth));

        registry.register("hour_DateTime_1__Integer_1_",
                (args, gt, mul, fe) -> new DateComponentNode(args[0],
                        "hour_DateTime_1__Integer_1_", LocalDateTime::getHour));

        registry.register("minute_DateTime_1__Integer_1_",
                (args, gt, mul, fe) -> new DateComponentNode(args[0],
                        "minute_DateTime_1__Integer_1_", LocalDateTime::getMinute));

        registry.register("second_DateTime_1__Integer_1_",
                (args, gt, mul, fe) -> new DateComponentNode(args[0],
                        "second_DateTime_1__Integer_1_", LocalDateTime::getSecond));

        registry.register("millis_DateTime_1__Integer_1_",
                (args, gt, mul, fe) -> new DateComponentNode(args[0],
                        "millis_DateTime_1__Integer_1_",
                        ldt -> ldt.getNano() / 1_000_000));

        registry.register("datePart_Date_1__StrictDate_1_",
                (args, gt, mul, fe) -> new DatePartNode(args[0], gt, mul));

        registry.register("dateDiff_Date_1__Date_1__DurationUnit_1__Integer_1_",
                (args, gt, mul, fe) -> new DateDiffNode(args[0], args[1], args[2]));

        registry.register("adjust_Date_1__Integer_1__DurationUnit_1__Date_1_",
                (args, gt, mul, fe) -> new AdjustDateNode(args[0], args[1], args[2]));

        registry.register("currentTimeMillis__Integer_1_",
                (args, gt, mul, fe) -> new CurrentTimeMillisNode());
    }
}
