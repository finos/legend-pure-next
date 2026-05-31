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

package org.finos.legend.pure.truffle.ast.natives.meta.antlr;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.NodeInfo;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.tree.ParseTree;
import org.antlr.v4.runtime.tree.TerminalNode;
import org.finos.legend.pure.truffle.ast.PureNode;
import org.finos.legend.pure.truffle.types.ObjectSequence;
import org.finos.legend.pure.truffle.types.PureSequence;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Truffle PureNodes implementing the ANTLR-context bridge declared in
 * pure/specification/grammar/mapping/mappings-pure/interpreter/antlr.pure.
 *
 * <p>Each native takes the ANTLR ctx as an opaque {@code Any[1]} (the Pure
 * runtime carries the raw {@link ParserRuleContext} through unchanged) plus
 * optional name/index args, and returns the matching reflective accessor
 * result. Collection returns become {@link ObjectSequence}; missing children
 * become {@link PureSequence#EMPTY} for {@code [*]} returns or {@code null}
 * for {@code [0..1]} returns.</p>
 *
 * <p>Mirrors {@code AntlrContextNativesExtension} on the bootstrap side —
 * same accessor semantics, different runtime plumbing.</p>
 */
public final class AntlrNodes
{
    private static final ConcurrentHashMap<String, Method> METHOD_CACHE = new ConcurrentHashMap<>();

    private AntlrNodes() {}

    @CompilerDirectives.TruffleBoundary
    static Object invokeNamed(Object ctx, String methodName)
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

    // ============================================================
    // getText / grammarRuleName
    // ============================================================

    @NodeInfo(shortName = "getText")
    public static final class GetTextNode extends PureNode
    {
        @Child private PureNode ctxArg;
        public GetTextNode(PureNode ctxArg) { this.ctxArg = ctxArg; }
        @Override public Object executeGeneric(VirtualFrame frame)
        {
            return doExecute(ctxArg.executeGeneric(frame));
        }
        @CompilerDirectives.TruffleBoundary
        private static String doExecute(Object ctx) { return ((ParseTree) ctx).getText(); }
    }

    @NodeInfo(shortName = "grammarRuleName")
    public static final class GrammarRuleNameNode extends PureNode
    {
        @Child private PureNode ctxArg;
        public GrammarRuleNameNode(PureNode ctxArg) { this.ctxArg = ctxArg; }
        @Override public Object executeGeneric(VirtualFrame frame)
        {
            return doExecute(ctxArg.executeGeneric(frame));
        }
        @CompilerDirectives.TruffleBoundary
        private static String doExecute(Object ctx)
        {
            // Resolve via the ctx's enclosing parser class (M3Parser.M3Context,
            // TopParser.DocumentContext, …) so the lookup works for every
            // grammar — not just M3Parser. Mirrors AntlrNatives.grammarRuleName.
            ParserRuleContext prc = (ParserRuleContext) ctx;
            Class<?> parserCls = prc.getClass().getEnclosingClass();
            if (parserCls == null)
            {
                throw new IllegalStateException(
                        "grammarRuleName: context class " + prc.getClass().getName()
                                + " has no enclosing parser class; can't resolve ruleNames");
            }
            String[] ruleNames = RULE_NAMES_CACHE.computeIfAbsent(parserCls, cls ->
            {
                try
                {
                    return (String[]) cls.getField("ruleNames").get(null);
                }
                catch (NoSuchFieldException | IllegalAccessException e)
                {
                    throw new IllegalStateException("grammarRuleName: " + cls.getName()
                            + " has no public static `ruleNames` field", e);
                }
            });
            return ruleNames[prc.getRuleIndex()];
        }
    }

    private static final ConcurrentHashMap<Class<?>, String[]> RULE_NAMES_CACHE = new ConcurrentHashMap<>();

    // ============================================================
    // getTopLevelChildren — children that are ParserRuleContext
    // ============================================================

    @NodeInfo(shortName = "getTopLevelChildren")
    public static final class GetTopLevelChildrenNode extends PureNode
    {
        @Child private PureNode ctxArg;
        public GetTopLevelChildrenNode(PureNode ctxArg) { this.ctxArg = ctxArg; }
        @Override public Object executeGeneric(VirtualFrame frame)
        {
            return doExecute(ctxArg.executeGeneric(frame));
        }
        @CompilerDirectives.TruffleBoundary
        private static PureSequence doExecute(Object ctx)
        {
            List<ParseTree> children = ((ParserRuleContext) ctx).children;
            if (children == null || children.isEmpty()) return PureSequence.EMPTY;
            List<Object> out = new ArrayList<>(children.size());
            for (ParseTree c : children) if (c instanceof ParserRuleContext prc) out.add(prc);
            return out.isEmpty() ? PureSequence.EMPTY : new ObjectSequence(out.toArray());
        }
    }

    // ============================================================
    // getChild / getChildren / getTokenText / getTokenTexts
    // ============================================================

    @NodeInfo(shortName = "getChild")
    public static final class GetChildNode extends PureNode
    {
        @Child private PureNode ctxArg;
        @Child private PureNode nameArg;
        public GetChildNode(PureNode ctxArg, PureNode nameArg) { this.ctxArg = ctxArg; this.nameArg = nameArg; }
        @Override public Object executeGeneric(VirtualFrame frame)
        {
            return doExecute(ctxArg.executeGeneric(frame), (String) nameArg.executeGeneric(frame));
        }
        @CompilerDirectives.TruffleBoundary
        private static Object doExecute(Object ctx, String name)
        {
            Object r = invokeNamed(ctx, name);
            return r instanceof ParserRuleContext ? r : null;
        }
    }

    @NodeInfo(shortName = "getChildren")
    public static final class GetChildrenNode extends PureNode
    {
        @Child private PureNode ctxArg;
        @Child private PureNode nameArg;
        public GetChildrenNode(PureNode ctxArg, PureNode nameArg) { this.ctxArg = ctxArg; this.nameArg = nameArg; }
        @Override public Object executeGeneric(VirtualFrame frame)
        {
            return doExecute(ctxArg.executeGeneric(frame), (String) nameArg.executeGeneric(frame));
        }
        @CompilerDirectives.TruffleBoundary
        private static PureSequence doExecute(Object ctx, String name)
        {
            Object r = invokeNamed(ctx, name);
            if (r == null) return PureSequence.EMPTY;
            if (r instanceof List<?> list)
            {
                return list.isEmpty() ? PureSequence.EMPTY : new ObjectSequence(list.toArray());
            }
            return new ObjectSequence(new Object[]{r});
        }
    }

    @NodeInfo(shortName = "getTokenText")
    public static final class GetTokenTextNode extends PureNode
    {
        @Child private PureNode ctxArg;
        @Child private PureNode nameArg;
        public GetTokenTextNode(PureNode ctxArg, PureNode nameArg) { this.ctxArg = ctxArg; this.nameArg = nameArg; }
        @Override public Object executeGeneric(VirtualFrame frame)
        {
            return doExecute(ctxArg.executeGeneric(frame), (String) nameArg.executeGeneric(frame));
        }
        @CompilerDirectives.TruffleBoundary
        private static String doExecute(Object ctx, String name)
        {
            Object r = invokeNamed(ctx, name);
            return r instanceof TerminalNode t ? t.getText() : null;
        }
    }

    @NodeInfo(shortName = "getTokenTexts")
    public static final class GetTokenTextsNode extends PureNode
    {
        @Child private PureNode ctxArg;
        @Child private PureNode nameArg;
        public GetTokenTextsNode(PureNode ctxArg, PureNode nameArg) { this.ctxArg = ctxArg; this.nameArg = nameArg; }
        @Override public Object executeGeneric(VirtualFrame frame)
        {
            return doExecute(ctxArg.executeGeneric(frame), (String) nameArg.executeGeneric(frame));
        }
        @CompilerDirectives.TruffleBoundary
        private static PureSequence doExecute(Object ctx, String name)
        {
            Object r = invokeNamed(ctx, name);
            if (r == null) return PureSequence.EMPTY;
            if (r instanceof List<?> list)
            {
                if (list.isEmpty()) return PureSequence.EMPTY;
                Object[] out = new Object[list.size()];
                for (int i = 0; i < list.size(); i++)
                {
                    Object o = list.get(i);
                    out[i] = o instanceof TerminalNode t ? t.getText() : null;
                }
                return new ObjectSequence(out);
            }
            if (r instanceof TerminalNode t) return new ObjectSequence(new Object[]{t.getText()});
            return PureSequence.EMPTY;
        }
    }

    // ============================================================
    // hasChild / hasToken
    // ============================================================

    @NodeInfo(shortName = "hasChild")
    public static final class HasChildNode extends PureNode
    {
        @Child private PureNode ctxArg;
        @Child private PureNode nameArg;
        public HasChildNode(PureNode ctxArg, PureNode nameArg) { this.ctxArg = ctxArg; this.nameArg = nameArg; }
        @Override public Object executeGeneric(VirtualFrame frame)
        {
            return doExecute(ctxArg.executeGeneric(frame), (String) nameArg.executeGeneric(frame));
        }
        @CompilerDirectives.TruffleBoundary
        private static boolean doExecute(Object ctx, String name) { return invokeNamed(ctx, name) != null; }
    }

    @NodeInfo(shortName = "hasToken")
    public static final class HasTokenNode extends PureNode
    {
        @Child private PureNode ctxArg;
        @Child private PureNode nameArg;
        public HasTokenNode(PureNode ctxArg, PureNode nameArg) { this.ctxArg = ctxArg; this.nameArg = nameArg; }
        @Override public Object executeGeneric(VirtualFrame frame)
        {
            return doExecute(ctxArg.executeGeneric(frame), (String) nameArg.executeGeneric(frame));
        }
        @CompilerDirectives.TruffleBoundary
        private static boolean doExecute(Object ctx, String name) { return invokeNamed(ctx, name) instanceof TerminalNode; }
    }

    // ============================================================
    // Source position accessors (long-typed for Pure Integer)
    // ============================================================

    @NodeInfo(shortName = "getStartLine")
    public static final class GetStartLineNode extends PureNode
    {
        @Child private PureNode ctxArg;
        public GetStartLineNode(PureNode ctxArg) { this.ctxArg = ctxArg; }
        @Override public Object executeGeneric(VirtualFrame frame) { return doExecute(ctxArg.executeGeneric(frame)); }
        @CompilerDirectives.TruffleBoundary
        private static long doExecute(Object ctx) { return ((ParserRuleContext) ctx).getStart().getLine(); }
    }

    @NodeInfo(shortName = "getStartColumn")
    public static final class GetStartColumnNode extends PureNode
    {
        @Child private PureNode ctxArg;
        public GetStartColumnNode(PureNode ctxArg) { this.ctxArg = ctxArg; }
        @Override public Object executeGeneric(VirtualFrame frame) { return doExecute(ctxArg.executeGeneric(frame)); }
        @CompilerDirectives.TruffleBoundary
        private static long doExecute(Object ctx) { return ((ParserRuleContext) ctx).getStart().getCharPositionInLine() + 1L; }
    }

    @NodeInfo(shortName = "getStopLine")
    public static final class GetStopLineNode extends PureNode
    {
        @Child private PureNode ctxArg;
        public GetStopLineNode(PureNode ctxArg) { this.ctxArg = ctxArg; }
        @Override public Object executeGeneric(VirtualFrame frame) { return doExecute(ctxArg.executeGeneric(frame)); }
        @CompilerDirectives.TruffleBoundary
        private static long doExecute(Object ctx) { return ((ParserRuleContext) ctx).getStop().getLine(); }
    }

    @NodeInfo(shortName = "getStopColumn")
    public static final class GetStopColumnNode extends PureNode
    {
        @Child private PureNode ctxArg;
        public GetStopColumnNode(PureNode ctxArg) { this.ctxArg = ctxArg; }
        @Override public Object executeGeneric(VirtualFrame frame) { return doExecute(ctxArg.executeGeneric(frame)); }
        @CompilerDirectives.TruffleBoundary
        private static long doExecute(Object ctx)
        {
            ParserRuleContext c = (ParserRuleContext) ctx;
            return c.getStop().getCharPositionInLine() + (long) c.getStop().getText().length();
        }
    }

    // ============================================================
    // getChildTextAt — text of the i-th child (ParserRuleContext or TerminalNode)
    // Used by the precedence ladder to read operator tokens.
    // ============================================================

    @NodeInfo(shortName = "getChildTextAt")
    public static final class GetChildTextAtNode extends PureNode
    {
        @Child private PureNode ctxArg;
        @Child private PureNode idxArg;
        public GetChildTextAtNode(PureNode ctxArg, PureNode idxArg) { this.ctxArg = ctxArg; this.idxArg = idxArg; }
        @Override public Object executeGeneric(VirtualFrame frame)
        {
            return doExecute(ctxArg.executeGeneric(frame), ((Long) idxArg.executeGeneric(frame)).intValue());
        }
        @CompilerDirectives.TruffleBoundary
        private static String doExecute(Object ctx, int idx)
        {
            ParserRuleContext c = (ParserRuleContext) ctx;
            return idx < c.children.size() ? c.children.get(idx).getText() : null;
        }
    }

    // ============================================================
    // stripTripleQuotesDedented — JEP-378 dedent
    // ============================================================

    @NodeInfo(shortName = "stripTripleQuotesDedented")
    public static final class StripTripleQuotesDedentedNode extends PureNode
    {
        @Child private PureNode textArg;
        public StripTripleQuotesDedentedNode(PureNode textArg) { this.textArg = textArg; }
        @Override public Object executeGeneric(VirtualFrame frame)
        {
            return doExecute((String) textArg.executeGeneric(frame));
        }
        @CompilerDirectives.TruffleBoundary
        private static String doExecute(String text)
        {
            return org.finos.legend.pure.next.parser.shared.TripleStringStripper.strip(text);
        }
    }

    // ============================================================
    // parseAntlr — drive ANTLR by grammar name and return the root context
    // ============================================================

    @NodeInfo(shortName = "parseAntlr")
    public static final class ParseAntlrNode extends PureNode
    {
        @Child private PureNode sourceArg;
        @Child private PureNode grammarArg;
        @Child private PureNode sourceIdArg;
        @Child private PureNode lineOffsetArg;
        public ParseAntlrNode(PureNode sourceArg, PureNode grammarArg, PureNode sourceIdArg, PureNode lineOffsetArg)
        {
            this.sourceArg = sourceArg;
            this.grammarArg = grammarArg;
            this.sourceIdArg = sourceIdArg;
            this.lineOffsetArg = lineOffsetArg;
        }
        @Override public Object executeGeneric(VirtualFrame frame)
        {
            String source = (String) sourceArg.executeGeneric(frame);
            String grammar = (String) grammarArg.executeGeneric(frame);
            String sourceId = (String) sourceIdArg.executeGeneric(frame);
            long lineOffset = (long) lineOffsetArg.executeGeneric(frame);
            return doExecute(source, grammar, sourceId, (int) lineOffset);
        }
        @CompilerDirectives.TruffleBoundary
        private static Object doExecute(String source, String grammar, String sourceId, int lineOffset)
        {
            // Dispatch via the per-runtime GrammarExtension registry (set up
            // by PureTruffleRuntime.Builder.withGrammarExtensions). The
            // node itself stays grammar-agnostic.
            org.finos.legend.pure.next.parser.GrammarExtension ext =
                    org.finos.legend.pure.truffle.PureLanguage.get(null).grammars().get(grammar);
            if (ext == null)
            {
                throw new RuntimeException("Unknown grammar: " + grammar
                        + " (no GrammarExtension registered; pass it via PureTruffleRuntime.Builder.withGrammarExtensions)");
            }
            return ext.parse(source, sourceId, lineOffset);
        }
    }

    // ============================================================
    // Parse-tree helper
    // ============================================================

    @NodeInfo(shortName = "computeFirstNonNewlineLine")
    public static final class ComputeFirstNonNewlineLineNode extends PureNode
    {
        @Child private PureNode ctxArg;
        @Child private PureNode syntheticHeaderArg;
        public ComputeFirstNonNewlineLineNode(PureNode ctxArg, PureNode syntheticHeaderArg)
        {
            this.ctxArg = ctxArg;
            this.syntheticHeaderArg = syntheticHeaderArg;
        }
        @Override public Object executeGeneric(VirtualFrame frame)
        {
            Object raw = ctxArg.executeGeneric(frame);
            boolean syntheticHeader = (boolean) syntheticHeaderArg.executeGeneric(frame);
            // [0..1] args may arrive as a PureSequence/List or a bare object.
            Object ctx;
            if (raw == null) ctx = null;
            else if (raw instanceof PureSequence ps) ctx = ps.size() == 0 ? null : ps.getBoxed(0);
            else if (raw instanceof java.util.List<?> l) ctx = l.isEmpty() ? null : l.get(0);
            else ctx = raw;
            return doExecute(ctx, syntheticHeader);
        }
        @CompilerDirectives.TruffleBoundary
        private static long doExecute(Object ctx, boolean syntheticHeader)
        {
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
                return offset;
            }
            return 0L;
        }
    }
}
