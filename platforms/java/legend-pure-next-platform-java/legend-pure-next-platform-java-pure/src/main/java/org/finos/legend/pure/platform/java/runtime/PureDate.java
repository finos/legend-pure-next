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

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoField;
import java.time.temporal.ChronoUnit;

/**
 * A Pure date: its normalized text (zero-padded, a time zone folded into UTC, no trailing {@code Z}) and its
 * precision — {@code StrictDate} (a full date), {@code DateTime} (with a time) or {@code Date} (a year or a
 * year-month). Dates compare by the instant they denote.
 */
public final class PureDate implements Comparable<Object>
{
    public String value;
    public String kind;

    public static PureDate of(String text)
    {
        PureDate d = new PureDate();
        String normalized = normalize(text.trim());
        if (normalized.endsWith("Z"))
        {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        d.value = normalized;
        // Year, year-month or full date, for a negative year too: count the separators of the part after it,
        // not fixed positions.
        String pureDateBody = normalized.startsWith("-") ? normalized.substring(1) : normalized;
        d.kind = normalized.contains("T") ? "DateTime" : pureDateBody.split("-").length >= 3 ? "StrictDate" : "Date";
        return d;
    }

    static PureDate ofKind(String value, String kind)
    {
        PureDate d = new PureDate();
        d.value = value;
        d.kind = kind;
        return d;
    }

    public String _purePath()
    {
        return "platform::pure::metamodel::type::primitives::Date";
    }

    @Override
    public String toString()
    {
        return value;
    }

    @Override
    public boolean equals(Object o)
    {
        return o instanceof PureDate && value.equals(((PureDate) o).value);
    }

    @Override
    public int hashCode()
    {
        return value.hashCode();
    }

    @Override
    public int compareTo(Object o)
    {
        return Integer.signum(toLocalDateTime().compareTo(((PureDate) o).toLocalDateTime()));
    }

    public static boolean is(Object value, String kind)
    {
        return value instanceof PureDate && (kind.equals("Date") || ((PureDate) value).kind.equals(kind));
    }

    /** Zero-padded, a time zone folded into UTC; the text as it is when it does not parse. */
    static String normalize(String dateStr)
    {
        // A negative year: the sign is not a field separator.
        if (dateStr.startsWith("-"))
        {
            return "-" + normalize(dateStr.substring(1));
        }
        int tIdx = dateStr.indexOf('T');
        if (tIdx < 0)
        {
            String[] parts = dateStr.split("-");
            if (parts.length >= 3)
            {
                return parts[0] + "-" + pad2(parts[1]) + "-" + pad2(parts[2]);
            }
            if (parts.length == 2)
            {
                return parts[0] + "-" + pad2(parts[1]);
            }
            return dateStr;
        }
        try
        {
            return normalizeDateTime(dateStr.substring(0, tIdx), dateStr.substring(tIdx + 1));
        }
        catch (RuntimeException e)
        {
            return dateStr;
        }
    }

    private static String normalizeDateTime(String datePart, String timeAndTz)
    {
        int tzIdx = Math.max(timeAndTz.lastIndexOf('+'), timeAndTz.lastIndexOf('-'));
        String timePart = tzIdx > 0 ? timeAndTz.substring(0, tzIdx) : timeAndTz;
        String tzStr = tzIdx > 0 ? timeAndTz.substring(tzIdx) : null;
        String[] dp = datePart.split("-");
        String[] tp = timePart.split(":");
        boolean hasMinutes = tp.length > 1;
        boolean hasSeconds = tp.length > 2;
        int second = 0;
        String fracStr = "";
        if (hasSeconds)
        {
            int dotIdx = tp[2].indexOf('.');
            second = Integer.parseInt(dotIdx >= 0 ? tp[2].substring(0, dotIdx) : tp[2]);
            fracStr = dotIdx >= 0 ? tp[2].substring(dotIdx) : "";
        }
        LocalDateTime local = LocalDateTime.of(Integer.parseInt(dp[0]), dp.length > 1 ? Integer.parseInt(dp[1]) : 1, dp.length > 2 ? Integer.parseInt(dp[2]) : 1,
                Integer.parseInt(tp[0]), hasMinutes ? Integer.parseInt(tp[1]) : 0, second);
        LocalDateTime utc = tzStr == null ? local : inUtc(local, tzStr);
        StringBuilder sb = new StringBuilder(String.format("%04d-%02d-%02dT%02d", utc.getYear(), utc.getMonthValue(), utc.getDayOfMonth(), utc.getHour()));
        if (hasMinutes)
        {
            sb.append(String.format(":%02d", utc.getMinute()));
        }
        if (hasSeconds)
        {
            sb.append(String.format(":%02d", utc.getSecond())).append(fracStr);
        }
        return sb.toString();
    }

    /** `local` at the offset `tzStr` (`+0100`, `-05`), as the UTC date-time it denotes. */
    private static LocalDateTime inUtc(LocalDateTime local, String tzStr)
    {
        int tzSign = tzStr.charAt(0) == '-' ? -1 : 1;
        int tzOffsetMinutes = tzSign * (Integer.parseInt(tzStr.substring(1, 3)) * 60 + (tzStr.length() > 3 ? Integer.parseInt(tzStr.substring(3, 5)) : 0));
        if (tzOffsetMinutes == 0)
        {
            return local;
        }
        return OffsetDateTime.of(local, ZoneOffset.ofTotalSeconds(tzOffsetMinutes * 60)).withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime();
    }

    private static String pad2(String s)
    {
        return s.length() < 2 ? "0" + s : s;
    }

    public LocalDateTime toLocalDateTime()
    {
        String s = value;
        if (s.contains("T"))
        {
            int t = s.indexOf('T');
            String[] tp = s.substring(t + 1).split(":");
            String[] dp = s.substring(0, t).split("-");
            int second = 0;
            int nanos = 0;
            if (tp.length > 2)
            {
                String sec = tp[2];
                int dot = sec.indexOf('.');
                second = Integer.parseInt(dot >= 0 ? sec.substring(0, dot) : sec);
                if (dot >= 0)
                {
                    nanos = Integer.parseInt((sec.substring(dot + 1) + "000000000").substring(0, 9));
                }
            }
            return LocalDateTime.of(Integer.parseInt(dp[0]), Integer.parseInt(dp[1]), Integer.parseInt(dp[2]), Integer.parseInt(tp[0]), tp.length > 1 ? Integer.parseInt(tp[1]) : 0, second, nanos);
        }
        String[] dp = s.split("-");
        return LocalDate.of(Integer.parseInt(dp[0]),
                dp.length > 1 && !dp[1].equals("00") ? Integer.parseInt(dp[1]) : 1,
                dp.length > 2 && !dp[2].equals("00") ? Integer.parseInt(dp[2]) : 1).atStartOfDay();
    }

    public long year()
    {
        return toLocalDateTime().getYear();
    }

    public long monthNumber()
    {
        return toLocalDateTime().getMonthValue();
    }

    public long dayOfMonth()
    {
        return toLocalDateTime().getDayOfMonth();
    }

    public long hour()
    {
        return toLocalDateTime().getHour();
    }

    public long minute()
    {
        return toLocalDateTime().getMinute();
    }

    public long second()
    {
        return toLocalDateTime().getSecond();
    }

    public static PureDate datePart(PureDate d)
    {
        String datePart = d.value.contains("T") ? d.value.substring(0, d.value.indexOf('T')) : d.value;
        if (datePart.length() == 4)
        {
            datePart += "-01-01";
        }
        else if (datePart.length() == 7)
        {
            datePart += "-01";
        }
        return ofKind(datePart, "StrictDate");
    }

    /** The date `amt` units later, at the precision it had (a date-time keeps as many fraction digits). */
    public static PureDate adjust(PureDate date, long amt, String unit)
    {
        String dateStr = date.value;
        LocalDateTime adjusted = plus(date.toLocalDateTime(), amt, unit);
        if (dateStr.contains("T"))
        {
            String dateTime = String.format("%d-%02d-%02d", adjusted.getYear(), adjusted.getMonthValue(), adjusted.getDayOfMonth())
                    + "T" + String.format("%02d:%02d:%02d", adjusted.getHour(), adjusted.getMinute(), adjusted.getSecond());
            String fraction = dateStr.contains(".") ? fraction(adjusted.getNano(), dateStr.substring(dateStr.indexOf('.') + 1).length()) : "";
            return ofKind(dateTime + fraction, "DateTime");
        }
        String cleanDate = dateStr.startsWith("+") || dateStr.startsWith("-") ? dateStr.substring(1) : dateStr;
        if (cleanDate.indexOf('-') < 0 && "YEARS".equals(unit))
        {
            return ofKind(String.valueOf(adjusted.getYear()), "Date");
        }
        return ofKind(String.format("%d-%02d-%02d", adjusted.getYear(), adjusted.getMonthValue(), adjusted.getDayOfMonth()), "StrictDate".equals(date.kind) ? "StrictDate" : "Date");
    }

    private static LocalDateTime plus(LocalDateTime ldt, long amt, String unit)
    {
        switch (unit)
        {
            case "YEARS":
                return ldt.plusYears(amt);
            case "MONTHS":
                return ldt.plusMonths(amt);
            case "WEEKS":
                return ldt.plusWeeks(amt);
            case "DAYS":
                return ldt.plusDays(amt);
            case "HOURS":
                return ldt.plusHours(amt);
            case "MINUTES":
                return ldt.plusMinutes(amt);
            case "SECONDS":
                return ldt.plusSeconds(amt);
            case "MILLISECONDS":
                return ldt.plusNanos(amt * 1_000_000);
            case "MICROSECONDS":
                return ldt.plusNanos(amt * 1_000);
            case "NANOSECONDS":
                return ldt.plusNanos(amt);
            default:
                throw new RuntimeException("Unknown duration unit: " + unit);
        }
    }

    /** `nanos` as a fraction of as many digits as the input had: 3, 6, or the input's own count. */
    private static String fraction(long nanos, int fracDigits)
    {
        if (fracDigits <= 3)
        {
            return String.format(".%03d", nanos / 1_000_000);
        }
        if (fracDigits <= 6)
        {
            return String.format(".%06d", nanos / 1_000);
        }
        return String.format(".%0" + fracDigits + "d", nanos / (long) Math.pow(10, 9 - fracDigits));
    }

    /** How many `unit`s from d1 to d2: negative when d2 is before d1. */
    public static long dateDiff(PureDate d1, PureDate d2, String unit)
    {
        LocalDateTime ldt1 = d1.toLocalDateTime();
        LocalDateTime ldt2 = d2.toLocalDateTime();
        long absResult = absoluteDifference(ldt1, ldt2, unit);
        int sign = ldt1.isEqual(ldt2) ? 0 : ldt1.isBefore(ldt2) ? 1 : -1;
        return sign * absResult;
    }

    private static long absoluteDifference(LocalDateTime ldt1, LocalDateTime ldt2, String unit)
    {
        switch (unit)
        {
            case "YEARS":
                return Math.abs((long) ldt1.getYear() - ldt2.getYear());
            case "MONTHS":
                return Math.abs((ldt1.getYear() * 12L + ldt1.getMonthValue()) - (ldt2.getYear() * 12L + ldt2.getMonthValue()));
            case "WEEKS":
                return weeksBetween(ldt1, ldt2);
            case "DAYS":
                return Math.abs(ChronoUnit.DAYS.between(ldt1.toLocalDate(), ldt2.toLocalDate()));
            case "HOURS":
                return Math.abs(ChronoUnit.MILLIS.between(ldt1, ldt2)) / 3600_000L;
            case "MINUTES":
                return Math.abs(ChronoUnit.MILLIS.between(ldt1, ldt2)) / 60_000L;
            case "SECONDS":
                return Math.abs(ChronoUnit.MILLIS.between(ldt1, ldt2)) / 1000L;
            case "MILLISECONDS":
                return Math.abs(ChronoUnit.MILLIS.between(ldt1, ldt2));
            case "MICROSECONDS":
                return Math.abs(ChronoUnit.MICROS.between(ldt1, ldt2));
            case "NANOSECONDS":
                return Math.abs(ChronoUnit.NANOS.between(ldt1, ldt2));
            default:
                throw new RuntimeException("Unknown duration unit: " + unit);
        }
    }

    /** Weeks as Pure counts them: the Sundays crossed going from ldt1 to ldt2. */
    private static long weeksBetween(LocalDateTime ldt1, LocalDateTime ldt2)
    {
        long absDiffDays = Math.abs(ChronoUnit.DAYS.between(ldt1.toLocalDate(), ldt2.toLocalDate()));
        int startDay = ldt1.get(ChronoField.DAY_OF_WEEK);
        int sundayIdx = startDay == 7 ? 1 : startDay + 1;
        int noDaysTillSunday = ldt1.isBefore(ldt2) ? 7 - (sundayIdx - 1) : sundayIdx - 1;
        return noDaysTillSunday > absDiffDays ? 0 : (absDiffDays - noDaysTillSunday) / 7 + (noDaysTillSunday > 0 ? 1 : 0);
    }

    public long millis()
    {
        int dot = value.indexOf('.');
        if (value.indexOf('T') < 0 || dot < 0)
        {
            return 0L;
        }
        return Long.parseLong((value.substring(dot + 1) + "000").substring(0, 3));
    }
}
