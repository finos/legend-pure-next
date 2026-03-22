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

package org.finos.legend.pure.execution.natives.string;

import meta.pure.metamodel.PackageableElement;
import meta.pure.metamodel.type.Type;
import org.finos.legend.pure.execution.DynamicInstance;
import org.finos.legend.pure.execution.NativeRepository.NativeImpl;
import org.finos.legend.pure.execution.NativeRepository.LazyNativeImpl;
import org.finos.legend.pure.execution._E_ValueSpecification;
import org.finos.legend.pure.m3.module.MetadataAccess;
import org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._GenericType;
import org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._Type;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

public class StringNatives
{
    public static void register(Map<String, NativeImpl> natives,
                                Map<String, LazyNativeImpl> lazyNatives,
                                MetadataAccess resolver)
    {
        natives.put("plus_String_1__String_1__String_1_", (args, eval, fe) ->
                _E_ValueSpecification.wrap((String) _E_ValueSpecification.unwrap(args.get(0)) + (String) _E_ValueSpecification.unwrap(args.get(1)),
                        fe._genericType(), fe._multiplicity()));

        natives.put("toString_Any_1__String_1_", (args, eval, fe) ->
        {
            meta.pure.metamodel.valuespecification.ValueSpecification argVs = args.get(0);
            Object val = _E_ValueSpecification.unwrap(argVs);
            Type argType = _GenericType.type(argVs._genericType());
            if (_Type.subtypeOf(argType, (Type) resolver.getElement("meta::pure::metamodel::type::primitives::Date"), resolver))
            {
                return _E_ValueSpecification.wrap(normalizePureDate(val.toString()), fe._genericType(), fe._multiplicity());
            }
            if (_Type.subtypeOf(argType, (Type) resolver.getElement("meta::pure::metamodel::type::primitives::Float"), resolver))
            {
                java.math.BigDecimal bd = java.math.BigDecimal.valueOf((Double) val);
                String plain = bd.stripTrailingZeros().toPlainString();
                if (!plain.contains("."))
                {
                    plain += ".0";
                }
                return _E_ValueSpecification.wrap(plain, fe._genericType(), fe._multiplicity());
            }
            return _E_ValueSpecification.wrap(String.valueOf(val), fe._genericType(), fe._multiplicity());
        });

        // joinStrings(String[*], String[1], String[1], String[1]) : String[1]
        natives.put("joinStrings_String_MANY__String_1__String_1__String_1__String_1_", (args, eval, fe) ->
        {
            String prefix = (String) _E_ValueSpecification.unwrap(args.get(1));
            String separator = (String) _E_ValueSpecification.unwrap(args.get(2));
            String suffix = (String) _E_ValueSpecification.unwrap(args.get(3));
            StringBuilder sb = new StringBuilder();
            if (prefix != null)
            {
                sb.append(prefix);
            }
            List<?> list = (List<?>) _E_ValueSpecification.unwrap(_E_ValueSpecification.toCollection(args.get(0), resolver));
            for (int i = 0; i < list.size(); i++)
            {
                if (i > 0 && separator != null)
                {
                    sb.append(separator);
                }
                sb.append(list.get(i));
            }
            if (suffix != null)
            {
                sb.append(suffix);
            }
            return _E_ValueSpecification.wrap(sb.toString(), fe._genericType(), fe._multiplicity());
        });

        // toRepresentation - returns the Pure-syntax representation of a value
        natives.put("toRepresentation_Any_1__String_1_", (args, eval, fe) ->
        {
            meta.pure.metamodel.valuespecification.ValueSpecification argVs = args.get(0);
            Object val = _E_ValueSpecification.unwrap(argVs);
            Type argType = _GenericType.type(argVs._genericType());
            if (_Type.subtypeOf(argType, (Type) resolver.getElement("meta::pure::metamodel::type::primitives::String"), resolver))
            {
                String s = (String) val;
                return _E_ValueSpecification.wrap("'" + s.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n") + "'", fe._genericType(), fe._multiplicity());
            }
            if (_Type.subtypeOf(argType, (Type) resolver.getElement("meta::pure::metamodel::type::primitives::Integer"), resolver))
            {
                return _E_ValueSpecification.wrap(((Long) val).toString(), fe._genericType(), fe._multiplicity());
            }
            if (_Type.subtypeOf(argType, (Type) resolver.getElement("meta::pure::metamodel::type::primitives::Float"), resolver))
            {
                java.math.BigDecimal bd = java.math.BigDecimal.valueOf((Double) val);
                String plain = bd.stripTrailingZeros().toPlainString();
                // Ensure it has a decimal point for float representation
                if (!plain.contains("."))
                {
                    plain += ".0";
                }
                return _E_ValueSpecification.wrap(plain, fe._genericType(), fe._multiplicity());
            }
            if (_Type.subtypeOf(argType, (Type) resolver.getElement("meta::pure::metamodel::type::primitives::Date"), resolver))
            {
                // Date values are stored without % prefix; add % for Pure representation
                return _E_ValueSpecification.wrap("%" + normalizePureDate(val.toString()), fe._genericType(), fe._multiplicity());
            }
            if (val instanceof DynamicInstance di)
            {
                return _E_ValueSpecification.wrap("<" + di.getId() + " instanceOf " + di.getClassPath().replace("::", "::") + ">",
                        fe._genericType(), fe._multiplicity());
            }
            if (val instanceof PackageableElement pe)
            {
                return _E_ValueSpecification.wrap(
                        org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._PackageableElement.path(pe).replace("::", "::"),
                        fe._genericType(), fe._multiplicity());
            }
            return _E_ValueSpecification.wrap(String.valueOf(val), fe._genericType(), fe._multiplicity());
        });

        // format(String[1], Any[*]) : String[1]
        natives.put("format_String_1__Any_MANY__String_1_", (args, eval, fe) ->
        {
            String formatStr = (String) _E_ValueSpecification.unwrap(args.get(0));
            List<?> formatArgs = (List<?>) _E_ValueSpecification.unwrap(_E_ValueSpecification.toCollection(args.get(1), resolver));
            // Simple %s / %d / %f replacement
            StringBuilder result = new StringBuilder();
            int argIdx = 0;
            for (int i = 0; i < formatStr.length(); i++)
            {
                char c = formatStr.charAt(i);
                if (c == '%' && i + 1 < formatStr.length())
                {
                    char next = formatStr.charAt(i + 1);
                    if (next == 's' || next == 'd' || next == 'f' || next == 'r')
                    {
                        if (argIdx < formatArgs.size())
                        {
                            Object arg = formatArgs.get(argIdx++);
                            if (next == 'r' && arg instanceof String s)
                            {
                                result.append("'").append(s).append("'");
                            }
                            else
                            {
                                result.append(arg);
                            }
                        }
                        i++; // skip format char
                        continue;
                    }
                }
                result.append(c);
            }
            return _E_ValueSpecification.wrap(result.toString(), fe._genericType(), fe._multiplicity());
        });

        // length(String[1]) : Integer[1]
        natives.put("length_String_1__Integer_1_", (args, eval, fe) ->
                _E_ValueSpecification.wrap((long) ((String) _E_ValueSpecification.unwrap(args.get(0))).length(), fe._genericType(), fe._multiplicity()));

        // startsWith(String[1], String[1]) : Boolean[1]
        natives.put("startsWith_String_1__String_1__Boolean_1_", (args, eval, fe) ->
                _E_ValueSpecification.wrap(((String) _E_ValueSpecification.unwrap(args.get(0))).startsWith((String) _E_ValueSpecification.unwrap(args.get(1))),
                        fe._genericType(), fe._multiplicity()));

        // replace(String[1], String[1], String[1]) : String[1]
        natives.put("replace_String_1__String_1__String_1__String_1_", (args, eval, fe) ->
        {
            String source = (String) _E_ValueSpecification.unwrap(args.get(0));
            String toReplace = (String) _E_ValueSpecification.unwrap(args.get(1));
            String replacement = (String) _E_ValueSpecification.unwrap(args.get(2));
            return _E_ValueSpecification.wrap(source.replace(toReplace, replacement), fe._genericType(), fe._multiplicity());
        });
    }

    // =========================================================================
    // Date normalization helpers
    // =========================================================================

    private static final Pattern PURE_DATE_PATTERN = Pattern.compile("^%\\d{4}-\\d{2}-\\d{2}(T\\d{2}:\\d{2}(:\\d{2}(\\.\\d+)?)([+-]\\d{4})?)?$");

    static boolean isPureDateString(String s)
    {
        return PURE_DATE_PATTERN.matcher(s).matches();
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

        // Check if this has a time component (contains T)
        int tIdx = dateStr.indexOf('T');
        if (tIdx < 0)
        {
            // StrictDate: YYYY-MM-DD — just zero-pad
            return zeroPadDate(dateStr);
        }

        // DateTime: parse, convert to UTC, format
        try
        {
            // Split date and time+tz
            String datePart = dateStr.substring(0, tIdx);
            String timeAndTz = dateStr.substring(tIdx + 1);

            // Extract timezone if present
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

            // Parse date components
            String[] dp = datePart.split("-");
            int year = Integer.parseInt(dp[0]);
            int month = dp.length > 1 ? Integer.parseInt(dp[1]) : 1;
            int day = dp.length > 2 ? Integer.parseInt(dp[2]) : 1;

            // Parse time components
            String[] tp = timePart.split(":");
            int hour = Integer.parseInt(tp[0]);
            int minute = tp.length > 1 ? Integer.parseInt(tp[1]) : 0;
            boolean hasSeconds = tp.length > 2;

            // Seconds may have fractional part
            int second = 0;
            String fracStr = "";
            if (hasSeconds)
            {
                String secPart = tp[2];
                int dotIdx = secPart.indexOf('.');
                if (dotIdx >= 0)
                {
                    second = Integer.parseInt(secPart.substring(0, dotIdx));
                    fracStr = secPart.substring(dotIdx); // includes the dot
                }
                else
                {
                    second = Integer.parseInt(secPart);
                }
            }

            // If we have a timezone, convert to UTC
            if (tzStr != null)
            {
                // Parse tz offset in ±HHMM format
                int tzSign = tzStr.charAt(0) == '-' ? -1 : 1;
                int tzHours = Integer.parseInt(tzStr.substring(1, 3));
                int tzMinutes = tzStr.length() > 3 ? Integer.parseInt(tzStr.substring(3, 5)) : 0;
                int tzOffsetMinutes = tzSign * (tzHours * 60 + tzMinutes);

                if (tzOffsetMinutes != 0)
                {
                    // Convert to UTC using java.time
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

            // Format with zero-padding, preserving original precision
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
            // Fallback: return as-is
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
