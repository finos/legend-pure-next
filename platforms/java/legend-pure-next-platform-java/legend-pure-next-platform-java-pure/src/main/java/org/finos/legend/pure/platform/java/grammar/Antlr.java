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

package org.finos.legend.pure.platform.java.grammar;

import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.tree.ParseTree;
import org.antlr.v4.runtime.tree.TerminalNode;




import org.eclipse.collections.api.factory.Maps;
import org.finos.legend.pure.platform.java.runtime.PureRuntime;
import org.eclipse.collections.api.map.ConcurrentMutableMap;
import org.eclipse.collections.impl.map.mutable.ConcurrentHashMap;
import org.eclipse.collections.api.map.MutableMap;

import java.lang.reflect.Method;
import java.util.ArrayList;
// The tree-walking natives below return java.util.List, and must: their values cross into translated
// Pure code, which is compiled against the JDK alone. Eclipse Collections is used for everything that
// stays on this side of that boundary.
import java.util.List;

/**
 * The `meta::pure::functions::meta::antlr` natives: parse a source with a named grammar
 * and walk the parse tree.
 *
 * <p>ANTLR's runtime is a declared dependency of this platform and the parsers are
 * generated here from the shared grammars, so this is the implementation rather than a
 * facade. It used to be a reflective lookup into a separately-built extension compiled
 * against the bootstrap fat jar, which was the platform's last dependency on another
 * platform. The rule that matters is that generated Java must not bind to bootstrap's
 * or Truffle's classes — a parser runtime is not a platform — and what enforces it for
 * translated code is {@code JavaSource.compile}, which runs javac with an empty class
 * path and the platform loader.</p>
 */
public final class Antlr
{
    private static volatile MutableMap<String, GrammarExtension> GRAMMARS = grammars();
    private static final ConcurrentMutableMap<String, Method> ACCESSORS = ConcurrentHashMap.newMap();

    private Antlr()
    {
    }

    public static Object parseAntlr(String source, String grammarName, String sourceId, long lineOffset)
    {
        GrammarExtension grammar = GRAMMARS.get(grammarName);
        if (grammar == null)
        {
            // Naming what IS registered, because the usual cause is a language the composition root did
            // not install — only TopParser is here until something does — rather than a typo.
            // Sorted, because the map is unordered and a message that changes between runs is worse
            // than one that does not.
            throw new RuntimeException("Unknown grammar: " + grammarName
                    + " (registered: " + GRAMMARS.keysView().toSortedList().makeString(", ") + ")");
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
        return org.finos.legend.pure.platform.java.grammar.shared.TripleStringStripper.strip(text);
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

    /** Rebuild the registry after {@code PureRuntime.installLanguages}. */
    public static void reloadGrammars()
    {
        GRAMMARS = grammars();
    }

    /**
     * The Top grammar, then every installed language that declares one.
     *
     * <p>Top is the only one named here, and it is not an exception: it frames a document into
     * {@code ###Section} blocks before any language is reached — {@code ###Diagram} included — so it
     * belongs to the engine rather than to a language, and no manifest declares it. M3 used to sit
     * beside it as a literal; it now arrives from {@code language_pure.json} like any other.</p>
     */
    private static MutableMap<String, GrammarExtension> grammars()
    {
        MutableMap<String, GrammarExtension> known = Maps.mutable.empty();
        // Constructed here rather than held in a static field: static initialisers run in TEXTUAL
        // order, so a field declared below GRAMMARS would still be null when this runs, and the whole
        // class would fail to initialise.
        GrammarExtension top = new TopGrammarExtension();
        known.put(top.grammarName(), top);
        for (Language language : PureRuntime.languages())
        {
            if (language.grammar() == null)
            {
                continue;
            }
            GrammarExtension clash = known.put(language.grammar().grammarName(), language.grammar());
            if (clash != null)
            {
                throw new IllegalStateException("two languages declare the grammar '"
                        + language.grammar().grammarName() + "'");
            }
        }
        return known;
    }
}
