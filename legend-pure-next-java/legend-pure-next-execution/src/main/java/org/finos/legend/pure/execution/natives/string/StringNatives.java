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
import org.finos.legend.pure.execution.NativeRepository.LazyNativeImpl;
import org.finos.legend.pure.execution.NativeRepository.NativeImpl;
import org.finos.legend.pure.execution._E_ValueSpecification;
import org.finos.legend.pure.execution.ValueSpecificationEvaluator;
import meta.pure.metamodel.valuespecification.ValueSpecification;
import org.finos.legend.pure.m3.module.MetadataAccess;
import org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._GenericType;
import org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._Type;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

public class StringNatives
{
    public static void register(Map<String, NativeImpl> natives,
                                Map<String, LazyNativeImpl> lazyNatives,
                                MetadataAccess resolver)
    {
        natives.put("plus_String_1__String_1__String_1_", (args, eval, genericType, multiplicity) ->
                _E_ValueSpecification.wrap((String) _E_ValueSpecification.unwrap(args.get(0)) + (String) _E_ValueSpecification.unwrap(args.get(1)),
                        genericType, multiplicity, resolver));

        natives.put("toString_Any_1__String_1_", (args, eval, genericType, multiplicity) ->
        {
            Object val = _E_ValueSpecification.unwrap(args.get(0));
            return _E_ValueSpecification.wrap(pureToString(val, args.get(0), eval, resolver), genericType, multiplicity, resolver);
        });

        // joinStrings(String[*], String[1], String[1], String[1]) : String[1]
        natives.put("joinStrings_String_MANY__String_1__String_1__String_1__String_1_", (args, eval, genericType, multiplicity) ->
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
            return _E_ValueSpecification.wrap(sb.toString(), genericType, multiplicity, resolver);
        });

        // toRepresentation - returns the Pure-syntax representation of a value
        natives.put("toRepresentation_Any_1__String_1_", (args, eval, genericType, multiplicity) ->
        {
            Object val = _E_ValueSpecification.unwrap(args.get(0));
            return _E_ValueSpecification.wrap(pureToRepresentation(val, args.get(0), eval, resolver), genericType, multiplicity, resolver);
        });

        // format(String[1], Any[*]) : String[1]
        natives.put("format_String_1__Any_MANY__String_1_", (args, eval, genericType, multiplicity) ->
        {
            String formatStr = (String) _E_ValueSpecification.unwrap(args.get(0));
            // Keep VS wrappers so we can check genericType (e.g. Date vs String)
            List<ValueSpecification> formatArgs = _E_ValueSpecification.toCollection(args.get(1), resolver)._values();
            StringBuilder result = new StringBuilder();
            int argIdx = 0;
            int i = 0;
            while (i < formatStr.length())
            {
                char c = formatStr.charAt(i);
                if (c == '%' && i + 1 < formatStr.length())
                {
                    i++; // skip '%'
                    char spec = formatStr.charAt(i);

                    if (spec == 's')
                    {
                        // %s — toString
                        ValueSpecification argVs = formatArgs.get(argIdx++);
                        Object arg = _E_ValueSpecification.unwrap(argVs);
                        result.append(pureToString(arg, argVs, eval, resolver));
                        i++;
                    }
                    else if (spec == 'd' || (spec == '0' && i + 1 < formatStr.length()))
                    {
                        // %d or %0Nd — integer with optional zero-padding
                        int width = 0;
                        if (spec == '0')
                        {
                            i++;
                            StringBuilder widthBuf = new StringBuilder();
                            while (i < formatStr.length() && formatStr.charAt(i) != 'd')
                            {
                                widthBuf.append(formatStr.charAt(i));
                                i++;
                            }
                            width = Integer.parseInt(widthBuf.toString());
                        }
                        Object arg = _E_ValueSpecification.unwrap(formatArgs.get(argIdx++));
                        long val = ((Number) arg).longValue();
                        if (width > 0)
                        {
                            String abs = String.valueOf(Math.abs(val));
                            StringBuilder padded = new StringBuilder();
                            if (val < 0)
                            {
                                padded.append('-');
                            }
                            while (padded.length() + abs.length() < width + (val < 0 ? 1 : 0))
                            {
                                padded.append('0');
                            }
                            padded.append(abs);
                            result.append(padded);
                        }
                        else
                        {
                            result.append(val);
                        }
                        i++; // skip 'd'
                    }
                    else if (spec == 'f' || spec == '.')
                    {
                        // %f or %.Nf — float with optional precision
                        int precision = -1;
                        if (spec == '.')
                        {
                            i++;
                            StringBuilder precBuf = new StringBuilder();
                            while (i < formatStr.length() && Character.isDigit(formatStr.charAt(i)))
                            {
                                precBuf.append(formatStr.charAt(i));
                                i++;
                            }
                            precision = Integer.parseInt(precBuf.toString());
                        }
                        ValueSpecification argVs = formatArgs.get(argIdx++);
                        Object arg = _E_ValueSpecification.unwrap(argVs);
                        double val = ((Number) arg).doubleValue();
                        if (precision >= 0)
                        {
                            java.math.BigDecimal bd = new java.math.BigDecimal(String.valueOf(val));
                            bd = bd.setScale(precision, java.math.RoundingMode.HALF_UP);
                            result.append(bd.toPlainString());
                        }
                        else
                        {
                            result.append(pureToString(arg, argVs, eval, resolver));
                        }
                        i++; // skip 'f'
                    }
                    else if (spec == 'r')
                    {
                        // %r — Pure representation
                        ValueSpecification argVs = formatArgs.get(argIdx++);
                        Object arg = _E_ValueSpecification.unwrap(argVs);
                        result.append(pureToRepresentation(arg, argVs, eval, resolver));
                        i++;
                    }
                    else if (spec == 't')
                    {
                        // %t — date (simple) or %t{pattern} — formatted date
                        i++;
                        ValueSpecification argVs = formatArgs.get(argIdx++);
                        Object arg = _E_ValueSpecification.unwrap(argVs);
                        String dateStr = pureToString(arg, argVs, eval, resolver);
                        if (i < formatStr.length() && formatStr.charAt(i) == '{')
                        {
                            i++;
                            StringBuilder pattern = new StringBuilder();
                            while (i < formatStr.length() && formatStr.charAt(i) != '}')
                            {
                                pattern.append(formatStr.charAt(i));
                                i++;
                            }
                            i++; // skip '}'
                            result.append(formatDate(dateStr, pattern.toString()));
                        }
                        else
                        {
                            result.append(dateStr);
                        }
                    }
                    else
                    {
                        result.append('%').append(spec);
                        i++;
                    }
                }
                else
                {
                    result.append(c);
                    i++;
                }
            }
            return _E_ValueSpecification.wrap(result.toString(), genericType, multiplicity, resolver);
        });

        // length(String[1]) : Integer[1]
        natives.put("length_String_1__Integer_1_", (args, eval, genericType, multiplicity) ->
                _E_ValueSpecification.wrap((long) ((String) _E_ValueSpecification.unwrap(args.get(0))).length(), genericType, multiplicity, resolver));

        // startsWith(String[1], String[1]) : Boolean[1]
        natives.put("startsWith_String_1__String_1__Boolean_1_", (args, eval, genericType, multiplicity) ->
                _E_ValueSpecification.wrap(((String) _E_ValueSpecification.unwrap(args.get(0))).startsWith((String) _E_ValueSpecification.unwrap(args.get(1))),
                        genericType, multiplicity, resolver));

        // replace(String[1], String[1], String[1]) : String[1]
        natives.put("replace_String_1__String_1__String_1__String_1_", (args, eval, genericType, multiplicity) ->
        {
            String source = (String) _E_ValueSpecification.unwrap(args.get(0));
            String toReplace = (String) _E_ValueSpecification.unwrap(args.get(1));
            String replacement = (String) _E_ValueSpecification.unwrap(args.get(2));
            return _E_ValueSpecification.wrap(source.replace(toReplace, replacement), genericType, multiplicity, resolver);
        });

        // endsWith(String[1], String[1]) : Boolean[1]
        natives.put("endsWith_String_1__String_1__Boolean_1_", (args, eval, genericType, multiplicity) ->
                _E_ValueSpecification.wrap(((String) _E_ValueSpecification.unwrap(args.get(0))).endsWith((String) _E_ValueSpecification.unwrap(args.get(1))),
                        genericType, multiplicity, resolver));

        // contains(String[1], String[1]) : Boolean[1]
        natives.put("contains_String_1__String_1__Boolean_1_", (args, eval, genericType, multiplicity) ->
                _E_ValueSpecification.wrap(((String) _E_ValueSpecification.unwrap(args.get(0))).contains((String) _E_ValueSpecification.unwrap(args.get(1))),
                        genericType, multiplicity, resolver));

        // substring(String[1], Integer[1]) : String[1]
        natives.put("substring_String_1__Integer_1__String_1_", (args, eval, genericType, multiplicity) ->
        {
            String s = (String) _E_ValueSpecification.unwrap(args.get(0));
            int start = ((Long) _E_ValueSpecification.unwrap(args.get(1))).intValue();
            return _E_ValueSpecification.wrap(s.substring(start), genericType, multiplicity, resolver);
        });

        // substring(String[1], Integer[1], Integer[1]) : String[1]
        natives.put("substring_String_1__Integer_1__Integer_1__String_1_", (args, eval, genericType, multiplicity) ->
        {
            String s = (String) _E_ValueSpecification.unwrap(args.get(0));
            int start = ((Long) _E_ValueSpecification.unwrap(args.get(1))).intValue();
            int end = ((Long) _E_ValueSpecification.unwrap(args.get(2))).intValue();
            return _E_ValueSpecification.wrap(s.substring(start, end), genericType, multiplicity, resolver);
        });

        // split(String[1], String[1]) : String[*]
        natives.put("split_String_1__String_1__String_MANY_", (args, eval, genericType, multiplicity) ->
        {
            String s = (String) _E_ValueSpecification.unwrap(args.get(0));
            String delimiter = (String) _E_ValueSpecification.unwrap(args.get(1));
            String[] parts = s.split(Pattern.quote(delimiter), -1);
            List<Object> result = new ArrayList<>();
            for (String part : parts)
            {
                result.add(part);
            }
            return _E_ValueSpecification.wrap(result, genericType, multiplicity, resolver);
        });

        // trim(String[1]) : String[1]
        natives.put("trim_String_1__String_1_", (args, eval, genericType, multiplicity) ->
                _E_ValueSpecification.wrap(((String) _E_ValueSpecification.unwrap(args.get(0))).trim(), genericType, multiplicity, resolver));

        // ltrim(String[1]) : String[1]
        natives.put("ltrim_String_1__String_1_", (args, eval, genericType, multiplicity) ->
                _E_ValueSpecification.wrap(((String) _E_ValueSpecification.unwrap(args.get(0))).stripLeading(), genericType, multiplicity, resolver));

        // rtrim(String[1]) : String[1]
        natives.put("rtrim_String_1__String_1_", (args, eval, genericType, multiplicity) ->
                _E_ValueSpecification.wrap(((String) _E_ValueSpecification.unwrap(args.get(0))).stripTrailing(), genericType, multiplicity, resolver));

        // toLower(String[1]) : String[1]
        natives.put("toLower_String_1__String_1_", (args, eval, genericType, multiplicity) ->
                _E_ValueSpecification.wrap(((String) _E_ValueSpecification.unwrap(args.get(0))).toLowerCase(), genericType, multiplicity, resolver));

        // toUpper(String[1]) : String[1]
        natives.put("toUpper_String_1__String_1_", (args, eval, genericType, multiplicity) ->
                _E_ValueSpecification.wrap(((String) _E_ValueSpecification.unwrap(args.get(0))).toUpperCase(), genericType, multiplicity, resolver));

        // reverseString(String[1]) : String[1]
        natives.put("reverseString_String_1__String_1_", (args, eval, genericType, multiplicity) ->
                _E_ValueSpecification.wrap(new StringBuilder((String) _E_ValueSpecification.unwrap(args.get(0))).reverse().toString(), genericType, multiplicity, resolver));

        // indexOf(String[1], String[1]) : Integer[1]
        natives.put("indexOf_String_1__String_1__Integer_1_", (args, eval, genericType, multiplicity) ->
        {
            String s = (String) _E_ValueSpecification.unwrap(args.get(0));
            String search = (String) _E_ValueSpecification.unwrap(args.get(1));
            return _E_ValueSpecification.wrap((long) s.indexOf(search), genericType, multiplicity, resolver);
        });

        // indexOf(String[1], String[1], Integer[1]) : Integer[1]
        natives.put("indexOf_String_1__String_1__Integer_1__Integer_1_", (args, eval, genericType, multiplicity) ->
        {
            String s = (String) _E_ValueSpecification.unwrap(args.get(0));
            String search = (String) _E_ValueSpecification.unwrap(args.get(1));
            int from = ((Long) _E_ValueSpecification.unwrap(args.get(2))).intValue();
            return _E_ValueSpecification.wrap((long) s.indexOf(search, from), genericType, multiplicity, resolver);
        });

        // parseInteger(String[1]) : Integer[1]
        natives.put("parseInteger_String_1__Integer_1_", (args, eval, genericType, multiplicity) ->
                _E_ValueSpecification.wrap(Long.parseLong(((String) _E_ValueSpecification.unwrap(args.get(0))).trim()), genericType, multiplicity, resolver));

        // parseFloat(String[1]) : Float[1]
        natives.put("parseFloat_String_1__Float_1_", (args, eval, genericType, multiplicity) ->
                _E_ValueSpecification.wrap(Double.parseDouble(((String) _E_ValueSpecification.unwrap(args.get(0))).trim()), genericType, multiplicity, resolver));

        // parseDecimal(String[1]) : Decimal[1]  — represented as Double in this engine
        natives.put("parseDecimal_String_1__Decimal_1_", (args, eval, genericType, multiplicity) ->
        {
            double d = Double.parseDouble(((String) _E_ValueSpecification.unwrap(args.get(0))).trim());
            if (d == 0.0) d = 0.0; // normalize -0.0 to 0.0
            return _E_ValueSpecification.wrap(d, genericType, multiplicity, resolver);
        });

        // parseDecimal(String[1], Integer[1], Integer[1]) : Decimal[1]
        natives.put("parseDecimal_String_1__Integer_1__Integer_1__Decimal_1_", (args, eval, genericType, multiplicity) ->
        {
            String str = ((String) _E_ValueSpecification.unwrap(args.get(0))).trim();
            int scale = ((Number) _E_ValueSpecification.unwrap(args.get(2))).intValue();
            double d = new java.math.BigDecimal(str)
                    .setScale(scale, java.math.RoundingMode.HALF_UP)
                    .doubleValue();
            if (d == 0.0) d = 0.0;
            return _E_ValueSpecification.wrap(d, genericType, multiplicity, resolver);
        });

        // parseBoolean(String[1]) : Boolean[1]
        natives.put("parseBoolean_String_1__Boolean_1_", (args, eval, genericType, multiplicity) ->
                _E_ValueSpecification.wrap(Boolean.parseBoolean(((String) _E_ValueSpecification.unwrap(args.get(0))).trim()), genericType, multiplicity, resolver));

        // parseDate(String[1]) : Date[1]
        natives.put("parseDate_String_1__Date_1_", (args, eval, genericType, multiplicity) ->
        {
            String dateStr = ((String) _E_ValueSpecification.unwrap(args.get(0))).trim();
            String result = normalizePureDate(dateStr);
            // Strip trailing Z — Pure dates don't use Z suffix
            if (result != null && result.endsWith("Z"))
            {
                result = result.substring(0, result.length() - 1);
            }
            return _E_ValueSpecification.wrap(result, genericType, multiplicity, resolver);
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

    /**
     * Convert a raw Java value to its Pure string representation.
     * Checks for class-defined toString() qualified properties on DynamicInstances,
     * handles Date and Float formatting, falls back to String.valueOf().
     */
    private static String pureToString(Object val,
                                       meta.pure.metamodel.valuespecification.ValueSpecification vs,
                                       ValueSpecificationEvaluator eval,
                                       MetadataAccess resolver)
    {
        // DynamicInstance with class-defined toString() qualified property
        if (val instanceof DynamicInstance di && di.getClassifierGenericType() != null)
        {
            meta.pure.metamodel.type.Type classType = _GenericType.type(di.getClassifierGenericType());
            if (classType instanceof meta.pure.metamodel.type.Class cls && cls._qualifiedProperties() != null)
            {
                for (var qp : cls._qualifiedProperties())
                {
                    if ("toString".equals(qp._name())
                            && qp._parameters() != null
                            && qp._parameters().size() == 1)
                    {
                        ValueSpecification wrappedSelf = _E_ValueSpecification.wrap(val, di.getClassifierGenericType(), null, resolver);
                        ValueSpecification result = eval.executeFunction(qp, List.of(wrappedSelf));
                        return (String) _E_ValueSpecification.unwrap(result);
                    }
                }
            }
        }

        // Use Pure type system when VS is available
        if (vs != null)
        {
            Type argType = _GenericType.type(vs._genericType());
            if (_Type.subtypeOf(argType, (Type) resolver.getElement("meta::pure::metamodel::type::primitives::Date"), resolver))
            {
                return normalizePureDate(val.toString());
            }
            if (_Type.subtypeOf(argType, (Type) resolver.getElement("meta::pure::metamodel::type::primitives::Float"), resolver))
            {
                java.math.BigDecimal bd = java.math.BigDecimal.valueOf((Double) val);
                String plain = bd.stripTrailingZeros().toPlainString();
                if (!plain.contains("."))
                {
                    plain += ".0";
                }
                return plain;
            }
        }

        if (val instanceof meta.pure.metamodel.type.Enum e)
        {
            return e._name();
        }
        if (val instanceof meta.pure.metamodel.PackageableElement pe)
        {
            return pe._name();
        }

        return String.valueOf(val);
    }

    /**
     * Convert a raw Java value to its Pure representation (as used by toRepresentation and %r).
     * Uses Pure type system from VS to correctly identify types (esp. Date vs String).
     */
    private static String pureToRepresentation(Object val,
                                               meta.pure.metamodel.valuespecification.ValueSpecification vs,
                                               ValueSpecificationEvaluator eval,
                                               MetadataAccess resolver)
    {
        // Use Pure type system when VS is available
        if (vs != null)
        {
            Type argType = _GenericType.type(vs._genericType());
            if (_Type.subtypeOf(argType, (Type) resolver.getElement("meta::pure::metamodel::type::primitives::Date"), resolver))
            {
                return "%" + normalizePureDate(val.toString());
            }
            if (_Type.subtypeOf(argType, (Type) resolver.getElement("meta::pure::metamodel::type::primitives::String"), resolver))
            {
                String s = (String) val;
                return "'" + s.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n") + "'";
            }
            if (_Type.subtypeOf(argType, (Type) resolver.getElement("meta::pure::metamodel::type::primitives::Boolean"), resolver))
            {
                return val.toString();
            }
            if (_Type.subtypeOf(argType, (Type) resolver.getElement("meta::pure::metamodel::type::primitives::Integer"), resolver))
            {
                return val.toString();
            }
            if (_Type.subtypeOf(argType, (Type) resolver.getElement("meta::pure::metamodel::type::primitives::Float"), resolver))
            {
                java.math.BigDecimal bd = java.math.BigDecimal.valueOf((Double) val);
                String plain = bd.stripTrailingZeros().toPlainString();
                if (!plain.contains("."))
                {
                    plain += ".0";
                }
                return plain;
            }
        }
        // Fallback for non-primitive types or missing VS
        if (val instanceof DynamicInstance di)
        {
            String str = pureToString(val, vs, eval, resolver);
            if (str.startsWith("[") || str.startsWith("<"))
            {
                return "<" + str + " instanceOf " + di.getClassPath() + ">";
            }
            return "<" + di.getId() + " instanceOf " + di.getClassPath() + ">";
        }
        if (val instanceof PackageableElement pe)
        {
            return org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._PackageableElement.path(pe);
        }
        return pureToString(val, vs, eval, resolver);
    }

    /**
     * Format a Pure date string using the given pattern.
     * Supports timezone override via [TZ] prefix, e.g. "[EST]yyyy-MM-dd HH:mm:ss.SSSZ".
     * Pure dates are assumed UTC if no offset is present.
     */
    private static String formatDate(String dateStr, String pattern)
    {
        try
        {
            // Extract optional timezone from pattern: [TZ]restOfPattern
            java.time.ZoneId targetZone = java.time.ZoneId.of("UTC");
            String datePattern = pattern;
            if (pattern.startsWith("["))
            {
                int close = pattern.indexOf(']');
                if (close > 0)
                {
                    String tz = pattern.substring(1, close);
                    targetZone = java.time.ZoneId.of(tz, java.time.ZoneId.SHORT_IDS);
                    datePattern = pattern.substring(close + 1);
                }
            }

            // Convert Pure double-quoted literals to Java single-quoted
            // e.g. yyyy-MM-dd"T"HH:mm:ss → yyyy-MM-dd'T'HH:mm:ss
            datePattern = datePattern.replace('"', '\'');

            // Normalize Pure date: add timezone if missing, handle date-only
            String normalized = dateStr;
            if (!normalized.contains("T"))
            {
                normalized += "T00:00:00.000+0000";
            }
            else if (!normalized.contains("+") && normalized.indexOf("-", normalized.indexOf("T")) < 0)
            {
                normalized += "+0000";
            }

            // Parse with flexible formatter that handles variable fractional seconds
            java.time.format.DateTimeFormatter parser = new java.time.format.DateTimeFormatterBuilder()
                    .appendPattern("yyyy-MM-dd'T'HH:mm:ss")
                    .optionalStart()
                    .appendFraction(java.time.temporal.ChronoField.NANO_OF_SECOND, 1, 9, true)
                    .optionalEnd()
                    .appendPattern("Z")
                    .toFormatter();
            java.time.ZonedDateTime zdt = java.time.ZonedDateTime.parse(normalized, parser);
            zdt = zdt.withZoneSameInstant(targetZone);

            java.time.format.DateTimeFormatter formatter = java.time.format.DateTimeFormatter.ofPattern(datePattern);
            return zdt.format(formatter);
        }
        catch (Exception e)
        {
            return dateStr;
        }
    }
}
