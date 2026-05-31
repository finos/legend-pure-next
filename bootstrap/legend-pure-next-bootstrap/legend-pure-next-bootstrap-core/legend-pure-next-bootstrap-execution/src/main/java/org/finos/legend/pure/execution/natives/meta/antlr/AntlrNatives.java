// Copyright 2026 Goldman Sachs
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

package org.finos.legend.pure.execution.natives.meta.antlr;

import meta.pure.metamodel.valuespecification.ValueSpecification;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.tree.ParseTree;
import org.antlr.v4.runtime.tree.TerminalNode;
import org.finos.legend.pure.execution.NativeRepository;
import org.finos.legend.pure.next.parser.GrammarExtension;
import org.finos.legend.pure.execution.NativeRepository.LazyNativeImpl;
import org.finos.legend.pure.execution.NativeRepository.NativeImpl;
import org.finos.legend.pure.execution._E_ValueSpecification;
import org.finos.legend.pure.m3.module.MetadataAccess;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Bridge between the Pure-side ANTLR primitives (declared in
 * {@code meta::pure::functions::meta::antlr}) and the host-side ANTLR
 * runtime. Registered by {@link NativeRepository}'s {@code registerDefaults}
 * — these are core natives, not opt-in extensions.
 *
 * <p>{@code parseAntlr} dispatches via the per-runtime
 * {@link GrammarExtension} registry (see
 * {@code PureExecution.Builder.withGrammarExtensions}); every other native
 * here is grammar-agnostic — it only knows how to navigate a
 * {@link ParserRuleContext}.</p>
 */
public final class AntlrNatives
{
    private static final ConcurrentHashMap<String, Method> METHOD_CACHE = new ConcurrentHashMap<>();
    /**
     * Cache for {@code ruleNames} field lookup per parser class. Each
     * ANTLR-generated parser exposes a {@code public static final String[] ruleNames};
     * we read it reflectively so {@code grammarRuleName} doesn't need a
     * compile-time reference to any specific parser class.
     */
    private static final ConcurrentHashMap<Class<?>, String[]> RULE_NAMES_CACHE = new ConcurrentHashMap<>();

    private AntlrNatives() {}

    public static void register(Map<String, NativeImpl> natives,
                                Map<String, LazyNativeImpl> lazyNatives,
                                MetadataAccess resolver,
                                NativeRepository repository)
    {
        put(natives, resolver,
            "getText_AntlrContext_1__String_1_",
            args -> ((ParseTree) args.get(0)).getText());

        put(natives, resolver,
            "grammarRuleName_AntlrContext_1__String_1_",
            args -> grammarRuleName((ParserRuleContext) args.get(0)));

        put(natives, resolver,
            "getTopLevelChildren_AntlrContext_1__AntlrContext_MANY_",
            args -> {
                List<ParseTree> children = ((ParserRuleContext) args.get(0)).children;
                List<Object> out = new ArrayList<>(children == null ? 0 : children.size());
                if (children != null) {
                    for (ParseTree c : children) {
                        if (c instanceof ParserRuleContext prc) out.add(prc);
                    }
                }
                return out;
            });

        put(natives, resolver,
            "getChild_AntlrContext_1__String_1__AntlrContext_$0_1$_",
            args -> {
                Object r = invokeNamed(args.get(0), (String) args.get(1));
                return r instanceof ParserRuleContext ? r : null;
            });

        put(natives, resolver,
            "getChildren_AntlrContext_1__String_1__AntlrContext_MANY_",
            args -> {
                Object r = invokeNamed(args.get(0), (String) args.get(1));
                // ANTLR generates `ctx.name()` returning either `XContext` (for
                // single-occurrence sub-rules in the grammar) or `List<XContext>`
                // (for + / *). Both should look like a list from Pure's side so
                // `->map(...)` always works uniformly.
                if (r == null) return List.of();
                if (r instanceof List<?> list) return list;
                return List.of(r);
            });

        put(natives, resolver,
            "getTokenText_AntlrContext_1__String_1__String_$0_1$_",
            args -> {
                Object r = invokeNamed(args.get(0), (String) args.get(1));
                return r instanceof TerminalNode t ? t.getText() : null;
            });

        put(natives, resolver,
            "getTokenTexts_AntlrContext_1__String_1__String_MANY_",
            args -> {
                Object r = invokeNamed(args.get(0), (String) args.get(1));
                if (r instanceof List<?> list) {
                    List<Object> out = new ArrayList<>(list.size());
                    for (Object o : list) {
                        if (o instanceof TerminalNode t) out.add(t.getText());
                    }
                    return out;
                }
                if (r instanceof TerminalNode t) return List.of(t.getText());
                return List.of();
            });

        put(natives, resolver,
            "hasChild_AntlrContext_1__String_1__Boolean_1_",
            args -> invokeNamed(args.get(0), (String) args.get(1)) != null);

        put(natives, resolver,
            "hasToken_AntlrContext_1__String_1__Boolean_1_",
            args -> {
                Object r = invokeNamed(args.get(0), (String) args.get(1));
                return r instanceof TerminalNode;
            });

        put(natives, resolver,
            "getStartLine_AntlrContext_1__Integer_1_",
            args -> (long) ((ParserRuleContext) args.get(0)).getStart().getLine());

        put(natives, resolver,
            "getStartColumn_AntlrContext_1__Integer_1_",
            args -> (long) ((ParserRuleContext) args.get(0)).getStart().getCharPositionInLine() + 1);

        put(natives, resolver,
            "getStopLine_AntlrContext_1__Integer_1_",
            args -> (long) ((ParserRuleContext) args.get(0)).getStop().getLine());

        put(natives, resolver,
            "getStopColumn_AntlrContext_1__Integer_1_",
            args -> {
                ParserRuleContext c = (ParserRuleContext) args.get(0);
                return (long) c.getStop().getCharPositionInLine() + c.getStop().getText().length();
            });

        put(natives, resolver,
            "getChildTextAt_AntlrContext_1__Integer_1__String_$0_1$_",
            args -> {
                ParserRuleContext ctx = (ParserRuleContext) args.get(0);
                int idx = ((Long) args.get(1)).intValue();
                return idx < ctx.children.size() ? ctx.children.get(idx).getText() : null;
            });

        put(natives, resolver,
            "stripTripleQuotesDedented_String_1__String_1_",
            args -> org.finos.legend.pure.next.parser.shared.TripleStringStripper.strip(
                (String) args.get(0)));

        put(natives, resolver,
            "parseAntlr_String_1__String_1__String_1__Integer_1__AntlrContext_1_",
            args -> {
                String source = (String) args.get(0);
                String grammar = (String) args.get(1);
                String sourceId = (String) args.get(2);
                int lineOffset = ((Long) args.get(3)).intValue();
                GrammarExtension ext = repository.lookupGrammar(grammar);
                if (ext == null)
                {
                    throw new RuntimeException("Unknown grammar: " + grammar
                            + " (no GrammarExtension registered; pass it via PureExecution.Builder.withGrammarExtensions)");
                }
                return ext.parse(source, sourceId, lineOffset);
            });

        put(natives, resolver,
            "computeFirstNonNewlineLine_AntlrContext_$0_1$__Boolean_1__Integer_1_",
            args -> {
                Object raw = args.get(0);
                Object ctx = raw instanceof List<?> l ? (l.isEmpty() ? null : l.get(0)) : raw;
                boolean syntheticHeader = (Boolean) args.get(1);
                if (ctx == null) return 0L;
                ParserRuleContext prc = (ParserRuleContext) ctx;
                for (int i = 0; i < prc.getChildCount(); i++)
                {
                    ParseTree child = prc.getChild(i);
                    if (child instanceof TerminalNode tn
                            && tn.getSymbol().getType() == org.antlr.v4.runtime.Token.EOF) continue;
                    String text = child.getText();
                    if (text != null && text.trim().isEmpty()) continue;
                    int line = (child instanceof TerminalNode tn2)
                            ? tn2.getSymbol().getLine()
                            : ((ParserRuleContext) child).getStart().getLine();
                    int offset = line - 1;
                    if (syntheticHeader) offset -= 1;
                    return (long) offset;
                }
                return 0L;
            });
    }

    /**
     * Resolve {@code ctx}'s rule name by walking up to its enclosing parser
     * class (every ANTLR-generated {@code XContext} is an inner class of
     * {@code XParser}) and reading {@code XParser.ruleNames} reflectively.
     * Cached per parser class.
     */
    private static String grammarRuleName(ParserRuleContext ctx)
    {
        Class<?> ctxCls = ctx.getClass();
        Class<?> parserCls = ctxCls.getEnclosingClass();
        if (parserCls == null)
        {
            throw new IllegalStateException(
                    "grammarRuleName: context class " + ctxCls.getName()
                            + " has no enclosing parser class; can't resolve ruleNames");
        }
        String[] ruleNames = RULE_NAMES_CACHE.computeIfAbsent(parserCls, cls ->
        {
            try
            {
                Field f = cls.getField("ruleNames");
                return (String[]) f.get(null);
            }
            catch (NoSuchFieldException | IllegalAccessException e)
            {
                throw new IllegalStateException(
                        "grammarRuleName: " + cls.getName()
                                + " has no public static `ruleNames` field", e);
            }
        });
        return ruleNames[ctx.getRuleIndex()];
    }

    private static void put(Map<String, NativeImpl> natives,
                            MetadataAccess resolver,
                            String signature,
                            Function<List<Object>, Object> body)
    {
        natives.put(signature, (args, eval, genericType, multiplicity) -> {
            List<Object> unwrapped = new ArrayList<>(args.size());
            for (ValueSpecification vs : args) unwrapped.add(_E_ValueSpecification.unwrap(vs));
            Object result = body.apply(unwrapped);
            return _E_ValueSpecification.wrap(result, genericType, multiplicity, resolver);
        });
    }

    private static Object invokeNamed(Object ctx, String methodName)
    {
        try
        {
            Class<?> cls = ctx.getClass();
            Method m = METHOD_CACHE.computeIfAbsent(cls.getName() + "#" + methodName, k ->
            {
                try { return cls.getMethod(methodName); }
                catch (NoSuchMethodException e) { throw new RuntimeException(e); }
            });
            return m.invoke(ctx);
        }
        catch (Exception e)
        {
            throw new RuntimeException("Failed to invoke " + methodName + " on " + ctx.getClass().getSimpleName(), e);
        }
    }
}
