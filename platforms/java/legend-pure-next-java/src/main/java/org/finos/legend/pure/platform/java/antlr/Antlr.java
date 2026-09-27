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

package org.finos.legend.pure.platform.java.antlr;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The `meta::pure::functions::meta::antlr` natives: parse a source with a named
 * grammar and walk the parse tree.
 *
 * <p>Parsing needs a parser, which ANTLR generates from the grammars and which
 * runs on the ANTLR runtime — neither belongs in this platform's JDK-only core.
 * So this is a facade: it finds the implementation on the class path when a host
 * put the ANTLR extension there (`platforms/java/ext/antlr`), exactly as the
 * JavaScript platform looks up its ANTLR bundle. Translated code only ever names
 * this class, so it compiles with or without the extension, and a program that
 * parses without it fails with a message saying so.</p>
 */
public final class Antlr
{
    private static final String IMPLEMENTATION = "org.finos.legend.pure.platform.java.antlr.ext.AntlrParsing";
    private static final Map<String, Method> METHODS = new HashMap<>();
    private static volatile Class<?> implementation;

    private Antlr()
    {
    }

    public static Object parseAntlr(String source, String grammarName, String sourceId, long lineOffset)
    {
        return call("parseAntlr", source, grammarName, sourceId, lineOffset);
    }

    public static String getText(Object context)
    {
        return (String) call("getText", context);
    }

    public static String grammarRuleName(Object context)
    {
        return (String) call("grammarRuleName", context);
    }

    public static List<Object> getTopLevelChildren(Object context)
    {
        return asList(call("getTopLevelChildren", context));
    }

    public static Object getChild(Object context, String name)
    {
        return call("getChild", context, name);
    }

    public static List<Object> getChildren(Object context, String name)
    {
        return asList(call("getChildren", context, name));
    }

    public static boolean hasChild(Object context, String name)
    {
        return (Boolean) call("hasChild", context, name);
    }

    public static String getTokenText(Object context, String name)
    {
        return (String) call("getTokenText", context, name);
    }

    public static List<Object> getTokenTexts(Object context, String name)
    {
        return asList(call("getTokenTexts", context, name));
    }

    public static boolean hasToken(Object context, String name)
    {
        return (Boolean) call("hasToken", context, name);
    }

    public static String getChildTextAt(Object context, long index)
    {
        return (String) call("getChildTextAt", context, index);
    }

    public static long getStartLine(Object context)
    {
        return (Long) call("getStartLine", context);
    }

    public static long getStartColumn(Object context)
    {
        return (Long) call("getStartColumn", context);
    }

    public static long getStopLine(Object context)
    {
        return (Long) call("getStopLine", context);
    }

    public static long getStopColumn(Object context)
    {
        return (Long) call("getStopColumn", context);
    }

    public static String stripTripleQuotesDedented(String text)
    {
        return (String) call("stripTripleQuotesDedented", text);
    }

    public static long computeFirstNonNewlineLine(Object context, boolean syntheticHeader)
    {
        return (Long) call("computeFirstNonNewlineLine", context, syntheticHeader);
    }

    @SuppressWarnings("unchecked")
    private static List<Object> asList(Object value)
    {
        return value == null ? List.of() : (List<Object>) value;
    }

    private static Object call(String name, Object... args)
    {
        try
        {
            return method(name, args.length).invoke(null, args);
        }
        catch (IllegalAccessException e)
        {
            throw new RuntimeException("Cannot call the ANTLR extension's " + name, e);
        }
        catch (InvocationTargetException e)
        {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            throw cause instanceof RuntimeException ? (RuntimeException) cause : new RuntimeException(cause);
        }
    }

    private static synchronized Method method(String name, int arity)
    {
        Method known = METHODS.get(name);
        if (known != null)
        {
            return known;
        }
        for (Method candidate : implementation().getMethods())
        {
            if (candidate.getName().equals(name) && candidate.getParameterCount() == arity)
            {
                METHODS.put(name, candidate);
                return candidate;
            }
        }
        throw new UnsupportedOperationException("The ANTLR extension has no " + name + "/" + arity);
    }

    private static Class<?> implementation()
    {
        if (implementation == null)
        {
            try
            {
                implementation = Class.forName(IMPLEMENTATION);
            }
            catch (ClassNotFoundException e)
            {
                throw new UnsupportedOperationException(
                        "Parsing needs the ANTLR extension on the class path (" + IMPLEMENTATION
                                + "): build it with `just java::build-antlr-ext` and run with build/ext-classes");
            }
        }
        return implementation;
    }
}
