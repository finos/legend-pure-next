// Copyright 2026 Goldman Sachs
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

package org.finos.legend.pure.platform.java.compiler;

import org.finos.legend.pure.platform.java.Execute;
import org.finos.legend.pure.platform.java.pdb.Metadata;

import java.util.ArrayList;
import java.util.List;

/**
 * Dynamic compilation on the Java platform: `compileSource`, and the two
 * metadata lookups the compiler makes while it runs.
 *
 * <p>The compiler itself is Pure, translated like everything else — this only
 * calls it and shapes the result. Nothing is registered: a compiled element is
 * handed back as a value, and the platform's own graph is left alone (which
 * `testCompileSourceDoesNotMutatePlatformGraph` checks).</p>
 */
public final class Compilation
{
    private static final String COMPILE =
            "meta::pure::compiler::compile_PureFile_MANY__CompilerContext_1__Boolean_1__Boolean_1__CompilationResult_1_";
    private static final String NEW_CONTEXT = "meta::pure::compiler::newCompilerContext_Boolean_1__CompilerContext_1_";
    private static final String RESULT = "meta::pure::functions::meta::CompileSourceResult";

    private Compilation()
    {
    }

    public static Object compileSource(Object file, Object dependencies)
    {
        for (Object dependency : asList(dependencies))
        {
            // A caller naming a module that was never loaded is a bug, and says
            // so — unlike a compile error, which is part of the result.
            if (!Metadata.runtime().moduleNames().contains(dependency))
            {
                throw new RuntimeException("compileSource: dependency module '" + dependency + "' is not loaded");
            }
        }
        List<Object> elements;
        List<Object> errors;
        try
        {
            // silent: a compile inside a program should not print a progress bar.
            Object result = Execute.call(COMPILE, List.of(file), Execute.call(NEW_CONTEXT, false), false, true);
            elements = values(result, "elements");
            errors = values(result, "errors");
        }
        catch (RuntimeException e)
        {
            elements = List.of();
            errors = List.of(String.valueOf(e.getMessage()));
        }
        catch (Exception e)
        {
            elements = List.of();
            errors = List.of(String.valueOf(e.getMessage()));
        }
        return result(errors.isEmpty() ? elements : List.of(), errors);
    }

    /** `findAllTypes()`: every type the loaded modules hold. */
    public static List<Object> findAllTypes()
    {
        return Metadata.runtime().allTypes();
    }

    /** `findFunctionsByNameAndArity(name, arity)`: from the archives' function index. */
    public static List<Object> findFunctionsByNameAndArity(Object name, Object arity)
    {
        return Metadata.runtime().functionsByNameAndArity((String) name, ((Number) arity).longValue());
    }

    /**
     * `unescapePureString(s)`: the parser hands escape sequences through as the
     * two characters they were written as; this is where they become one.
     */
    public static String unescapePureString(String text)
    {
        if (text.indexOf('\\') < 0)
        {
            return text;
        }
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++)
        {
            char c = text.charAt(i);
            if (c != '\\' || i + 1 >= text.length())
            {
                out.append(c);
                continue;
            }
            i = unescape(text, i, out);
        }
        return out.toString();
    }

    /** Appends what the escape at `i` stands for; returns the last index it consumed. */
    private static int unescape(String text, int i, StringBuilder out)
    {
        char next = text.charAt(i + 1);
        switch (next)
        {
            case '\'':
            case '\\':
                out.append(next);
                return i + 1;
            case 'n':
                out.append('\n');
                return i + 1;
            case 't':
                out.append('\t');
                return i + 1;
            case 'r':
                out.append('\r');
                return i + 1;
            case 'u':
                return unescapeUnicode(text, i, out);
            default:
                out.append('\\');
                return i;
        }
    }

    private static int unescapeUnicode(String text, int i, StringBuilder out)
    {
        int start = i + 2;
        int end = start;
        while (end < text.length() && end - start < 4 && Character.digit(text.charAt(end), 16) >= 0)
        {
            end++;
        }
        if (end == start)
        {
            out.append('\\');
            return i;
        }
        out.append((char) Integer.parseInt(text.substring(start, end), 16));
        return end - 1;
    }

    /**
     * `normalizeDateString(s)`: a date literal as the metadata stores it —
     * components zero-padded, and a zone offset resolved to UTC.
     */
    public static String normalizeDateString(String dateStr)
    {
        if (dateStr == null)
        {
            return null;
        }
        int t = dateStr.indexOf('T');
        if (t < 0)
        {
            return zeroPadDate(dateStr);
        }
        try
        {
            return normalizeDateTime(dateStr.substring(0, t), dateStr.substring(t + 1));
        }
        catch (RuntimeException e)
        {
            // Not a date this can read: the compiler reports on it, not this.
            return dateStr;
        }
    }

    private static String normalizeDateTime(String datePart, String timeAndZone)
    {
        int zoneAt = Math.max(timeAndZone.lastIndexOf('+'), timeAndZone.lastIndexOf('-'));
        String timePart = zoneAt > 0 ? timeAndZone.substring(0, zoneAt) : timeAndZone;
        String zone = zoneAt > 0 ? timeAndZone.substring(zoneAt) : null;
        String[] date = datePart.split("-");
        String[] time = timePart.split(":");
        boolean hasSeconds = time.length > 2;
        String fraction = "";
        int seconds = 0;
        if (hasSeconds)
        {
            int dot = time[2].indexOf('.');
            seconds = Integer.parseInt(dot >= 0 ? time[2].substring(0, dot) : time[2]);
            fraction = dot >= 0 ? time[2].substring(dot) : "";
        }
        java.time.LocalDateTime when = java.time.LocalDateTime.of(
                Integer.parseInt(date[0]),
                date.length > 1 ? Integer.parseInt(date[1]) : 1,
                date.length > 2 ? Integer.parseInt(date[2]) : 1,
                Integer.parseInt(time[0]),
                time.length > 1 ? Integer.parseInt(time[1]) : 0,
                seconds);
        java.time.LocalDateTime utc = zone == null ? when : when.minusMinutes(offsetMinutes(zone));
        String text = String.format("%04d-%02d-%02dT%02d:%02d",
                utc.getYear(), utc.getMonthValue(), utc.getDayOfMonth(), utc.getHour(), utc.getMinute());
        return hasSeconds ? text + String.format(":%02d", utc.getSecond()) + fraction : text;
    }

    private static long offsetMinutes(String zone)
    {
        long sign = zone.charAt(0) == '-' ? -1 : 1;
        long hours = Long.parseLong(zone.substring(1, 3));
        long minutes = zone.length() > 3 ? Long.parseLong(zone.substring(3, 5)) : 0;
        return sign * (hours * 60 + minutes);
    }

    /** `2015-3-4` is `2015-03-04`; a year or year-month date keeps its shape. */
    private static String zeroPadDate(String datePart)
    {
        String[] parts = datePart.split("-");
        if (parts.length >= 3)
        {
            return parts[0] + "-" + pad(parts[1]) + "-" + pad(parts[2]);
        }
        return parts.length == 2 ? parts[0] + "-" + pad(parts[1]) : datePart;
    }

    private static String pad(String value)
    {
        return value.length() < 2 ? "0" + value : value;
    }

    /** A CompileSourceResult carrying what the compiler said. */
    private static Object result(List<Object> elements, List<Object> errors)
    {
        return new org.finos.legend.pure.m3.meta.pure.functions.meta.CompileSourceResultImpl()
                ._classifierGenericType(Metadata.classifier(RESULT))
                ._elements(elements)
                ._errors(errors);
    }

    private static List<Object> values(Object result, String property)
    {
        List<Object> read = ((org.finos.legend.pure.platform.java.pdb.LazyObject) result).__values(property);
        return new ArrayList<>(read);
    }

    private static List<?> asList(Object value)
    {
        return value == null ? List.of() : value instanceof List ? (List<?>) value : List.of(value);
    }
}
