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
import meta.pure.metamodel.valuespecification.ValueSpecification;
import org.finos.legend.pure.execution.DynamicInstance;
import org.finos.legend.pure.execution.NativeRepository;
import org.finos.legend.pure.execution.NativeRepository.LazyNativeImpl;
import org.finos.legend.pure.execution.NativeRepository.NativeImpl;
import org.finos.legend.pure.execution.ValueSpecificationEvaluator;
import org.finos.legend.pure.execution._E_ValueSpecification;
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
                {
                    return _E_ValueSpecification.wrap((String) _E_ValueSpecification.unwrap(args.get(0)) + (String) _E_ValueSpecification.unwrap(args.get(1)),
                            genericType, multiplicity, resolver);
                });


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
            List<? extends ValueSpecification> items = _E_ValueSpecification.toCollection(args.get(0), resolver)._values();
            for (int i = 0; i < items.size(); i++)
            {
                if (i > 0 && separator != null)
                {
                    sb.append(separator);
                }
                // pureToString auto-unwraps the VS
                sb.append(NativeRepository.pureToString(items.get(i)));
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

        // unescapePureString(String[1]) : String[1]
        // Char-by-char unescape of Pure string-literal escapes. The Pure-side
        // chained-replace approach can't handle `\\` correctly: it falls apart
        // under self-host because each iteration distorts the unescape patterns
        // it stores for the next iteration. Expose as a native that mirrors
        // ValueSpecificationCompiler.unescapePureString on the Java side.
        natives.put("unescapePureString_String_1__String_1_", (args, eval, genericType, multiplicity) ->
        {
            String s = (String) _E_ValueSpecification.unwrap(args.get(0));
            String result;
            if (s.indexOf('\\') < 0)
            {
                result = s;
            }
            else
            {
                StringBuilder sb = new StringBuilder(s.length());
                for (int i = 0; i < s.length(); i++)
                {
                    char c = s.charAt(i);
                    if (c == '\\' && i + 1 < s.length())
                    {
                        char next = s.charAt(i + 1);
                        switch (next)
                        {
                            case '\'': sb.append('\''); i++; break;
                            case '\\': sb.append('\\'); i++; break;
                            case 'n':  sb.append('\n'); i++; break;
                            case 't':  sb.append('\t'); i++; break;
                            case 'r':  sb.append('\r'); i++; break;
                            case 'u':
                                int hexStart = i + 2;
                                int hexEnd = hexStart;
                                while (hexEnd < s.length() && hexEnd - hexStart < 4
                                        && Character.digit(s.charAt(hexEnd), 16) >= 0)
                                {
                                    hexEnd++;
                                }
                                if (hexEnd > hexStart)
                                {
                                    sb.append((char) Integer.parseInt(s.substring(hexStart, hexEnd), 16));
                                    i = hexEnd - 1;
                                }
                                else
                                {
                                    sb.append(c);
                                }
                                break;
                            default:   sb.append(c); break;
                        }
                    }
                    else
                    {
                        sb.append(c);
                    }
                }
                result = sb.toString();
            }
            return _E_ValueSpecification.wrap(result, genericType, multiplicity, resolver);
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
            List<ValueSpecification> result = new ArrayList<>();
            for (String part : parts)
            {
                result.add(_E_ValueSpecification.wrap(part, genericType, null, resolver));
            }
            // String[*] — compute common type and size-based multiplicity
            int size = parts.length;
            meta.pure.metamodel.type.generics.GenericType gt = result.isEmpty() ? null : result.get(0)._genericType();
            return new meta.pure.metamodel.valuespecification.CollectionImpl(resolver)
                    ._values(org.eclipse.collections.api.factory.Lists.mutable.withAll(result))
                    ._genericType(gt)
                    ._multiplicity(org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._Multiplicity.concreteMultiplicity(size, size, resolver));
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

        // lastIndexOf(String[1], String[1]) : Integer[1]
        natives.put("lastIndexOf_String_1__String_1__Integer_1_", (args, eval, genericType, multiplicity) ->
        {
            String s = (String) _E_ValueSpecification.unwrap(args.get(0));
            String search = (String) _E_ValueSpecification.unwrap(args.get(1));
            return _E_ValueSpecification.wrap((long) s.lastIndexOf(search), genericType, multiplicity, resolver);
        });

        // chunk(String[1], Integer[1]) : String[*]
        natives.put("chunk_String_1__Integer_1__String_MANY_", (args, eval, genericType, multiplicity) ->
        {
            String s = (String) _E_ValueSpecification.unwrap(args.get(0));
            int size = ((Long) _E_ValueSpecification.unwrap(args.get(1))).intValue();
            int len = s.length();
            List<ValueSpecification> result = new ArrayList<>();
            for (int i = 0; i < len; i += size)
            {
                result.add(_E_ValueSpecification.wrap(s.substring(i, Math.min(i + size, len)), genericType, null, resolver));
            }
            int count = result.size();
            meta.pure.metamodel.type.generics.GenericType gt = result.isEmpty() ? null : result.get(0)._genericType();
            return new meta.pure.metamodel.valuespecification.CollectionImpl(resolver)
                    ._values(org.eclipse.collections.api.factory.Lists.mutable.withAll(result))
                    ._genericType(gt)
                    ._multiplicity(org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._Multiplicity.concreteMultiplicity(count, count, resolver));
        });

        // matches(String[1], String[1]) : Boolean[1] — full-string regex match
        natives.put("matches_String_1__String_1__Boolean_1_", (args, eval, genericType, multiplicity) ->
        {
            String s = (String) _E_ValueSpecification.unwrap(args.get(0));
            String regex = (String) _E_ValueSpecification.unwrap(args.get(1));
            return _E_ValueSpecification.wrap(Pattern.matches(regex, s), genericType, multiplicity, resolver);
        });

        // regexpLike(String[1], String[1], RegexpParameter[1..*]) : Boolean[1] — contains-match
        natives.put("regexpLike_String_1__String_1__RegexpParameter_$1_MANY$__Boolean_1_", (args, eval, genericType, multiplicity) ->
        {
            String s = (String) _E_ValueSpecification.unwrap(args.get(0));
            String regex = (String) _E_ValueSpecification.unwrap(args.get(1));
            int flags = regexpFlags(args.get(2), resolver);
            return _E_ValueSpecification.wrap(Pattern.compile(regex, flags).matcher(s).find(),
                    genericType, multiplicity, resolver);
        });

        // regexpCount(String[1], String[1], RegexpParameter[1..*]) : Integer[1]
        natives.put("regexpCount_String_1__String_1__RegexpParameter_$1_MANY$__Integer_1_", (args, eval, genericType, multiplicity) ->
        {
            String s = (String) _E_ValueSpecification.unwrap(args.get(0));
            String regex = (String) _E_ValueSpecification.unwrap(args.get(1));
            int flags = regexpFlags(args.get(2), resolver);
            java.util.regex.Matcher m = Pattern.compile(regex, flags).matcher(s);
            long c = 0;
            while (m.find()) { c++; }
            return _E_ValueSpecification.wrap(c, genericType, multiplicity, resolver);
        });

        // regexpIndexOf(String[1], String[1], Integer[1], RegexpParameter[1..*]) : Integer[1]
        natives.put("regexpIndexOf_String_1__String_1__Integer_1__RegexpParameter_$1_MANY$__Integer_1_", (args, eval, genericType, multiplicity) ->
        {
            String s = (String) _E_ValueSpecification.unwrap(args.get(0));
            String regex = (String) _E_ValueSpecification.unwrap(args.get(1));
            int group = ((Number) _E_ValueSpecification.unwrap(args.get(2))).intValue();
            int flags = regexpFlags(args.get(3), resolver);
            java.util.regex.Matcher m = Pattern.compile(regex, flags).matcher(s);
            long pos = m.find() ? m.start(group) : -1L;
            return _E_ValueSpecification.wrap(pos, genericType, multiplicity, resolver);
        });

        // regexpReplace(String[1], String[1], String[1], Boolean[1], RegexpParameter[1..*]) : String[1]
        natives.put("regexpReplace_String_1__String_1__String_1__Boolean_1__RegexpParameter_$1_MANY$__String_1_", (args, eval, genericType, multiplicity) ->
        {
            String s = (String) _E_ValueSpecification.unwrap(args.get(0));
            String regex = (String) _E_ValueSpecification.unwrap(args.get(1));
            String replacement = (String) _E_ValueSpecification.unwrap(args.get(2));
            boolean replaceAll = (Boolean) _E_ValueSpecification.unwrap(args.get(3));
            int flags = regexpFlags(args.get(4), resolver);
            java.util.regex.Matcher m = Pattern.compile(regex, flags).matcher(s);
            return _E_ValueSpecification.wrap(replaceAll ? m.replaceAll(replacement) : m.replaceFirst(replacement),
                    genericType, multiplicity, resolver);
        });

        // regexpExtract(String[1], String[1], Boolean[1], Integer[1], RegexpParameter[1..*]) : String[*]
        natives.put("regexpExtract_String_1__String_1__Boolean_1__Integer_1__RegexpParameter_$1_MANY$__String_MANY_", (args, eval, genericType, multiplicity) ->
        {
            String s = (String) _E_ValueSpecification.unwrap(args.get(0));
            String regex = (String) _E_ValueSpecification.unwrap(args.get(1));
            boolean extractAll = (Boolean) _E_ValueSpecification.unwrap(args.get(2));
            int group = ((Number) _E_ValueSpecification.unwrap(args.get(3))).intValue();
            int flags = regexpFlags(args.get(4), resolver);
            java.util.regex.Matcher m = Pattern.compile(regex, flags).matcher(s);
            List<String> found = new ArrayList<>();
            if (extractAll)
            {
                while (m.find())
                {
                    String g = m.group(group);
                    if (g != null) { found.add(g); }
                }
            }
            else if (m.find())
            {
                String g = m.group(group);
                if (g != null) { found.add(g); }
            }
            List<ValueSpecification> result = new ArrayList<>(found.size());
            meta.pure.metamodel.type.generics.GenericType stringGT = null;
            for (String g : found)
            {
                ValueSpecification vs = _E_ValueSpecification.wrap(g, genericType, null, resolver);
                result.add(vs);
                if (stringGT == null) { stringGT = vs._genericType(); }
            }
            int size = result.size();
            return new meta.pure.metamodel.valuespecification.CollectionImpl(resolver)
                    ._values(org.eclipse.collections.api.factory.Lists.mutable.withAll(result))
                    ._genericType(stringGT)
                    ._multiplicity(org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._Multiplicity.concreteMultiplicity(size, size, resolver));
        });

        // parseInteger(String[1]) : Integer[1]
        natives.put("parseInteger_String_1__Integer_1_", (args, eval, genericType, multiplicity) ->
                _E_ValueSpecification.wrap(Long.parseLong(((String) _E_ValueSpecification.unwrap(args.get(0))).trim()), genericType, multiplicity, resolver));

        // parseFloat(String[1]) : Float[1]
        natives.put("parseFloat_String_1__Float_1_", (args, eval, genericType, multiplicity) ->
                _E_ValueSpecification.wrap(Double.parseDouble(((String) _E_ValueSpecification.unwrap(args.get(0))).trim()), genericType, multiplicity, resolver));

        // parseDecimal(String[1]) : Decimal[1] — BigDecimal preserves the exact
        // decimal representation of the source so downstream arithmetic doesn't
        // round-trip through Double.
        natives.put("parseDecimal_String_1__Decimal_1_", (args, eval, genericType, multiplicity) ->
        {
            java.math.BigDecimal bd = new java.math.BigDecimal(stripDecimalSuffix(((String) _E_ValueSpecification.unwrap(args.get(0))).trim()));
            return _E_ValueSpecification.wrap(bd, genericType, multiplicity, resolver);
        });

        // parseDecimal(String[1], Integer[1], Integer[1]) : Decimal[1]
        natives.put("parseDecimal_String_1__Integer_1__Integer_1__Decimal_1_", (args, eval, genericType, multiplicity) ->
        {
            String str = stripDecimalSuffix(((String) _E_ValueSpecification.unwrap(args.get(0))).trim());
            int scale = ((Number) _E_ValueSpecification.unwrap(args.get(2))).intValue();
            java.math.BigDecimal bd = new java.math.BigDecimal(str)
                    .setScale(scale, java.math.RoundingMode.HALF_UP);
            return _E_ValueSpecification.wrap(bd, genericType, multiplicity, resolver);
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
     * Translate a Pure-side {@code RegexpParameter[1..*]} arg (a value spec
     * wrapping a list of {@code RegexpParameter} enum values) into JDK
     * {@link Pattern} flag bits. Mirrors the Truffle-side {@code RegexpHelper}.
     *
     * <ul>
     *   <li>{@code CASE_SENSITIVE}        — no flag (clears CASE_INSENSITIVE).</li>
     *   <li>{@code CASE_INSENSITIVE}      — {@link Pattern#CASE_INSENSITIVE}.</li>
     *   <li>{@code MULTILINE}             — {@link Pattern#MULTILINE}.</li>
     *   <li>{@code NON_NEWLINE_SENSITIVE} — {@link Pattern#DOTALL}.</li>
     * </ul>
     */
    private static int regexpFlags(ValueSpecification arg, MetadataAccess resolver)
    {
        if (arg == null) return 0;
        List<? extends ValueSpecification> items = _E_ValueSpecification.toCollection(arg, resolver)._values();
        int flags = 0;
        for (ValueSpecification item : items)
        {
            Object v = _E_ValueSpecification.unwrap(item);
            String name = (v instanceof meta.pure.metamodel.type.Enum e) ? e._name() : (v == null ? null : v.toString());
            if (name == null) continue;
            switch (name)
            {
                case "CASE_INSENSITIVE"      -> flags |= Pattern.CASE_INSENSITIVE;
                case "MULTILINE"             -> flags |= Pattern.MULTILINE;
                case "NON_NEWLINE_SENSITIVE" -> flags |= Pattern.DOTALL;
                default                      -> { /* CASE_SENSITIVE — no flag */ }
            }
        }
        return flags;
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
                        ValueSpecification fnVS = _E_ValueSpecification.wrap(qp, qp._genericType(), qp._multiplicity(), resolver);
                        ValueSpecification result = eval.executeFunction(fnVS, List.of(wrappedSelf));
                        return (String) _E_ValueSpecification.unwrap(result);
                    }
                }
            }
        }

        // Use Pure type system when VS is available
        if (vs != null && vs._genericType() != null)
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
        if (vs != null && vs._genericType() != null)
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

    /**
     * Drop a trailing decimal-literal suffix {@code 'd'} / {@code 'D'} from
     * a numeric string so {@link java.math.BigDecimal#BigDecimal(String)}
     * accepts it. Pure source carries {@code 1.0d} for Decimal literals and
     * some callers pass the literal text straight to parseDecimal;
     * {@code Double.parseDouble} used to accept the suffix, BigDecimal does not.
     */
    private static String stripDecimalSuffix(String s)
    {
        int len = s.length();
        if (len > 0)
        {
            char last = s.charAt(len - 1);
            if (last == 'd' || last == 'D') return s.substring(0, len - 1);
        }
        return s;
    }
}
