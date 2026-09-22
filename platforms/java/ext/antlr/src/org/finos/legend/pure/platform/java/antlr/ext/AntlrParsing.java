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

package org.finos.legend.pure.platform.java.antlr.ext;

import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.tree.ParseTree;
import org.antlr.v4.runtime.tree.TerminalNode;
import org.finos.legend.pure.next.parser.GrammarExtension;
import org.finos.legend.pure.next.parser.M3GrammarExtension;
import org.finos.legend.pure.next.parser.TopGrammarExtension;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The ANTLR natives of the Java platform, over the generated parsers and the
 * ANTLR runtime. The platform's core names only the facade
 * ({@code platform.java.antlr.Antlr}), which finds this class when a host puts
 * the extension on the class path — so the core stays JDK-only.
 *
 * <p>An {@code AntlrContext} is an ANTLR {@link ParserRuleContext}: the natives
 * pass it around as an opaque value and read names, text and positions from it.</p>
 */
public final class AntlrParsing
{
    private static final Map<String, GrammarExtension> GRAMMARS = grammars();
    private static final Map<String, Method> ACCESSORS = new ConcurrentHashMap<>();

    private AntlrParsing()
    {
    }

    public static Object parseAntlr(String source, String grammarName, String sourceId, long lineOffset)
    {
        GrammarExtension grammar = GRAMMARS.get(grammarName);
        if (grammar == null)
        {
            throw new RuntimeException("Unknown grammar: " + grammarName);
        }
        return grammar.parse(source, sourceId, (int) lineOffset);
    }

    public static String getText(Object context)
    {
        return ((ParseTree) context).getText();
    }

    public static String grammarRuleName(Object context)
    {
        String name = ((ParserRuleContext) context).getClass().getSimpleName();
        String withoutSuffix = name.endsWith("Context") ? name.substring(0, name.length() - "Context".length()) : name;
        return withoutSuffix.isEmpty() ? withoutSuffix : Character.toLowerCase(withoutSuffix.charAt(0)) + withoutSuffix.substring(1);
    }

    public static List<Object> getTopLevelChildren(Object context)
    {
        List<ParseTree> children = ((ParserRuleContext) context).children;
        List<Object> rules = new ArrayList<>(children == null ? 0 : children.size());
        for (ParseTree child : children == null ? List.<ParseTree>of() : children)
        {
            if (child instanceof ParserRuleContext)
            {
                rules.add(child);
            }
        }
        return rules;
    }

    public static Object getChild(Object context, String name)
    {
        Object child = named(context, name);
        return child instanceof ParserRuleContext ? child : null;
    }

    public static List<Object> getChildren(Object context, String name)
    {
        // ANTLR generates `ctx.name()` returning the context for a single
        // occurrence and a List for `+`/`*`: Pure sees a collection either way.
        Object child = named(context, name);
        if (child == null)
        {
            return List.of();
        }
        return child instanceof List ? new ArrayList<>((List<?>) child) : List.of(child);
    }

    public static boolean hasChild(Object context, String name)
    {
        return named(context, name) != null;
    }

    public static String getTokenText(Object context, String name)
    {
        Object token = named(context, name);
        return token instanceof TerminalNode ? ((TerminalNode) token).getText() : null;
    }

    public static List<Object> getTokenTexts(Object context, String name)
    {
        Object token = named(context, name);
        List<Object> texts = new ArrayList<>();
        for (Object each : token instanceof List ? (List<?>) token : token == null ? List.of() : List.of(token))
        {
            if (each instanceof TerminalNode)
            {
                texts.add(((TerminalNode) each).getText());
            }
        }
        return texts;
    }

    public static boolean hasToken(Object context, String name)
    {
        return named(context, name) instanceof TerminalNode;
    }

    public static String getChildTextAt(Object context, long index)
    {
        List<ParseTree> children = ((ParserRuleContext) context).children;
        return children == null || index >= children.size() ? null : children.get((int) index).getText();
    }

    public static long getStartLine(Object context)
    {
        return ((ParserRuleContext) context).getStart().getLine();
    }

    public static long getStartColumn(Object context)
    {
        return ((ParserRuleContext) context).getStart().getCharPositionInLine() + 1L;
    }

    public static long getStopLine(Object context)
    {
        return ((ParserRuleContext) context).getStop().getLine();
    }

    public static long getStopColumn(Object context)
    {
        Token stop = ((ParserRuleContext) context).getStop();
        return stop.getCharPositionInLine() + stop.getText().length();
    }

    public static String stripTripleQuotesDedented(String text)
    {
        return org.finos.legend.pure.next.parser.shared.TripleStringStripper.strip(text);
    }

    /** The line of the first child that is neither EOF nor blank, as an offset. */
    public static long computeFirstNonNewlineLine(Object context, boolean syntheticHeader)
    {
        Object value = context instanceof List ? (((List<?>) context).isEmpty() ? null : ((List<?>) context).get(0)) : context;
        if (value == null)
        {
            return 0L;
        }
        ParserRuleContext rule = (ParserRuleContext) value;
        for (int i = 0; i < rule.getChildCount(); i++)
        {
            ParseTree child = rule.getChild(i);
            if (child instanceof TerminalNode && ((TerminalNode) child).getSymbol().getType() == Token.EOF)
            {
                continue;
            }
            String text = child.getText();
            if (text != null && text.trim().isEmpty())
            {
                continue;
            }
            int line = child instanceof TerminalNode
                    ? ((TerminalNode) child).getSymbol().getLine()
                    : ((ParserRuleContext) child).getStart().getLine();
            return Math.max(0, line - 1 - (syntheticHeader ? 1 : 0));
        }
        return 0L;
    }

    /** `ctx.<name>()`, the accessor ANTLR generates for a sub-rule or token. */
    private static Object named(Object context, String name)
    {
        Method accessor = ACCESSORS.computeIfAbsent(context.getClass().getName() + "#" + name, key ->
        {
            try
            {
                return context.getClass().getMethod(name);
            }
            catch (NoSuchMethodException e)
            {
                return null;
            }
        });
        if (accessor == null)
        {
            return null;
        }
        try
        {
            return accessor.invoke(context);
        }
        catch (ReflectiveOperationException e)
        {
            throw new RuntimeException("Cannot read '" + name + "' of " + context.getClass().getSimpleName(), e);
        }
    }

    private static Map<String, GrammarExtension> grammars()
    {
        Map<String, GrammarExtension> known = new LinkedHashMap<>();
        for (GrammarExtension grammar : List.of(new M3GrammarExtension(), new TopGrammarExtension()))
        {
            known.put(grammar.grammarName(), grammar);
        }
        return known;
    }
}
