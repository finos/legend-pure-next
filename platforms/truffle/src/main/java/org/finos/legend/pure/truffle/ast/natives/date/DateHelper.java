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
import meta.pure.metamodel.valuespecification.AtomicValue;
import org.finos.legend.pure.execution.natives.string.StringNatives;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.temporal.ChronoField;

/**
 * Shared helpers for date-native Truffle nodes. Extracts the date string
 * from a VS and parses it via a flexible formatter matching the bridge
 * {@code DateNatives} implementation.
 */
public final class DateHelper
{
    private static final DateTimeFormatter PURE_DATE_FORMATTER = new DateTimeFormatterBuilder()
            .appendPattern("yyyy-MM-dd['T'HH[:mm[:ss]")
            .optionalStart().appendFraction(ChronoField.NANO_OF_SECOND, 1, 9, true).optionalEnd()
            .appendPattern("[Z]]")
            .parseDefaulting(ChronoField.HOUR_OF_DAY, 0)
            .parseDefaulting(ChronoField.MINUTE_OF_HOUR, 0)
            .parseDefaulting(ChronoField.SECOND_OF_MINUTE, 0)
            .parseDefaulting(ChronoField.NANO_OF_SECOND, 0)
            .toFormatter();

    private DateHelper()
    {
    }

    @TruffleBoundary
    public static String asDateString(Object v, String signature)
    {
        if (v instanceof String s)
        {
            return s;
        }
        if (v instanceof AtomicValue av && av._value() instanceof String s)
        {
            return s;
        }
        if (v != null)
        {
            return v.toString();
        }
        throw new ClassCastException(signature + " expected Date string, got: null");
    }

    @TruffleBoundary
    public static LocalDateTime parseDate(String pureDateStr)
    {
        String normalized = StringNatives.normalizePureDate(pureDateStr);
        if (normalized.endsWith("Z") || normalized.matches(".*[+-]\\d{4}$"))
        {
            return OffsetDateTime.parse(normalized, PURE_DATE_FORMATTER).toLocalDateTime();
        }
        else if (normalized.contains("T"))
        {
            return LocalDateTime.parse(normalized, PURE_DATE_FORMATTER);
        }
        else
        {
            String padded = normalized;
            if (padded.length() == 4)
            {
                padded += "-01-01";
            }
            else if (padded.length() == 7)
            {
                padded += "-01";
            }
            else if (padded.endsWith("-00-00"))
            {
                padded = padded.substring(0, padded.length() - 6) + "-01-01";
            }
            else if (padded.endsWith("-00"))
            {
                padded = padded.substring(0, padded.length() - 3) + "-01";
            }
            return LocalDate.parse(padded, PURE_DATE_FORMATTER).atStartOfDay();
        }
    }
}
