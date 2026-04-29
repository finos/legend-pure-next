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

import org.finos.legend.pure.truffle.types.PureDate;

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

    public static String asDateString(Object v, String signature)
    {
        if (v instanceof PureDate pd)
        {
            return pd.dateString();
        }
        if (v instanceof String s)
        {
            return s;
        }
        if (v != null)
        {
            return v.toString();
        }
        throw new ClassCastException(signature + " expected Date string, got: null");
    }


    public static LocalDateTime parseDate(String pureDateStr)
    {
        String normalized = normalizePureDate(pureDateStr);
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

    /**
     * Normalize a date string: zero-pad components and convert timezone to UTC.
     * Input is a clean date string WITHOUT % prefix (e.g., "2014-01-01T00:00:00.0000+0000").
     * Returns the normalized date string WITHOUT % prefix.
     */
    public static String normalizePureDate(String dateStr)
    {
        if (dateStr == null)
        {
            return dateStr;
        }

        int tIdx = dateStr.indexOf('T');
        if (tIdx < 0)
        {
            return zeroPadDate(dateStr);
        }

        try
        {
            String datePart = dateStr.substring(0, tIdx);
            String timeAndTz = dateStr.substring(tIdx + 1);

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

            String[] dp = datePart.split("-");
            int year = Integer.parseInt(dp[0]);
            int month = dp.length > 1 ? Integer.parseInt(dp[1]) : 1;
            int day = dp.length > 2 ? Integer.parseInt(dp[2]) : 1;

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
            return dateStr;
        }
    }

    private static String zeroPadDate(String datePart)
    {
        String[] parts = datePart.split("-");
        if (parts.length >= 3)
        {
            return parts[0]
                    + "-" + (parts[1].length() < 2 ? "0" + parts[1] : parts[1])
                    + "-" + (parts[2].length() < 2 ? "0" + parts[2] : parts[2]);
        }
        if (parts.length == 2)
        {
            return parts[0]
                    + "-" + (parts[1].length() < 2 ? "0" + parts[1] : parts[1]);
        }
        return datePart;
    }
}
