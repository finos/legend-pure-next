package org.finos.legend.pure.execution.natives.date;

import org.finos.legend.pure.execution.NativeRepository.LazyNativeImpl;
import org.finos.legend.pure.execution.NativeRepository.NativeImpl;
import org.finos.legend.pure.execution._E_ValueSpecification;
import org.finos.legend.pure.m3.module.MetadataAccess;
import org.finos.legend.pure.execution.natives.string.StringNatives;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.temporal.ChronoField;
import java.time.temporal.ChronoUnit;
import java.util.Map;

public class DateNatives
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

    public static void register(Map<String, NativeImpl> natives,
                                Map<String, LazyNativeImpl> lazyNatives,
                                MetadataAccess resolver)
    {
        natives.put("year_Date_1__Integer_1_", (args, eval, genericType, multiplicity) ->
        {
            String dateStr = org.finos.legend.pure.execution.natives.string.StringNatives.normalizePureDate((String) _E_ValueSpecification.unwrap(args.get(0)));
            return _E_ValueSpecification.wrap((long) parseDate(dateStr).getYear(), genericType, multiplicity, resolver);
        });

        natives.put("monthNumber_Date_1__Integer_1_", (args, eval, genericType, multiplicity) ->
        {
            String dateStr = org.finos.legend.pure.execution.natives.string.StringNatives.normalizePureDate((String) _E_ValueSpecification.unwrap(args.get(0)));
            return _E_ValueSpecification.wrap((long) parseDate(dateStr).getMonthValue(), genericType, multiplicity, resolver);
        });

        natives.put("dayOfMonth_Date_1__Integer_1_", (args, eval, genericType, multiplicity) ->
        {
            String dateStr = org.finos.legend.pure.execution.natives.string.StringNatives.normalizePureDate((String) _E_ValueSpecification.unwrap(args.get(0)));
            return _E_ValueSpecification.wrap((long) parseDate(dateStr).getDayOfMonth(), genericType, multiplicity, resolver);
        });

        natives.put("hour_DateTime_1__Integer_1_", (args, eval, genericType, multiplicity) ->
        {
            String dateStr = org.finos.legend.pure.execution.natives.string.StringNatives.normalizePureDate((String) _E_ValueSpecification.unwrap(args.get(0)));
            return _E_ValueSpecification.wrap((long) parseDate(dateStr).getHour(), genericType, multiplicity, resolver);
        });

        natives.put("minute_DateTime_1__Integer_1_", (args, eval, genericType, multiplicity) ->
        {
            String dateStr = org.finos.legend.pure.execution.natives.string.StringNatives.normalizePureDate((String) _E_ValueSpecification.unwrap(args.get(0)));
            return _E_ValueSpecification.wrap((long) parseDate(dateStr).getMinute(), genericType, multiplicity, resolver);
        });

        natives.put("second_DateTime_1__Integer_1_", (args, eval, genericType, multiplicity) ->
        {
            String dateStr = org.finos.legend.pure.execution.natives.string.StringNatives.normalizePureDate((String) _E_ValueSpecification.unwrap(args.get(0)));
            return _E_ValueSpecification.wrap((long) parseDate(dateStr).getSecond(), genericType, multiplicity, resolver);
        });

        natives.put("millis_DateTime_1__Integer_1_", (args, eval, genericType, multiplicity) ->
        {
            String dateStr = org.finos.legend.pure.execution.natives.string.StringNatives.normalizePureDate((String) _E_ValueSpecification.unwrap(args.get(0)));
            return _E_ValueSpecification.wrap((long) (parseDate(dateStr).getNano() / 1_000_000), genericType, multiplicity, resolver);
        });

        natives.put("datePart_Date_1__StrictDate_1_", (args, eval, genericType, multiplicity) ->
        {
            String dateStr = (String) _E_ValueSpecification.unwrap(args.get(0));
            String datePart = dateStr.contains("T") ? dateStr.substring(0, dateStr.indexOf('T')) : dateStr;
            if (datePart.length() == 4) {
                datePart += "-01-01";
            } else if (datePart.length() == 7) {
                datePart += "-01";
            }
            return _E_ValueSpecification.wrap(datePart, genericType, multiplicity, resolver);
        });

        natives.put("dateDiff_Date_1__Date_1__DurationUnit_1__Integer_1_", (args, eval, genericType, multiplicity) ->
        {
            LocalDateTime d1 = parseDate(org.finos.legend.pure.execution.natives.string.StringNatives.normalizePureDate((String) _E_ValueSpecification.unwrap(args.get(0))));
            LocalDateTime d2 = parseDate(org.finos.legend.pure.execution.natives.string.StringNatives.normalizePureDate((String) _E_ValueSpecification.unwrap(args.get(1))));
            String unitStr = ((meta.pure.metamodel.type.Enum) _E_ValueSpecification.unwrap(args.get(2)))._name();
            
            long absOpResult = 0;
            switch (unitStr)
            {
                case "YEARS":
                    absOpResult = Math.abs((long) d1.getYear() - d2.getYear());
                    break;
                case "MONTHS":
                    long m1 = d1.getYear() * 12L + d1.getMonthValue();
                    long m2 = d2.getYear() * 12L + d2.getMonthValue();
                    absOpResult = Math.abs(m1 - m2);
                    break;
                case "WEEKS":
                {
                    long absDiffDays = Math.abs(ChronoUnit.DAYS.between(d1.toLocalDate(), d2.toLocalDate()));
                    int startDay = d1.get(ChronoField.DAY_OF_WEEK);
                    int sundayIdx = startDay == 7 ? 1 : startDay + 1; // 1 = Sunday, 2 = Monday, ...
                    int noDaysTillSunday;
                    if (d1.isBefore(d2)) {
                        noDaysTillSunday = 7 - (sundayIdx - 1);
                    } else {
                        noDaysTillSunday = sundayIdx - 1;
                    }
                    if (noDaysTillSunday > absDiffDays) {
                        absOpResult = 0;
                    } else {
                        long fullWeeks = (absDiffDays - noDaysTillSunday) / 7;
                        long partialWeek = noDaysTillSunday > 0 ? 1 : 0;
                        absOpResult = fullWeeks + partialWeek;
                    }
                    break;
                }
                case "DAYS":
                    absOpResult = Math.abs(ChronoUnit.DAYS.between(d1.toLocalDate(), d2.toLocalDate()));
                    break;
                case "HOURS":
                    absOpResult = Math.abs(ChronoUnit.MILLIS.between(d1, d2)) / 3600_000L;
                    break;
                case "MINUTES":
                    absOpResult = Math.abs(ChronoUnit.MILLIS.between(d1, d2)) / 60_000L;
                    break;
                case "SECONDS":
                    absOpResult = Math.abs(ChronoUnit.MILLIS.between(d1, d2)) / 1000L;
                    break;
                case "MILLISECONDS":
                    absOpResult = Math.abs(ChronoUnit.MILLIS.between(d1, d2));
                    break;
                case "MICROSECONDS":
                    absOpResult = Math.abs(ChronoUnit.MICROS.between(d1, d2));
                    break;
                case "NANOSECONDS":
                    absOpResult = Math.abs(ChronoUnit.NANOS.between(d1, d2));
                    break;
                default:
                    throw new RuntimeException("Unsupported DurationUnit: " + unitStr);
            }
            
            int sign = d1.isEqual(d2) ? 0 : d1.isBefore(d2) ? 1 : -1;
            return _E_ValueSpecification.wrap(sign * absOpResult, genericType, multiplicity, resolver);
        });

        natives.put("adjust_Date_1__Integer_1__DurationUnit_1__Date_1_", (args, eval, genericType, multiplicity) ->
        {
            String originalDateStr = (String) _E_ValueSpecification.unwrap(args.get(0));
            String dateStr = org.finos.legend.pure.execution.natives.string.StringNatives.normalizePureDate(originalDateStr);
            LocalDateTime d1 = parseDate(dateStr);
            long amount = (Long) _E_ValueSpecification.unwrap(args.get(1));
            String unitStr = ((meta.pure.metamodel.type.Enum) _E_ValueSpecification.unwrap(args.get(2)))._name();
            
            LocalDateTime result = switch (unitStr)
            {
                case "YEARS" -> d1.plusYears(amount);
                case "MONTHS" -> d1.plusMonths(amount);
                case "WEEKS" -> d1.plusWeeks(amount);
                case "DAYS" -> d1.plusDays(amount);
                case "HOURS" -> d1.plusHours(amount);
                case "MINUTES" -> d1.plusMinutes(amount);
                case "SECONDS" -> d1.plusSeconds(amount);
                case "MILLISECONDS" -> d1.plus(amount, ChronoUnit.MILLIS);
                case "MICROSECONDS" -> d1.plus(amount, ChronoUnit.MICROS);
                case "NANOSECONDS" -> d1.plus(amount, ChronoUnit.NANOS);
                default -> throw new RuntimeException("Unsupported DurationUnit: " + unitStr);
            };
            
            String formatted = null;
            if (!originalDateStr.contains("T")) {
                if (originalDateStr.length() == 4 && unitStr.equals("YEARS")) {
                    formatted = String.format("%04d", result.getYear());
                } else {
                    formatted = String.format("%04d-%02d-%02d", result.getYear(), result.getMonthValue(), result.getDayOfMonth());
                }
            } else {
                 String res = String.format("%04d-%02d-%02dT%02d:%02d:%02d", result.getYear(), result.getMonthValue(), result.getDayOfMonth(), result.getHour(), result.getMinute(), result.getSecond());
                 if (result.getNano() > 0 || originalDateStr.contains(".")) {
                     String nanos = String.format("%09d", result.getNano());
                     int originalFracLen = 0;
                     int dotIdx = originalDateStr.indexOf('.');
                     if (dotIdx != -1) {
                         int expectedEnd = dotIdx + 1;
                         while (expectedEnd < originalDateStr.length() && Character.isDigit(originalDateStr.charAt(expectedEnd))) {
                             expectedEnd++;
                         }
                         originalFracLen = expectedEnd - (dotIdx + 1);
                     }
                     int end = nanos.length();
                     while (end > originalFracLen && end > 0 && nanos.charAt(end - 1) == '0') {
                         end--;
                     }
                     if (end > 0) {
                         res += "." + nanos.substring(0, end);
                     }
                 }
                 
                 // If original date had +timezone, preserve it or just output +0000?
                 // Standard pure parser handles it.
                 formatted = res;
            }
            if (formatted != null && formatted.startsWith("+")) {
                formatted = formatted.substring(1);
            }
            // Strip .000 if not in original or something? no, PURE_DATE_FORMATTER formats 10:11 as 10:11.
            return _E_ValueSpecification.wrap(formatted, genericType, multiplicity, resolver);
        });
    }

    private static LocalDateTime parseDate(String pureDateStr)
    {
        if (pureDateStr.endsWith("Z") || pureDateStr.matches(".*[+-]\\d{4}$"))
        {
            return OffsetDateTime.parse(pureDateStr, PURE_DATE_FORMATTER).toLocalDateTime();
        }
        else if (pureDateStr.contains("T"))
        {
            return LocalDateTime.parse(pureDateStr, PURE_DATE_FORMATTER);
        }
        else
        {
            // Just year-month or year: pad to full date for LocalDate
            if (pureDateStr.length() == 4)
            {
                pureDateStr += "-01-01";
            }
            else if (pureDateStr.length() == 7)
            {
                pureDateStr += "-01";
            }
            else if (pureDateStr.endsWith("-00-00"))
            {
                pureDateStr = pureDateStr.substring(0, pureDateStr.length() - 6) + "-01-01";
            }
            else if (pureDateStr.endsWith("-00"))
            {
                pureDateStr = pureDateStr.substring(0, pureDateStr.length() - 3) + "-01";
            }
            
            return LocalDate.parse(pureDateStr, PURE_DATE_FORMATTER).atStartOfDay();
        }
    }
}
