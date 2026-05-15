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

package org.finos.legend.pure.generators;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Generator: ANTLR-visitor methods from the m3-visitor DSL.
 *
 * Prototype: covers two rules (variable, instanceLiteralToken).
 *
 * DSL syntax — see m3-mappings.dsl for the full set:
 *   rule X { ... }                       — declares a visitor for ANTLR rule X
 *   emit T                               — what AST node type to construct
 *   field name = expr                    — field assignment
 *   shared field name = expr             — applies across all alternatives
 *   alt when $.X { ... }                 — branch when ctx.X() != null
 *   else error("msg")                    — fallback (emits throw)
 *
 * Expression sub-grammar:
 *   $.X            → ctx.X()
 *   $.X.text       → ctx.X().getText()
 *   $loc           → buildSourceInfo(ctx)
 *   parseLong(e)   → Long.parseLong(e)
 *   parseDouble(e) → Double.parseDouble(e)
 *   parseBoolean(e)→ Boolean.parseBoolean(e)
 *   stripQuotes(e) → e.substring(1, e.length() - 1)            (with caching)
 *   stripPercent(e)→ e.startsWith("%") ? e.substring(1) : e    (with caching)
 *   primitiveType(name)        → buildPrimitiveGenericType(name)
 *   dateLiteralType(textExpr)  → textExpr.contains("T") ? buildPrimitiveGenericType("DateTime")
 *                                                       : buildPrimitiveGenericType("StrictDate")
 *
 * Outputs a single Java source file containing the generated methods. The
 * methods are intended to be folded into M3ProtocolBuilder (or a sibling
 * partial class) by hand or by post-processing.
 */
public final class M3VisitorGenerator
{
    public static void main(String[] args) throws IOException
    {
        if (args.length < 2)
        {
            System.err.println("Usage: M3VisitorGenerator <dsl-file> <output-java-file>");
            System.exit(1);
        }
        Path dslPath = Path.of(args[0]);
        Path outPath = Path.of(args[1]);

        String source = Files.readString(dslPath, StandardCharsets.UTF_8);
        List<Rule> rules = parse(source);

        StringBuilder sb = new StringBuilder();
        emitFullClassHeader(sb, dslPath.getFileName().toString());
        emitParserScaffolding(sb);
        for (Rule r : rules)
        {
            emit(sb, r);
            if (r.topLevel)
            {
                emitTopLevelVisitWrapper(sb, r);
            }
            sb.append('\n');
        }
        sb.append("}\n");

        Files.createDirectories(outPath.getParent());
        Files.writeString(outPath, sb.toString(), StandardCharsets.UTF_8);
        System.out.println("  Wrote " + outPath + " (" + rules.size() + " rule(s))");
    }

    private static void emitFullClassHeader(StringBuilder sb, String dslFileName)
    {
        sb.append("// AUTO-GENERATED from ").append(dslFileName).append(" by M3VisitorGenerator — DO NOT EDIT\n");
        sb.append("// Concrete parser: extends M3ParserBaseVisitor directly. Contains the elements\n");
        sb.append("// accumulator, parser entry points, build<RuleName> methods, and @Override\n");
        sb.append("// visit wrappers for topLevel rules. Fully self-contained — no hand-written\n");
        sb.append("// parent class. To port to another language, port this generator + the DSL.\n");
        sb.append("package org.finos.legend.pure.next.parser.m3;\n\n");
        sb.append("import meta.pure.protocol.grammar.Enum_PointerImpl;\n");
        sb.append("import meta.pure.protocol.grammar.Package_PointerImpl;\n");
        sb.append("import meta.pure.protocol.grammar.PackageableElement;\n");
        sb.append("import meta.pure.protocol.grammar.SourceInformationImpl;\n");
        sb.append("import meta.pure.protocol.grammar.constraint.Constraint;\n");
        sb.append("import meta.pure.protocol.grammar.constraint.ConstraintImpl;\n");
        sb.append("import meta.pure.protocol.grammar.extension.AnnotatedElement;\n");
        sb.append("import meta.pure.protocol.grammar.extension.ProfileImpl;\n");
        sb.append("import meta.pure.protocol.grammar.extension.StereotypeImpl;\n");
        sb.append("import meta.pure.protocol.grammar.extension.Stereotype_PointerImpl;\n");
        sb.append("import meta.pure.protocol.grammar.extension.TagImpl;\n");
        sb.append("import meta.pure.protocol.grammar.extension.Tag_PointerImpl;\n");
        sb.append("import meta.pure.protocol.grammar.extension.TaggedValueImpl;\n");
        sb.append("import meta.pure.protocol.grammar.function.LambdaFunctionImpl;\n");
        sb.append("import meta.pure.protocol.grammar.function.NativeFunctionImpl;\n");
        sb.append("import meta.pure.protocol.grammar.function.UserDefinedFunctionImpl;\n");
        sb.append("import meta.pure.protocol.grammar.function.property.PropertyImpl;\n");
        sb.append("import meta.pure.protocol.grammar.function.property.QualifiedPropertyImpl;\n");
        sb.append("import meta.pure.protocol.grammar.multiplicity.MultiplicityParameter;\n");
        sb.append("import meta.pure.protocol.grammar.multiplicity.MultiplicityValueImpl;\n");
        sb.append("import meta.pure.protocol.grammar.multiplicity.Multiplicity_Protocol;\n");
        sb.append("import meta.pure.protocol.grammar.multiplicity.UndefinedMultiplicityImpl;\n");
        sb.append("import meta.pure.protocol.grammar.multiplicity.UserDefinedAdHocMultiplicityImpl;\n");
        sb.append("import meta.pure.protocol.grammar.multiplicity.UserDefinedMultiplicityParameterImpl;\n");
        sb.append("import meta.pure.protocol.grammar.type.generics.TypeParameterImpl;\n");
        sb.append("import meta.pure.protocol.grammar.PointerValueImpl;\n");
        sb.append("import meta.pure.protocol.grammar.relation.ColumnImpl;\n");
        sb.append("import meta.pure.protocol.grammar.relation.GenericTypeOperationImpl;\n");
        sb.append("import meta.pure.protocol.grammar.relation.RelationTypeImpl;\n");
        sb.append("import meta.pure.protocol.grammar.relationship.AssociationImpl;\n");
        sb.append("import meta.pure.protocol.grammar.relationship.GeneralizationImpl;\n");
        sb.append("import meta.pure.protocol.grammar.type.ClassImpl;\n");
        sb.append("import meta.pure.protocol.grammar.type.EnumerationImpl;\n");
        sb.append("import meta.pure.protocol.grammar.type.FunctionTypeImpl;\n");
        sb.append("import meta.pure.protocol.grammar.type.PrimitiveTypeImpl;\n");
        sb.append("import meta.pure.protocol.grammar.type.Type_PointerImpl;\n");
        sb.append("import meta.pure.protocol.grammar.type.generics.GenericType;\n");
        sb.append("import meta.pure.protocol.grammar.type.generics.TypeParameter;\n");
        sb.append("import meta.pure.protocol.grammar.type.generics.UndefinedGenericTypeImpl;\n");
        sb.append("import meta.pure.protocol.grammar.type.generics.UserDefinedGenericTypeImpl;\n");
        sb.append("import meta.pure.protocol.grammar.valuespecification.ArrowInvocationImpl;\n");
        sb.append("import meta.pure.protocol.grammar.valuespecification.AtomicValueImpl;\n");
        sb.append("import meta.pure.protocol.grammar.valuespecification.CollectionImpl;\n");
        sb.append("import meta.pure.protocol.grammar.valuespecification.CompilerGenericTypeAndMultiplicityHolderImpl;\n");
        sb.append("import meta.pure.protocol.grammar.valuespecification.DotApplicationImpl;\n");
        sb.append("import meta.pure.protocol.grammar.valuespecification.FunctionInvocationImpl;\n");
        sb.append("import meta.pure.protocol.grammar.valuespecification.UserDefinedGenericTypeAndMultiplicityHolderImpl;\n");
        sb.append("import meta.pure.protocol.grammar.valuespecification.ValueSpecification;\n");
        sb.append("import meta.pure.protocol.grammar.valuespecification.VariableExpressionImpl;\n");
        sb.append("import org.antlr.v4.runtime.ParserRuleContext;\n");
        sb.append("import org.antlr.v4.runtime.Token;\n");
        sb.append("import org.eclipse.collections.api.list.MutableList;\n");
        sb.append("import org.eclipse.collections.impl.factory.Lists;\n");
        sb.append("import org.eclipse.collections.impl.list.mutable.ListAdapter;\n");
        sb.append("import org.finos.legend.pure.next.parser.m3.helper._G_PackageableFunction;\n\n");
        sb.append("public class M3ProtocolBuilder extends M3ParserBaseVisitor<Object>\n");
        sb.append("{\n");
        sb.append("    protected final MutableList<PackageableElement> elements = Lists.mutable.empty();\n\n");
    }

    /**
     * Emit the boilerplate scaffolding every generated parser class needs:
     * the lineOffset field, the source-information helpers, the precedence-ladder
     * sub-helpers, and the public parseElements entry points. None of these vary
     * with the DSL, so they live in the generator as fixed templates.
     */
    private static void emitParserScaffolding(StringBuilder sb)
    {
        sb.append("    protected int lineOffset = 0;\n\n");
        sb.append("    /** Parse Pure source and return the list of top-level packageable elements. */\n");
        sb.append("    public java.util.List<PackageableElement> parseElements(final String source, final int lineOffsetIn)\n");
        sb.append("    {\n");
        sb.append("        this.lineOffset = lineOffsetIn;\n");
        sb.append("        M3Lexer lexer = new M3Lexer(org.antlr.v4.runtime.CharStreams.fromString(source));\n");
        sb.append("        org.antlr.v4.runtime.CommonTokenStream tokens = new org.antlr.v4.runtime.CommonTokenStream(lexer);\n");
        sb.append("        M3Parser parser = new M3Parser(tokens);\n");
        sb.append("        parser.removeErrorListeners();\n");
        sb.append("        parser.addErrorListener(new org.antlr.v4.runtime.BaseErrorListener()\n");
        sb.append("        {\n");
        sb.append("            @Override\n");
        sb.append("            public void syntaxError(org.antlr.v4.runtime.Recognizer<?, ?> recognizer, Object offendingSymbol,\n");
        sb.append("                                    int line, int charPositionInLine, String msg, org.antlr.v4.runtime.RecognitionException e)\n");
        sb.append("            {\n");
        sb.append("                throw new RuntimeException(\"Parse error in file \" + source + \" at line \" + (line + lineOffsetIn) + \":\" + charPositionInLine + \" - \" + msg);\n");
        sb.append("            }\n");
        sb.append("        });\n");
        sb.append("        visit(parser.definition());\n");
        sb.append("        return elements;\n");
        sb.append("    }\n\n");
        sb.append("    /** parseElements with no line offset (lineOffset = 0). */\n");
        sb.append("    public java.util.List<PackageableElement> parseElements(final String source)\n");
        sb.append("    {\n");
        sb.append("        return parseElements(source, 0);\n");
        sb.append("    }\n\n");
        sb.append("    protected SourceInformationImpl buildSourceInfo(final ParserRuleContext ctx)\n");
        sb.append("    {\n");
        sb.append("        return new SourceInformationImpl()\n");
        sb.append("                ._startLine((long) ctx.getStart().getLine() + lineOffset)\n");
        sb.append("                ._startColumn((long) ctx.getStart().getCharPositionInLine() + 1)\n");
        sb.append("                ._endLine((long) ctx.getStop().getLine() + lineOffset)\n");
        sb.append("                ._endColumn((long) (ctx.getStop().getCharPositionInLine() + ctx.getStop().getText().length()));\n");
        sb.append("    }\n\n");
        sb.append("    /** Operator token between the i-th and (i-1)-th operand in a left-fold context. */\n");
        sb.append("    protected Token operatorTokenAt(final ParserRuleContext ctx, final int operandIndex)\n");
        sb.append("    {\n");
        sb.append("        return ((org.antlr.v4.runtime.tree.TerminalNode) ctx.getChild(2 * operandIndex - 1)).getSymbol();\n");
        sb.append("    }\n\n");
        sb.append("    /** Span from LHS start to RHS end (falls back to operator token when LHS has no source info). */\n");
        sb.append("    protected SourceInformationImpl buildOpSourceInfo(final Token opTok, final ParserRuleContext rhsCtx, final ValueSpecification left)\n");
        sb.append("    {\n");
        sb.append("        meta.pure.protocol.grammar.SourceInformation leftSrc = left._p_sourceInformation();\n");
        sb.append("        long startLine = leftSrc != null && leftSrc._startLine() != null ? leftSrc._startLine() : (long) opTok.getLine() + lineOffset;\n");
        sb.append("        long startCol = leftSrc != null && leftSrc._startColumn() != null ? leftSrc._startColumn() : (long) opTok.getCharPositionInLine() + 1;\n");
        sb.append("        return new SourceInformationImpl()\n");
        sb.append("                ._startLine(startLine)\n");
        sb.append("                ._startColumn(startCol)\n");
        sb.append("                ._endLine((long) rhsCtx.getStop().getLine() + lineOffset)\n");
        sb.append("                ._endColumn((long) (rhsCtx.getStop().getCharPositionInLine() + rhsCtx.getStop().getText().length()));\n");
        sb.append("    }\n\n");
    }

    /**
     * Emit an @Override `visit<RuleName>` wrapper that calls the rule's build method,
     * appends to `elements`, and returns the built value as Object (ANTLR convention).
     */
    private static void emitTopLevelVisitWrapper(StringBuilder sb, Rule r)
    {
        String ctxType = "M3Parser." + (r.contextTypeOverride != null ? r.contextTypeOverride : capitalize(r.name) + "Context");
        String visitName = "visit" + capitalize(r.name);
        String buildName = r.methodNameOverride != null ? r.methodNameOverride : methodNameFor(r.name);
        String implType = r.emitType + "Impl";
        sb.append("\n    @Override\n");
        sb.append("    public Object ").append(visitName).append("(final ").append(ctxType).append(" ctx)\n    {\n");
        sb.append("        ").append(implType).append(" __built = ").append(buildName).append("(ctx);\n");
        sb.append("        elements.add(__built);\n");
        sb.append("        return __built;\n    }\n");
    }

    // ------------------------------------------------------------------ model

    private static final class Rule
    {
        String name;
        // Emit-rule fields
        String emitType;
        List<Field> sharedFields = new ArrayList<>();
        List<Alt> alts = new ArrayList<>();
        String elseError;      // null if no `else error(...)` line
        // Left-fold-rule fields (mutually exclusive with the emit-rule fields)
        LeftFold leftFold;     // null for emit-rules
        // Chain-fold-rule fields (mutually exclusive with emit / left_fold / delegate).
        // A chain_fold rule maps grammar of the shape `seed (chained-element)*` to
        // `injectInto(seed, (acc, it) -> dispatch(it))`. Each `alt` describes one
        // chain element; the `step EXPR` body computes the next accumulator value.
        ChainFold chainFold;   // null for non chain-fold rules
        // Grow-list-rule fields: dispatch-and-collect over a list of contexts.
        // Generates `collectIf(any-alt-matches, dispatch-to-matching-alt)`. Used for
        // grammar like `codeBlock: programLine ...` where each programLine is mapped
        // to a value depending on its sub-context (combinedExpression vs letExpression).
        GrowList growList;     // null for non grow-list rules
        // Delegate rules forward straight to another build method.
        String delegateTarget; // e.g. "orExpression"; null when not a delegate
        String delegateReturn; // return type for the generated method
        // Post-build statements run after the emit chain, before the final `return`.
        // Each entry is a raw DSL expression; substituted and emitted as a Java statement.
        // When `when` is non-null, the emitted statement is wrapped in `if (when) ...`.
        List<PostAction> postActions = new ArrayList<>();
        // Extra method parameters (besides the always-present `ctx`). Declared via
        // `param TYPE name` in the rule body; emitted as `final TYPE name, ` ahead of
        // ctx in the generated method signature. Each entry is `[type, name]`.
        // Used for visitor methods that receive a "current value" alongside the ctx
        // (e.g. propertyExpression takes a receiver ValueSpecification).
        List<String[]> extraParams = new ArrayList<>();
        // Optional method-name override. When non-null, the generated method is named
        // exactly this value, ignoring `methodNameFor(name)`. Declared via
        // `method NAME` in the rule body. Used when the canonical Java method name
        // diverges from the `build<RuleName>` convention (e.g. `buildGenericType` for
        // the `type` grammar rule).
        String methodNameOverride;
        // Optional context-type override. When non-null, the generated method's ctx
        // parameter uses this type (e.g. `IdentifierContext`) instead of the type
        // derived from the rule name. Declared via `context CtxType`. Used for
        // helper rules that target a generic grammar rule like `identifier`.
        String contextTypeOverride;
        // Let-bindings declared at the rule body level. Each entry is
        // `[type, name, exprDsl]`. Emitted as `TYPE NAME = <java>;` at the
        // start of the method body. Used for values that need to be referenced
        // multiple times (predicate + field) or sequentially mutated via `set`.
        // Non-final so `set NAME = EXPR` can rebind.
        List<String[]> lets = new ArrayList<>();
        // Sequential rebinds declared after lets. Each entry is `[name, exprDsl]`.
        // Emitted as `NAME = <java>;` in declared order, between lets and the alt
        // construction. Used for staged transforms (e.g. type-algebra wrappings).
        List<String[]> sets = new ArrayList<>();
        // Top-level marker: when true the generator emits an extra `@Override public
        // Object visit<RuleName>(ctx)` wrapper that calls buildX, registers it in the
        // `elements` list, and returns it. Used for grammar rules that correspond to
        // file-level packageable elements (class, function, profile, etc.).
        boolean topLevel;
        // Explicit return type for multi-alt rules whose alts produce different
        // concrete types (or where one alt delegates / returns an expression).
        String returnType;     // e.g. "ValueSpecification"; null = inferred
    }

    private static final class Alt
    {
        String predicate;      // e.g. "$.INTEGER" or null for "always" (single-branch rule)
        List<Field> fields = new ArrayList<>();
        // Per-alt overrides (mutually exclusive). When set, the alt's body is one
        // of: emit a fresh T (default — uses rule.emitType), `return EXPR`, or
        // `delegate $.X` (calls buildX(ctx.X())).
        String altEmitType;    // override rule.emitType for this branch only
        String returnExpr;     // raw DSL expression; alt body is `return <expr>`
        String delegateRule;   // child rule name; alt body is `return build<X>(ctx.X())`
    }

    private static final class Field
    {
        String name;
        String expr;           // raw DSL expression text
        String predicate;      // non-null = conditional (`optional field X when $.Y = EXPR`)
    }

    private static final class PostAction
    {
        String predicate;      // non-null = `post when EXPR STMT` (wrap STMT in `if (EXPR) ...`)
        String stmt;           // raw DSL statement
    }

    private static final class LeftFold
    {
        String operandRule;                          // e.g. "andExpression"
        // Per-iteration step body. Inside the step expression(s) the bindings
        // `acc` (current accumulator), `rhs` (just-built right operand),
        // `$tok` (operator token), and `$loc` (operator source-info) resolve
        // to the corresponding Java locals in the emitted loop.
        List<String[]> lets = new ArrayList<>();     // [type, name, exprDsl]
        String stepExpr;                              // single unconditional step
        List<Alt> alts = new ArrayList<>();           // token-dispatch (alt when $tok = TOK { step EXPR })
        String elseStep;                              // raw DSL for `alt else { step EXPR }`
    }

    /**
     * chain_fold from SEED over $.X { alt when $it.Y { step EXPR } ... [else step EXPR] }
     *
     * Generates: ListAdapter.adapt(ctx.X()).injectInto(SEED, (acc, it) -> {
     *     if (it.Y() != null) return EXPR;
     *     ...
     *     return ELSE_EXPR;  // or `acc` by default
     * });
     *
     * Used for grammar rules of the shape `seed (chained-element)*` where each
     * chained-element dispatches on which sub-context is present. The alt
     * step-exprs may reference {@code acc} (the running accumulator) and
     * {@code $it.X} (current iteration's sub-context — translated to
     * {@code it.X()}).
     */
    private static final class ChainFold
    {
        String seedExpr;       // DSL expression for the initial accumulator
        String overRule;       // e.g. "propertyOrFunctionExpression" — list to fold over
        List<Alt> alts = new ArrayList<>();
        String elseStep;       // raw DSL expression for the trailing `else step EXPR`; null = `acc`
    }

    /**
     * grow_list over $.X { alt when $it.Y { yield EXPR } ... }
     *
     * Generates `ListAdapter.adapt(ctx.X()).collectIf(P, F)` where:
     *   P = any-alt-matches predicate (or of all alt conditions)
     *   F = dispatch lambda: if (it.Y() != null) return EXPR; ...
     *
     * Each alt's element type (`yield EXPR`) is stored in {@link Alt#returnExpr},
     * re-using the per-branch value field. Items that don't match any alt are
     * filtered out (the collectIf predicate excludes them).
     */
    private static final class GrowList
    {
        String overRule;       // e.g. "programLine"
        List<Alt> alts = new ArrayList<>();
    }

    // ------------------------------------------------------------------ parser

    private static List<Rule> parse(String source)
    {
        List<String> lines = new ArrayList<>();
        // Continuation join: a line whose paren count is positive joins forward until
        // balanced. Lets long field expressions (e.g. nested `newImpl(...)`) be split
        // across lines for readability.
        StringBuilder pending = null;
        int pendingDepth = 0;
        for (String l : source.split("\n"))
        {
            String trimmed = stripComment(l).strip();
            if (trimmed.isEmpty())
            {
                continue;
            }
            if (pending != null)
            {
                pending.append(' ').append(trimmed);
                pendingDepth += parenDelta(trimmed);
                if (pendingDepth == 0)
                {
                    lines.add(pending.toString());
                    pending = null;
                }
                continue;
            }
            int delta = parenDelta(trimmed);
            if (delta > 0)
            {
                pending = new StringBuilder(trimmed);
                pendingDepth = delta;
            }
            else
            {
                lines.add(trimmed);
            }
        }
        if (pending != null)
        {
            // Tolerant: emit what we have, the body parser will complain if invalid.
            lines.add(pending.toString());
        }

        List<Rule> rules = new ArrayList<>();
        int i = 0;
        while (i < lines.size())
        {
            String line = lines.get(i);
            if (line.startsWith("rule ") || line.startsWith("helper "))
            {
                Rule r = new Rule();
                boolean isHelper = line.startsWith("helper ");
                String head = line.substring((isHelper ? "helper " : "rule ").length()).replace("{", "").strip();
                // Optional `as Type` suffix declares an explicit return type.
                int asIdx = head.indexOf(" as ");
                if (asIdx >= 0)
                {
                    r.returnType = head.substring(asIdx + " as ".length()).strip();
                    head = head.substring(0, asIdx).strip();
                }
                if (isHelper)
                {
                    // helper buildName(ExtraType1 name1, …, CtxType ctx)
                    // The last argument is the context (name MUST be `ctx`); earlier args
                    // become extra params before ctx in the generated method signature.
                    int paren = head.indexOf('(');
                    if (paren < 0 || !head.endsWith(")"))
                    {
                        throw new RuntimeException("helper needs `(ExtraT name, …, CtxType ctx)`: " + line);
                    }
                    r.name = head.substring(0, paren).strip();
                    r.methodNameOverride = r.name;
                    String argsStr = head.substring(paren + 1, head.length() - 1).strip();
                    List<String> args = splitTopLevelCommas(argsStr);
                    if (args.isEmpty()) throw new RuntimeException("helper needs at least the ctx param: " + line);
                    String[] ctxArg = args.get(args.size() - 1).strip().split("\\s+");
                    if (ctxArg.length != 2 || !"ctx".equals(ctxArg[1]))
                    {
                        throw new RuntimeException("helper's last param must be `CtxType ctx`: " + line);
                    }
                    r.contextTypeOverride = ctxArg[0];
                    for (int k = 0; k < args.size() - 1; k++)
                    {
                        String[] p = args.get(k).strip().split("\\s+");
                        if (p.length != 2) throw new RuntimeException("helper param must be `TYPE name`: " + args.get(k));
                        r.extraParams.add(new String[] {p[0], p[1]});
                    }
                }
                else
                {
                    r.name = head;
                }
                i++;
                // Inside rule block — `depth` 1 = inside rule, 2 = inside an alt or left_fold block.
                int depth = 1;
                Alt currentAlt = null;
                while (i < lines.size() && depth > 0)
                {
                    String body = lines.get(i);
                    if (body.equals("}"))
                    {
                        // Closing brace: alt / left_fold / chain_fold / grow_list / nested alt / rule.
                        if (currentAlt != null)
                        {
                            // Belongs to whichever container is currently open.
                            if (r.chainFold != null)
                            {
                                r.chainFold.alts.add(currentAlt);
                            }
                            else if (r.growList != null)
                            {
                                r.growList.alts.add(currentAlt);
                            }
                            else if (r.leftFold != null)
                            {
                                r.leftFold.alts.add(currentAlt);
                            }
                            else
                            {
                                r.alts.add(currentAlt);
                            }
                            currentAlt = null;
                        }
                        depth--;
                        i++;
                        continue;
                    }
                    if (body.startsWith("alt when "))
                    {
                        currentAlt = new Alt();
                        currentAlt.predicate = body.substring("alt when ".length()).replace("{", "").strip();
                        depth++;
                    }
                    else if (body.startsWith("alt else"))
                    {
                        // `alt else { ... }` — default branch (predicate-less alt).
                        currentAlt = new Alt();
                        currentAlt.predicate = null;
                        depth++;
                    }
                    else if (currentAlt != null && body.startsWith("return "))
                    {
                        // Per-alt `return EXPR`. If EXPR is `newImpl(T, …)` with at least
                        // one 2-arg ifPresent (conditional) field, decompose into alt fields
                        // so each conditional becomes `if (P) __r._k(e);`. Otherwise leave
                        // as a raw returnExpr (fluent chain emitted via exprToJava).
                        String returnExpr = body.substring("return ".length()).strip();
                        if (!tryDecomposeUnifiedAltReturn(currentAlt, returnExpr))
                        {
                            currentAlt.returnExpr = returnExpr;
                        }
                    }
                    else if (currentAlt == null && body.startsWith("return "))
                    {
                        // Rule-level `return EXPR`. Two shapes are accepted:
                        //   1. Unified: `return newImpl(T, k=v, k=ifPresent(p, e), ...)` or
                        //      `return register(newImpl(T, ...))` — decomposed into emit
                        //      + fields, so the existing fluent-chain / __result emitter
                        //      can do the rest. 2-arg `ifPresent(p, e)` becomes an
                        //      optional field; `register(…)` flags the rule as topLevel.
                        //   2. Anything else — synthesize a single unconditional alt
                        //      (delegated call, sub-rule call, etc.).
                        String returnExpr = body.substring("return ".length()).strip();
                        if (!tryDecomposeUnifiedReturn(r, returnExpr))
                        {
                            // `register(X)` outside a decomposable newImpl — peel the wrapper
                            // and flag the rule as topLevel; the synth-alt then returns the
                            // inner expression, and the topLevel visit wrapper does the
                            // `elements.add(...)` at emission time.
                            if (returnExpr.startsWith("register(") && returnExpr.endsWith(")")
                                    && parenBalanced(returnExpr, "register(".length(), returnExpr.length() - 1))
                            {
                                r.topLevel = true;
                                returnExpr = returnExpr.substring("register(".length(), returnExpr.length() - 1).strip();
                            }
                            Alt synth = new Alt();
                            synth.predicate = null;
                            synth.returnExpr = returnExpr;
                            r.alts.add(synth);
                        }
                    }
                    else if (currentAlt != null && body.startsWith("step "))
                    {
                        // chain_fold alt body: `step EXPR` — the value of the next acc.
                        // Stored in returnExpr (re-used as the dispatch-branch value).
                        currentAlt.returnExpr = body.substring("step ".length()).strip();
                    }
                    else if (body.startsWith("else error("))
                    {
                        String inner = body.substring("else error(".length());
                        inner = inner.substring(0, inner.lastIndexOf(')'));
                        r.elseError = inner.strip();
                    }
                    else if (body.startsWith("post "))
                    {
                        // Post-build action: side-effect statement run after the emit chain.
                        // Two forms:
                        //   post STMT                       (unconditional)
                        //   post when PRED => STMT          (wrap STMT in `if (PRED) ...`)
                        String tail = body.substring("post ".length()).strip();
                        PostAction pa = new PostAction();
                        if (tail.startsWith("when "))
                        {
                            String rest = tail.substring("when ".length()).strip();
                            int arrow = rest.indexOf("=>");
                            if (arrow < 0)
                            {
                                throw new RuntimeException("`post when` needs `=> STMT` after the predicate: " + body);
                            }
                            pa.predicate = rest.substring(0, arrow).strip();
                            pa.stmt = rest.substring(arrow + 2).strip();
                        }
                        else
                        {
                            pa.stmt = tail;
                        }
                        r.postActions.add(pa);
                    }
                    else if (body.startsWith("param "))
                    {
                        // `param TYPE name` declares an extra method parameter before ctx.
                        String tail = body.substring("param ".length()).strip();
                        int sp = tail.lastIndexOf(' ');
                        if (sp < 0) throw new RuntimeException("param needs 'TYPE name': " + body);
                        r.extraParams.add(new String[] {tail.substring(0, sp).strip(), tail.substring(sp + 1).strip()});
                    }
                    else if (body.startsWith("method "))
                    {
                        // `method NAME` overrides the default `build<RuleName>` method name.
                        r.methodNameOverride = body.substring("method ".length()).strip();
                    }
                    else if (body.startsWith("context "))
                    {
                        // `context CtxType` overrides the auto-derived ctx parameter type.
                        // Used for helper rules whose name isn't a real grammar rule.
                        r.contextTypeOverride = body.substring("context ".length()).strip();
                    }
                    else if (body.startsWith("let "))
                    {
                        // `let TYPE NAME = EXPR` declares a local. Routes to the enclosing
                        // container: a left_fold body's `let` is per-iteration and re-evaluated
                        // each loop, while a rule-level `let` is a method-scope binding.
                        String tail = body.substring("let ".length()).strip();
                        int eqIdx = tail.indexOf('=');
                        if (eqIdx < 0) throw new RuntimeException("let needs '=': " + body);
                        String lhs = tail.substring(0, eqIdx).strip();
                        String expr = tail.substring(eqIdx + 1).strip();
                        int sp = lhs.lastIndexOf(' ');
                        if (sp < 0) throw new RuntimeException("let LHS needs 'TYPE NAME': " + body);
                        String[] entry = new String[] {lhs.substring(0, sp).strip(), lhs.substring(sp + 1).strip(), expr};
                        if (r.leftFold != null && currentAlt == null)
                        {
                            r.leftFold.lets.add(entry);
                        }
                        else
                        {
                            r.lets.add(entry);
                        }
                    }
                    else if (body.startsWith("set "))
                    {
                        // `set NAME = EXPR` rebinds a previously declared let-binding.
                        String tail = body.substring("set ".length()).strip();
                        int eqIdx = tail.indexOf('=');
                        if (eqIdx < 0) throw new RuntimeException("set needs '=': " + body);
                        r.sets.add(new String[] {tail.substring(0, eqIdx).strip(), tail.substring(eqIdx + 1).strip()});
                    }
                    else if (body.startsWith("left_fold over "))
                    {
                        // left_fold over $.X { op "name" | op_by_token TOK "name" | when_token TOK wrap_with "name" }
                        r.leftFold = new LeftFold();
                        String lfHead = body.substring("left_fold over ".length()).replace("{", "").strip();
                        if (!lfHead.startsWith("$ctx.")) throw new RuntimeException("left_fold operand must be $ctx.X: " + lfHead);
                        r.leftFold.operandRule = lfHead.substring("$ctx.".length());
                        depth++;
                    }
                    else if (body.startsWith("chain_fold from "))
                    {
                        // chain_fold from SEED over $.X { alt ... [else step EXPR] }
                        r.chainFold = new ChainFold();
                        String cfHead = body.substring("chain_fold from ".length()).replace("{", "").strip();
                        int overIdx = cfHead.indexOf(" over ");
                        if (overIdx < 0) throw new RuntimeException("chain_fold needs ' over $.X': " + body);
                        r.chainFold.seedExpr = cfHead.substring(0, overIdx).strip();
                        String overPart = cfHead.substring(overIdx + " over ".length()).strip();
                        if (!overPart.startsWith("$ctx.")) throw new RuntimeException("chain_fold list must be $ctx.X: " + overPart);
                        r.chainFold.overRule = overPart.substring("$ctx.".length());
                        depth++;
                    }
                    else if (r.chainFold != null && body.startsWith("else step "))
                    {
                        // chain_fold fallback: `else step EXPR` — value when no alt matches.
                        r.chainFold.elseStep = body.substring("else step ".length()).strip();
                    }
                    else if (body.startsWith("grow_list over "))
                    {
                        // grow_list over $.X { alt when $it.Y { yield EXPR } ... }
                        r.growList = new GrowList();
                        String glHead = body.substring("grow_list over ".length()).replace("{", "").strip();
                        if (!glHead.startsWith("$ctx.")) throw new RuntimeException("grow_list list must be $ctx.X: " + glHead);
                        r.growList.overRule = glHead.substring("$ctx.".length());
                        depth++;
                    }
                    else if (currentAlt != null && body.startsWith("yield "))
                    {
                        // grow_list alt body: `yield EXPR` — the value to push into the result list.
                        currentAlt.returnExpr = body.substring("yield ".length()).strip();
                    }
                    else if (r.leftFold != null && currentAlt == null && body.startsWith("step "))
                    {
                        // left_fold body: unconditional `step EXPR` — assigned to result each iteration.
                        r.leftFold.stepExpr = body.substring("step ".length()).strip();
                    }
                    else if (r.leftFold != null && body.startsWith("else step "))
                    {
                        // left_fold body: `else step EXPR` — fallback value when no alt matches.
                        r.leftFold.elseStep = body.substring("else step ".length()).strip();
                    }
                    i++;
                }
                inferUnifiedReturnType(r);
                rules.add(r);
            }
            else
            {
                i++;
            }
        }
        return rules;
    }

    /**
     * Net paren count for a line: each `(` outside string literals contributes +1, each
     * `)` contributes -1. Drives the continuation-join logic so a single field expression
     * may span multiple physical lines (the body parser still sees one logical line).
     */
    private static int parenDelta(String line)
    {
        int depth = 0;
        boolean inStr = false;
        for (int k = 0; k < line.length(); k++)
        {
            char c = line.charAt(k);
            if (c == '"')
            {
                inStr = !inStr;
                continue;
            }
            if (inStr) continue;
            if (c == '(') depth++;
            else if (c == ')') depth--;
        }
        return depth;
    }

    /**
     * Check that the parens at positions [openIdx-1, closeIdx] form a balanced unit —
     * i.e. the closing paren at closeIdx matches the opening paren at openIdx-1, with
     * no premature close in between. Used to verify a `register(...)` wrap is a single
     * top-level call, not e.g. `register(x) + foo`.
     */
    private static boolean parenBalanced(String s, int openIdx, int closeIdx)
    {
        int depth = 1;
        for (int k = openIdx; k < closeIdx; k++)
        {
            char c = s.charAt(k);
            if (c == '(') depth++;
            else if (c == ')')
            {
                if (--depth == 0) return false;
            }
        }
        return depth == 1;
    }

    /**
     * For multi-alt unified rules (each alt has `return newImpl(T, …)`) where the
     * rule has no explicit `as TypeName` and no `emit T`, infer the rule's method
     * return type by inspecting each alt's `returnExpr`. If every alt returns the
     * same `newImpl(T, …)` shape, set returnType = `TImpl`. Otherwise leave it
     * null so the default `ValueSpecification` kicks in.
     */
    private static void inferUnifiedReturnType(Rule r)
    {
        if (r.returnType != null || r.emitType != null) return;
        if (r.alts.isEmpty()) return;
        // Pattern 1: single alt whose return is a bare identifier matching a let — infer
        // the emit type from the matching let's `newImpl(T, …)` value (the let-and-mutate
        // pattern: `let TImpl __r = newImpl(T, …); __r._foo(…); return register(__r)`).
        if (r.alts.size() == 1)
        {
            String e = r.alts.get(0).returnExpr;
            if (e != null)
            {
                String trimmed = e.strip();
                for (String[] l : r.lets)
                {
                    if (l[1].equals(trimmed))
                    {
                        String letExpr = l[2].strip();
                        if (letExpr.startsWith("newImpl(") && letExpr.endsWith(")")
                                && tryMatchPrimitiveCall(letExpr, 0) == letExpr.length())
                        {
                            String args = letExpr.substring("newImpl(".length(), letExpr.length() - 1);
                            List<String> parts = splitTopLevelCommas(args);
                            if (!parts.isEmpty())
                            {
                                r.emitType = parts.get(0).strip();
                                r.returnType = r.emitType + "Impl";
                                return;
                            }
                        }
                    }
                }
            }
        }
        // Pattern 2: every alt either has a decomposed altEmitType (per-alt fields, after
        // the unified-alt decomposer ran) or a `newImpl(SameT, …)` returnExpr.
        // Either way, all alts must agree on a single emit type.
        String commonType = null;
        for (Alt a : r.alts)
        {
            String t;
            if (a.altEmitType != null)
            {
                t = a.altEmitType;
            }
            else if (a.returnExpr != null)
            {
                String e = a.returnExpr.strip();
                if (!e.startsWith("newImpl(") || !e.endsWith(")")) return;
                if (tryMatchPrimitiveCall(e, 0) != e.length()) return;
                String args = e.substring("newImpl(".length(), e.length() - 1);
                List<String> parts = splitTopLevelCommas(args);
                if (parts.isEmpty()) return;
                t = parts.get(0).strip();
            }
            else
            {
                return;
            }
            if (commonType == null) commonType = t;
            else if (!commonType.equals(t)) return;
        }
        if (commonType != null) r.returnType = commonType + "Impl";
    }

    /**
     * Decompose a unified-form `return EXPR` into the existing emit + fields IR.
     *   `return newImpl(T, k1=v1, k2=v2, ...)`
     *   `return register(newImpl(T, ...))`             — flags rule as topLevel
     * Each k=v becomes a field; `k = ifPresent(p, e)` 2-arg becomes an optional field.
     * Returns false (no decomposition) for anything else — the caller falls back to a
     * synth-alt with the raw expression.
     */
    private static boolean tryDecomposeUnifiedReturn(Rule r, String expr)
    {
        String inner = expr.strip();
        boolean wrappedInRegister = false;
        if (inner.startsWith("register(") && inner.endsWith(")")
                && parenBalanced(inner, "register(".length(), inner.length() - 1))
        {
            wrappedInRegister = true;
            inner = inner.substring("register(".length(), inner.length() - 1).strip();
        }
        if (!inner.startsWith("newImpl(") || !inner.endsWith(")"))
        {
            return false;
        }
        if (tryMatchPrimitiveCall(inner, 0) != inner.length())
        {
            return false;
        }
        String args = inner.substring("newImpl(".length(), inner.length() - 1);
        List<String> parts = splitTopLevelCommas(args);
        if (parts.isEmpty()) return false;
        String emitType = parts.get(0).strip();
        Alt defaultAlt = new Alt();
        for (int j = 1; j < parts.size(); j++)
        {
            Field f = parseUnifiedField(parts.get(j).strip());
            if (f == null) return false;
            defaultAlt.fields.add(f);
        }
        // Commit only after full success — no partial side effects on the Rule
        r.emitType = emitType;
        r.alts.add(defaultAlt);
        if (wrappedInRegister) r.topLevel = true;
        return true;
    }

    /**
     * Per-alt decomposer: if the alt's return is `newImpl(T, k=v, k=ifPresent(p, e), …)`
     * with at least one 2-arg ifPresent, set alt.altEmitType=T and alt.fields from the
     * args. Otherwise return false so the caller stores returnExpr unchanged (the
     * existing fluent-chain emitter handles unconditional newImpl returns just fine).
     */
    private static boolean tryDecomposeUnifiedAltReturn(Alt a, String expr)
    {
        String inner = expr.strip();
        if (!inner.startsWith("newImpl(") || !inner.endsWith(")")) return false;
        if (tryMatchPrimitiveCall(inner, 0) != inner.length()) return false;
        String args = inner.substring("newImpl(".length(), inner.length() - 1);
        List<String> parts = splitTopLevelCommas(args);
        if (parts.isEmpty()) return false;
        List<Field> fields = new ArrayList<>();
        boolean anyConditional = false;
        for (int j = 1; j < parts.size(); j++)
        {
            Field f = parseUnifiedField(parts.get(j).strip());
            if (f == null) return false;
            if (f.predicate != null) anyConditional = true;
            fields.add(f);
        }
        if (!anyConditional) return false;
        a.altEmitType = parts.get(0).strip();
        a.fields.addAll(fields);
        return true;
    }

    /**
     * Parse one `k = v` field of a unified `newImpl(...)` call. If `v` is a 2-arg
     * `ifPresent(pred, expr)`, the field is optional (predicate=pred, expr=expr).
     * Otherwise it's an unconditional field.
     */
    private static Field parseUnifiedField(String kv)
    {
        int eq = kv.indexOf('=');
        if (eq < 0) return null;
        Field f = new Field();
        f.name = kv.substring(0, eq).strip();
        String value = kv.substring(eq + 1).strip();
        if (value.startsWith("ifPresent(") && tryMatchPrimitiveCall(value, 0) == value.length())
        {
            String inner = value.substring("ifPresent(".length(), value.length() - 1);
            List<String> argParts = splitTopLevelCommas(inner);
            if (argParts.size() == 2)
            {
                f.predicate = argParts.get(0).strip();
                f.expr = argParts.get(1).strip();
                return f;
            }
        }
        f.expr = value;
        return f;
    }

    private static String stripComment(String line)
    {
        int hash = line.indexOf('#');
        return hash >= 0 ? line.substring(0, hash) : line;
    }

    // ------------------------------------------------------------------ emitter

    private static void emit(StringBuilder sb, Rule r)
    {
        if (r.delegateTarget != null)
        {
            emitDelegate(sb, r);
            return;
        }
        if (r.leftFold != null)
        {
            emitLeftFold(sb, r);
            return;
        }
        if (r.chainFold != null)
        {
            emitChainFold(sb, r);
            return;
        }
        if (r.growList != null)
        {
            emitGrowList(sb, r);
            return;
        }
        String ctxType = "M3Parser." + (r.contextTypeOverride != null ? r.contextTypeOverride : capitalize(r.name) + "Context");
        // `build` prefix avoids name collision with ANTLR's M3ParserVisitor
        // interface methods (visit<RuleName>) whose return type is fixed to Object.
        String methodName = r.methodNameOverride != null ? r.methodNameOverride : methodNameFor(r.name);
        boolean hasAltOverrides = r.alts.stream().anyMatch(a -> a.altEmitType != null || a.returnExpr != null || a.delegateRule != null);
        String returnType = r.returnType != null ? r.returnType
                : (hasAltOverrides ? "ValueSpecification" : r.emitType + "Impl");

        sb.append("    protected ").append(returnType).append(' ').append(methodName)
                .append("(").append(extraParamsPrefix(r)).append("final ").append(ctxType).append(" ctx)\n    {\n");
        emitLetBindings(sb, r);
        // Post-actions are emitted here when the rule reaches the multi-alt /
        // single-return path (i.e. the let-and-mutate unified pattern):
        // post-actions mutate a let-bound `__r` and the synth alt then `return __r`.
        // The single-alt-no-return path below has its own (legacy) postActions
        // emission tied to `__result`.
        boolean emitPostsHere = r.alts.size() == 1 && r.alts.get(0).returnExpr != null && !r.postActions.isEmpty();
        if (emitPostsHere)
        {
            for (PostAction post : r.postActions)
            {
                String stmtJava = substituteContextRefs(post.stmt, null);
                if (post.predicate != null)
                {
                    sb.append("        if (").append(predicateAsJava(post.predicate))
                            .append(") ").append(stmtJava).append(";\n");
                }
                else
                {
                    sb.append("        ").append(stmtJava).append(";\n");
                }
            }
        }

        if (r.alts.size() == 1 && r.alts.get(0).predicate == null
                && r.alts.get(0).returnExpr == null && r.alts.get(0).delegateRule == null)
        {
            // Single-branch rule (no `alt when`). Build a single fluent chain.
            String implType = (r.alts.get(0).altEmitType != null ? r.alts.get(0).altEmitType : r.emitType) + "Impl";
            List<Field> all = new ArrayList<>();
            all.addAll(r.alts.get(0).fields);
            all.addAll(r.sharedFields);
            // Split into unconditional vs optional fields. Unconditional fields go in the
            // initial fluent chain; optional fields become `if (predicate) __result._X(EXPR);`.
            List<Field> unconditional = new ArrayList<>();
            List<Field> optional = new ArrayList<>();
            for (Field f : all)
            {
                if (f.predicate != null) optional.add(f);
                else unconditional.add(f);
            }
            boolean needsResultLocal = !r.postActions.isEmpty() || !optional.isEmpty();
            if (!needsResultLocal)
            {
                sb.append("        return new ").append(implType).append("()\n");
                emitFluentChain(sb, unconditional, "                ");
                sb.append(";\n    }\n");
            }
            else
            {
                // Capture in local so post-build actions and optional-field assignments can run.
                sb.append("        ").append(implType).append(" __result = new ").append(implType).append("()\n");
                emitFluentChain(sb, unconditional, "                ");
                sb.append(";\n");
                for (Field f : optional)
                {
                    String cond = predicateAsJava(f.predicate);
                    sb.append("        if (").append(cond).append(") __result.")
                            .append(setter(f.name)).append('(').append(exprToJava(f.expr, null)).append(");\n");
                }
                for (PostAction post : r.postActions)
                {
                    String stmtJava = substituteContextRefs(post.stmt, null).replace("$$result", "__result");
                    if (post.predicate != null)
                    {
                        sb.append("        if (").append(predicateAsJava(post.predicate))
                                .append(") ").append(stmtJava).append(";\n");
                    }
                    else
                    {
                        sb.append("        ").append(stmtJava).append(";\n");
                    }
                }
                sb.append("        return __result;\n    }\n");
            }
            return;
        }

        // Multi-alternative rule. If any alt overrides the emit type or uses
        // return/delegate, we emit per-branch construction with no shared
        // __result variable. Otherwise we set up a shared __result with
        // shared fields and each branch layers on its own fields.
        String defaultImplType = r.emitType != null ? r.emitType + "Impl" : null;
        boolean useSharedResult = !hasAltOverrides && r.alts.stream().allMatch(a -> a.altEmitType == null);
        if (useSharedResult && defaultImplType != null)
        {
            sb.append("        ").append(defaultImplType).append(" __result = new ").append(defaultImplType).append("()");
            if (!r.sharedFields.isEmpty())
            {
                sb.append('\n');
                emitFluentChain(sb, r.sharedFields, "                ");
            }
            sb.append(";\n\n");
        }

        boolean sawAltElse = false;
        for (Alt a : r.alts)
        {
            boolean isElse = a.predicate == null;
            if (isElse)
            {
                // `alt else { ... }` — emit unconditionally (skips the `if` wrapper)
                // and mark that we should not fall through to elseError.
                sawAltElse = true;
            }
            else
            {
                String cond = predicateAsJava(a.predicate);
                sb.append("        if (").append(cond).append(")\n        {\n");
            }
            String indent = isElse ? "        " : "            ";
            // Per-alt action: return, delegate, or emit/build.
            if (a.returnExpr != null)
            {
                sb.append(indent).append("return ").append(exprToJava(a.returnExpr, null)).append(";\n");
                if (!isElse) sb.append("        }\n");
                continue;
            }
            if (a.delegateRule != null)
            {
                String tgt = methodNameFor(a.delegateRule);
                sb.append(indent).append("return ").append(tgt).append("(ctx.").append(a.delegateRule).append("());\n");
                if (!isElse) sb.append("        }\n");
                continue;
            }
            // Emit case (default — uses rule.emitType, or per-alt override).
            String altImpl = (a.altEmitType != null ? a.altEmitType : r.emitType) + "Impl";
            String cachedTextToken = detectRepeatedText(a);
            if (cachedTextToken != null)
            {
                sb.append("            String __t = ctx.").append(cachedTextToken).append("().getText();\n");
            }
            boolean altHasOptional = a.fields.stream().anyMatch(f -> f.predicate != null);
            // `alt else` doesn't emit an `if (cond) {` wrapper, so it must not emit a `}` either.
            String closeBrace = isElse ? "" : "        }\n";
            if (useSharedResult)
            {
                if (!altHasOptional)
                {
                    // Fluent return form preserves the existing layout for alts
                    // with only unconditional fields.
                    sb.append("            return __result");
                    for (Field f : a.fields)
                    {
                        String javaExpr = exprToJava(f.expr, cachedTextToken);
                        sb.append("\n                    .").append(setter(f.name))
                                .append('(').append(javaExpr).append(')');
                    }
                    sb.append(";\n").append(closeBrace);
                }
                else
                {
                    // Statement form: each unconditional field becomes `__result._X(EXPR);`
                    // and each optional field becomes `if (P) __result._X(EXPR);`.
                    for (Field f : a.fields)
                    {
                        String javaExpr = exprToJava(f.expr, cachedTextToken);
                        if (f.predicate != null)
                        {
                            sb.append("            if (").append(predicateAsJava(f.predicate))
                                    .append(") __result.").append(setter(f.name))
                                    .append('(').append(javaExpr).append(");\n");
                        }
                        else
                        {
                            sb.append("            __result.").append(setter(f.name))
                                    .append('(').append(javaExpr).append(");\n");
                        }
                    }
                    sb.append("            return __result;\n").append(closeBrace);
                }
            }
            else
            {
                // Build fresh node — combine shared + alt fields.
                List<Field> all = new ArrayList<>();
                all.addAll(r.sharedFields);
                all.addAll(a.fields);
                boolean anyOptional = all.stream().anyMatch(f -> f.predicate != null);
                if (!anyOptional)
                {
                    sb.append("            return new ").append(altImpl).append("()");
                    if (!all.isEmpty()) sb.append('\n');
                    for (int j = 0; j < all.size(); j++)
                    {
                        Field f = all.get(j);
                        String javaExpr = exprToJava(f.expr, cachedTextToken);
                        sb.append("                    .").append(setter(f.name))
                                .append('(').append(javaExpr).append(')');
                        if (j < all.size() - 1) sb.append('\n');
                    }
                    sb.append(";\n").append(closeBrace);
                }
                else
                {
                    // Use __result local so optional fields can apply.
                    sb.append("            ").append(altImpl).append(" __result = new ").append(altImpl).append("()");
                    List<Field> uncondAll = new ArrayList<>();
                    List<Field> optAll = new ArrayList<>();
                    for (Field f : all)
                    {
                        if (f.predicate != null) optAll.add(f); else uncondAll.add(f);
                    }
                    if (!uncondAll.isEmpty()) sb.append('\n');
                    for (int j = 0; j < uncondAll.size(); j++)
                    {
                        Field f = uncondAll.get(j);
                        String javaExpr = exprToJava(f.expr, cachedTextToken);
                        sb.append("                    .").append(setter(f.name))
                                .append('(').append(javaExpr).append(')');
                        if (j < uncondAll.size() - 1) sb.append('\n');
                    }
                    sb.append(";\n");
                    for (Field f : optAll)
                    {
                        String javaExpr = exprToJava(f.expr, cachedTextToken);
                        sb.append("            if (").append(predicateAsJava(f.predicate))
                                .append(") __result.").append(setter(f.name))
                                .append('(').append(javaExpr).append(");\n");
                    }
                    sb.append("            return __result;\n").append(closeBrace);
                }
            }
        }

        if (!sawAltElse)
        {
            if (r.elseError != null)
            {
                sb.append("        throw new RuntimeException(").append(r.elseError)
                        .append(" + \": \" + ctx.getText());\n");
            }
            else
            {
                sb.append("        throw new RuntimeException(\"No matching alternative for ")
                        .append(r.name).append(": \" + ctx.getText());\n");
            }
        }
        sb.append("    }\n");
    }

    private static void emitDelegate(StringBuilder sb, Rule r)
    {
        String ctxType = "M3Parser." + (r.contextTypeOverride != null ? r.contextTypeOverride : capitalize(r.name) + "Context");
        String methodName = r.methodNameOverride != null ? r.methodNameOverride : methodNameFor(r.name);
        String tgtMethod = methodNameFor(r.delegateTarget);
        sb.append("    protected ").append(r.delegateReturn).append(' ').append(methodName)
                .append("(").append(extraParamsPrefix(r)).append("final ").append(ctxType).append(" ctx)\n    {\n");
        sb.append("        return ").append(tgtMethod).append("(ctx.").append(r.delegateTarget).append("());\n");
        sb.append("    }\n");
    }

    private static void emitLeftFold(StringBuilder sb, Rule r)
    {
        LeftFold lf = r.leftFold;
        String ctxType = "M3Parser." + (r.contextTypeOverride != null ? r.contextTypeOverride : capitalize(r.name) + "Context");
        String rhsCtxType = "M3Parser." + capitalize(lf.operandRule) + "Context";
        String methodName = r.methodNameOverride != null ? r.methodNameOverride : methodNameFor(r.name);
        String operandMethod = methodNameFor(lf.operandRule);
        String returnType = r.returnType != null ? r.returnType : "ValueSpecification";

        sb.append("    protected ").append(returnType).append(' ').append(methodName)
                .append("(").append(extraParamsPrefix(r)).append("final ").append(ctxType).append(" ctx)\n    {\n");
        sb.append("        java.util.List<").append(rhsCtxType).append("> operands = ctx.")
                .append(lf.operandRule).append("();\n");
        sb.append("        ").append(returnType).append(" result = ").append(operandMethod)
                .append("(operands.get(0));\n");
        sb.append("        for (int i = 1; i < operands.size(); i++)\n        {\n");
        sb.append("            Token opTok = operatorTokenAt(ctx, i);\n");
        sb.append("            ").append(rhsCtxType).append(" rhsCtx = operands.get(i);\n");
        sb.append("            ").append(returnType).append(" rhs = ").append(operandMethod).append("(rhsCtx);\n");

        // Per-iteration `let` bindings — the binding expressions can reference acc/rhs/$tok/$loc.
        for (String[] l : lf.lets)
        {
            sb.append("            ").append(l[0]).append(' ').append(l[1])
                    .append(" = ").append(foldExprToJava(l[2])).append(";\n");
        }

        if (lf.stepExpr != null)
        {
            // Single unconditional step.
            sb.append("            result = ").append(foldExprToJava(lf.stepExpr)).append(";\n");
        }
        else if (!lf.alts.isEmpty())
        {
            // Token-dispatch: emit if/else chain. Each alt's predicate is the bare token
            // name (parsed from `$tok = TOK_NAME`); the alt body's `step EXPR` is the value.
            for (int j = 0; j < lf.alts.size(); j++)
            {
                Alt a = lf.alts.get(j);
                if (a.predicate != null)
                {
                    String tokName = a.predicate;
                    // Predicate may have been parsed as `$tok = TOK_NAME`; strip the prefix if present.
                    int eq = tokName.indexOf('=');
                    if (eq >= 0) tokName = tokName.substring(eq + 1).strip();
                    sb.append("            ").append(j == 0 ? "if" : "else if")
                            .append(" (opTok.getType() == M3Lexer.").append(tokName).append(")\n");
                }
                else
                {
                    sb.append("            else\n");
                }
                sb.append("            {\n");
                sb.append("                result = ").append(foldExprToJava(a.returnExpr)).append(";\n");
                sb.append("            }\n");
            }
            if (lf.elseStep != null)
            {
                sb.append("            else\n            {\n");
                sb.append("                result = ").append(foldExprToJava(lf.elseStep)).append(";\n");
                sb.append("            }\n");
            }
        }

        sb.append("        }\n");
        sb.append("        return result;\n    }\n");
    }

    /**
     * Translate a left_fold step / let expression to Java, substituting the
     * per-iteration bindings:
     *   acc      → result    (running accumulator / LHS)
     *   rhs      → rhs       (just-built right operand, kept verbatim)
     *   $tok     → opTok     (operator Token)
     *   $rhsCtx  → rhsCtx    (RHS sub-context, useful for source-info helpers)
     *
     * Defers to the normal `exprToJava` for everything else. There is no
     * fold-local `$loc` magic — the DSL writes `buildOpSourceInfo($tok, $rhsCtx, acc)`
     * explicitly for the binary-call span.
     */
    private static String foldExprToJava(String dslExpr)
    {
        String staged = dslExpr;
        staged = staged.replaceAll("\\bacc\\b", "__FOLD_ACC__");
        staged = staged.replaceAll("\\$tok\\b", "__FOLD_TOK__");
        staged = staged.replaceAll("\\$rhsCtx\\b", "__FOLD_RHSCTX__");
        String java = exprToJava(staged, null);
        java = java.replace("__FOLD_ACC__", "result");
        java = java.replace("__FOLD_TOK__", "opTok");
        java = java.replace("__FOLD_RHSCTX__", "rhsCtx");
        return java;
    }

    private static void emitChainFold(StringBuilder sb, Rule r)
    {
        ChainFold cf = r.chainFold;
        String ctxType = "M3Parser." + (r.contextTypeOverride != null ? r.contextTypeOverride : capitalize(r.name) + "Context");
        String itemCtxType = "M3Parser." + capitalize(cf.overRule) + "Context";
        String methodName = r.methodNameOverride != null ? r.methodNameOverride : methodNameFor(r.name);
        String returnType = r.returnType != null ? r.returnType : "ValueSpecification";

        sb.append("    protected ").append(returnType).append(' ').append(methodName)
                .append("(").append(extraParamsPrefix(r)).append("final ").append(ctxType).append(" ctx)\n    {\n");
        sb.append("        return ListAdapter.adapt(ctx.").append(cf.overRule).append("())\n");
        sb.append("                .injectInto(").append(exprToJava(cf.seedExpr, null))
                .append(", (").append(returnType).append(" acc, ").append(itemCtxType).append(" it) ->\n                {\n");
        for (Alt a : cf.alts)
        {
            String cond = predicateAsJava(a.predicate);
            sb.append("                    if (").append(cond).append(")\n                    {\n");
            sb.append("                        return ").append(exprToJava(a.returnExpr, null)).append(";\n");
            sb.append("                    }\n");
        }
        String elseExpr = cf.elseStep != null ? exprToJava(cf.elseStep, null) : "acc";
        sb.append("                    return ").append(elseExpr).append(";\n");
        sb.append("                });\n    }\n");
    }

    private static void emitGrowList(StringBuilder sb, Rule r)
    {
        GrowList gl = r.growList;
        String ctxType = "M3Parser." + (r.contextTypeOverride != null ? r.contextTypeOverride : capitalize(r.name) + "Context");
        String itemCtxType = "M3Parser." + capitalize(gl.overRule) + "Context";
        String methodName = r.methodNameOverride != null ? r.methodNameOverride : methodNameFor(r.name);
        String returnType = r.returnType != null ? r.returnType : "MutableList<ValueSpecification>";
        // Element type for the lambda return — for now, hardcode ValueSpecification.
        // (Could be plumbed via the rule's `as` clause if other element types appear.)
        String elemType = "ValueSpecification";

        StringBuilder pred = new StringBuilder();
        for (int j = 0; j < gl.alts.size(); j++)
        {
            if (j > 0) pred.append(" || ");
            pred.append(predicateAsJava(gl.alts.get(j).predicate));
        }

        sb.append("    protected ").append(returnType).append(' ').append(methodName)
                .append("(").append(extraParamsPrefix(r)).append("final ").append(ctxType).append(" ctx)\n    {\n");
        sb.append("        return ListAdapter.adapt(ctx.").append(gl.overRule).append("())\n");
        sb.append("                .collectIf(\n");
        sb.append("                        (").append(itemCtxType).append(" it) -> ").append(pred).append(",\n");
        sb.append("                        (").append(itemCtxType).append(" it) ->\n                        {\n");
        for (Alt a : gl.alts)
        {
            String cond = predicateAsJava(a.predicate);
            sb.append("                            if (").append(cond).append(")\n                            {\n");
            sb.append("                                return (").append(elemType).append(") ").append(exprToJava(a.returnExpr, null)).append(";\n");
            sb.append("                            }\n");
        }
        sb.append("                            throw new RuntimeException(\"unreachable: predicate guarantees an alt matches\");\n");
        sb.append("                        });\n    }\n");
    }

    /** Detects a token whose `.text` is referenced more than once in the alt, so we cache it. */
    private static String detectRepeatedText(Alt a)
    {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Field f : a.fields)
        {
            String expr = f.expr;
            int idx = 0;
            while ((idx = expr.indexOf("$ctx.", idx)) >= 0)
            {
                int end = idx + "$ctx.".length();
                while (end < expr.length() && (Character.isLetterOrDigit(expr.charAt(end)) || expr.charAt(end) == '_'))
                {
                    end++;
                }
                String name = expr.substring(idx + "$ctx.".length(), end);
                if (expr.startsWith(".text", end))
                {
                    counts.merge(name, 1, Integer::sum);
                }
                idx = end;
            }
        }
        return counts.entrySet().stream()
                .filter(e -> e.getValue() > 1)
                .map(Map.Entry::getKey)
                .findFirst()
                .orElse(null);
    }

    private static void emitFluentChain(StringBuilder sb, List<Field> fields, String indent)
    {
        for (int i = 0; i < fields.size(); i++)
        {
            Field f = fields.get(i);
            sb.append(indent).append('.').append(setter(f.name))
                    .append('(').append(exprToJava(f.expr, null)).append(')');
            if (i < fields.size() - 1) sb.append('\n');
        }
    }

    private static String setter(String fieldName)
    {
        return ("sourceInformation".equals(fieldName) ? "_p_sourceInformation" : "_" + fieldName);
    }

    private static String predicateAsJava(String pred)
    {
        if (pred == null) return "true";
        // Multi-clause: `$.X && $.Y` (each clause independently translated and ANDed).
        if (pred.contains(" && "))
        {
            String[] parts = pred.split(" && ");
            StringBuilder sb = new StringBuilder();
            for (int k = 0; k < parts.length; k++)
            {
                if (k > 0) sb.append(" && ");
                sb.append(predicateAsJava(parts[k].strip()));
            }
            return sb.toString();
        }
        if (pred.startsWith("$it."))
        {
            // chain_fold iteration-variable form: ctx-of-current-item
            return "it." + pred.substring(4) + "() != null";
        }
        if (pred.startsWith("$ctx."))
        {
            // Multi-segment paths emit a null-safe chain so the predicate is sound
            // even when intermediate getters can return null (e.g. optional sub-rules):
            //   $ctx.X.Y → ctx.X() != null && ctx.X().Y() != null
            String[] segs = pred.substring("$ctx.".length()).split("\\.");
            StringBuilder sb = new StringBuilder();
            StringBuilder cum = new StringBuilder("ctx");
            for (int k = 0; k < segs.length; k++)
            {
                if (k > 0) sb.append(" && ");
                cum.append('.').append(segs[k]).append("()");
                sb.append(cum).append(" != null");
            }
            return sb.toString();
        }
        // Primitive call predicate (e.g. `notEmpty(typeParams)`). Match against a
        // known primitive at position 0 and require it to span the whole string.
        int end = tryMatchPrimitiveCall(pred, 0);
        if (end == pred.length())
        {
            return exprToJava(pred, null);
        }
        throw new RuntimeException("unsupported predicate: " + pred);
    }

    /**
     * Translate a DSL expression to Java. The translation is purely textual:
     *
     *   $loc                       → buildSourceInfo(ctx)
     *   $.X.text                   → ctx.X().getText()  (or __t if cached)
     *   $.X                        → ctx.X()
     *   primitiveType("X")         → buildPrimitiveGenericType("X")
     *   dateLiteralType(arg)       → arg.contains("T") ? buildPrimitiveGenericType("DateTime") : buildPrimitiveGenericType("StrictDate")
     *   stripQuotes(arg)           → arg.substring(1, arg.length() - 1)
     *   stripPercent(arg)          → arg.startsWith("%") ? arg.substring(1) : arg
     *   parseLong(arg)             → Long.parseLong(arg)
     *   parseDouble(arg)           → Double.parseDouble(arg)
     *   parseBoolean(arg)          → Boolean.parseBoolean(arg)
     */
    private static String exprToJava(String dslExpr, String cachedTextToken)
    {
        String e = dslExpr.strip();

        // ifPresent($ctx.X, THEN, ELSE) — ctx.X() != null ? THEN : ELSE
        // ifPresent($ctx.X, EXPR)        — 2-arg "skip" sentinel; only legal as a top-level
        //                               newImpl field of a rule's return or a let-binding,
        //                               where the decomposer turns it into a conditional
        //                               setter. Anywhere else it's a parse error.
        if (e.startsWith("ifPresent(") && e.endsWith(")"))
        {
            String inner = e.substring("ifPresent(".length(), e.length() - 1);
            List<String> parts = splitTopLevelCommas(inner);
            if (parts.size() == 3)
            {
                String pred = predicateAsJava(parts.get(0).strip());
                String thenE = exprToJava(parts.get(1), cachedTextToken);
                String elseE = exprToJava(parts.get(2), cachedTextToken);
                return "(" + pred + " ? " + thenE + " : " + elseE + ")";
            }
            if (parts.size() == 2)
            {
                throw new RuntimeException("2-arg ifPresent is only valid as a top-level newImpl field "
                        + "(rule-level `return` or `let`); found in expression position: " + e);
            }
        }

        // listOf(a, b, c) — Lists.mutable.with(a, b, c); empty form → Lists.mutable.empty()
        if (e.startsWith("listOf(") && e.endsWith(")"))
        {
            String inner = e.substring("listOf(".length(), e.length() - 1);
            if (inner.strip().isEmpty()) return "Lists.mutable.empty()";
            List<String> parts = splitTopLevelCommas(inner);
            StringBuilder out = new StringBuilder("Lists.mutable.with(");
            for (int i = 0; i < parts.size(); i++)
            {
                if (i > 0) out.append(", ");
                out.append(exprToJava(parts.get(i), cachedTextToken));
            }
            out.append(')');
            return out.toString();
        }

        // prepended(item, list) — Lists.mutable.with(item).withAll(list); used for
        // receiver-prepended parameter lists (e.g. visitor methods that take a
        // receiver and a list of other args).
        if (e.startsWith("prepended(") && e.endsWith(")"))
        {
            String inner = e.substring("prepended(".length(), e.length() - 1);
            List<String> parts = splitTopLevelCommas(inner);
            if (parts.size() == 2)
            {
                String item = exprToJava(parts.get(0), cachedTextToken);
                String list = exprToJava(parts.get(1), cachedTextToken);
                return "Lists.mutable.<ValueSpecification>with(" + item + ").withAll(" + list + ")";
            }
        }

        // filterMap($.X, "needle", fn) — adapt+select(contains)+collect.
        // filterMapNot($.X, "needle", fn) — same but reject(contains).
        if ((e.startsWith("filterMap(") || e.startsWith("filterMapNot(")) && e.endsWith(")"))
        {
            boolean negate = e.startsWith("filterMapNot(");
            String prefix = negate ? "filterMapNot(" : "filterMap(";
            String inner = e.substring(prefix.length(), e.length() - 1);
            List<String> parts = splitTopLevelCommas(inner);
            if (parts.size() == 3)
            {
                String listJava = exprToJava(parts.get(0), cachedTextToken);
                String needle = parts.get(1).strip();
                String fn = parts.get(2).strip();
                String methodRef = fn.startsWith("build") || fn.startsWith("visit") || fn.startsWith("parse") ? fn : methodNameFor(fn);
                String op = negate ? "reject" : "select";
                return "ListAdapter.adapt(" + listJava + ")." + op + "(__c -> __c.getText().contains(" + needle + ")).collect(this::" + methodRef + ")";
            }
        }

        // mapList($.X, fn) — ListAdapter.adapt(ctx.X()).collect(this::fn)
        if (e.startsWith("mapList(") && e.endsWith(")"))
        {
            String inner = e.substring("mapList(".length(), e.length() - 1);
            List<String> parts = splitTopLevelCommas(inner);
            if (parts.size() == 2)
            {
                String listJava = exprToJava(parts.get(0), cachedTextToken);
                String fn = parts.get(1).strip();
                // Allow either a bare method name (mapped via methodNameFor) or
                // a qualified-name like buildX which we leave verbatim.
                // Verbatim if the name looks like a Java helper method (build*/visit*/parse*);
                // otherwise treat as a bare grammar rule name and prefix with `build`.
                String methodRef = fn.startsWith("build") || fn.startsWith("visit") || fn.startsWith("parse") ? fn : methodNameFor(fn);
                return "ListAdapter.adapt(" + listJava + ").collect(this::" + methodRef + ")";
            }
        }

        // notEmpty(X) — !X.isEmpty() — emit-side check for a non-empty collection.
        if (e.startsWith("notEmpty(") && e.endsWith(")"))
        {
            String inner = exprToJava(e.substring("notEmpty(".length(), e.length() - 1), cachedTextToken);
            return "!" + inner + ".isEmpty()";
        }

        // simpleNameOf(s) — last `::`-suffix of a path, or the whole string if unqualified.
        if (e.startsWith("simpleNameOf(") && e.endsWith(")"))
        {
            String inner = exprToJava(e.substring("simpleNameOf(".length(), e.length() - 1), cachedTextToken);
            return "(" + inner + ".contains(\"::\") ? " + inner + ".substring(" + inner + ".lastIndexOf(\"::\") + 2) : " + inner + ")";
        }

        // packagePrefix(s) — substring of `s` before the last `::` (undefined if no `::` present;
        // pair with hasPackagePrefix(s) as the guard).
        if (e.startsWith("packagePrefix(") && e.endsWith(")"))
        {
            String inner = exprToJava(e.substring("packagePrefix(".length(), e.length() - 1), cachedTextToken);
            return inner + ".substring(0, " + inner + ".lastIndexOf(\"::\"))";
        }

        // hasPackagePrefix(s) — boolean: true iff `s` contains "::". Used as a predicate.
        if (e.startsWith("hasPackagePrefix(") && e.endsWith(")"))
        {
            String inner = exprToJava(e.substring("hasPackagePrefix(".length(), e.length() - 1), cachedTextToken);
            return inner + ".contains(\"::\")";
        }

        // match(discToken, TOK1, v1, TOK2, v2, …) — pattern-match on a Token's type.
        // Emits a ternary chain comparing `disc.getType()` against each `M3Lexer.TOKi`.
        // The LAST value is the fallback (no comparison emitted), matching the previous
        // op_by_token semantics — the listed token names exhaustively cover the grammar's
        // operator positions, and the final entry's name is documentation-only.
        if (e.startsWith("match(") && e.endsWith(")"))
        {
            String inner = e.substring("match(".length(), e.length() - 1);
            List<String> parts = splitTopLevelCommas(inner);
            if (parts.size() >= 3 && (parts.size() - 1) % 2 == 0)
            {
                String disc = exprToJava(parts.get(0).strip(), cachedTextToken);
                StringBuilder out = new StringBuilder("(");
                int nPairs = (parts.size() - 1) / 2;
                for (int j = 0; j < nPairs - 1; j++)
                {
                    String tok = parts.get(1 + 2 * j).strip();
                    String val = exprToJava(parts.get(2 + 2 * j).strip(), cachedTextToken);
                    out.append(disc).append(".getType() == M3Lexer.").append(tok)
                            .append(" ? ").append(val).append(" : ");
                }
                String lastVal = exprToJava(parts.get(parts.size() - 1).strip(), cachedTextToken);
                out.append(lastVal).append(")");
                return out.toString();
            }
            throw new RuntimeException("match needs discriminator + N (token, value) pairs: " + e);
        }

        // beforeFirstDot(s) — substring of `s` before the first ".", or `s` itself when no dot.
        // Used to recover the type name from a path-separator-prefixed reference like `Type.all()`.
        if (e.startsWith("beforeFirstDot(") && e.endsWith(")"))
        {
            String inner = exprToJava(e.substring("beforeFirstDot(".length(), e.length() - 1), cachedTextToken);
            return inner + ".split(\"\\\\.\")[0]";
        }

        // firstOf($ctx.X) — first element of a list-returning sub-rule accessor.
        if (e.startsWith("firstOf($ctx.") && e.endsWith(")"))
        {
            String tok = e.substring("firstOf($ctx.".length(), e.length() - 1);
            return "ctx." + tok + "().get(0)";
        }

        // anyHas($ctx.X, name) — boolean: any item in $ctx.X has a non-null `name()` child.
        if (e.startsWith("anyHas($ctx.") && e.endsWith(")"))
        {
            String inner = e.substring("anyHas($ctx.".length(), e.length() - 1);
            int comma = inner.indexOf(',');
            String tok = inner.substring(0, comma).strip();
            String name = inner.substring(comma + 1).strip();
            return "ListAdapter.adapt(ctx." + tok + "()).anySatisfy(__c -> __c." + name + "() != null)";
        }

        // anyHasAny($ctx.X, name1, name2) — boolean: any item in $ctx.X has non-null `name1()` OR `name2()`.
        if (e.startsWith("anyHasAny($ctx.") && e.endsWith(")"))
        {
            String inner = e.substring("anyHasAny($ctx.".length(), e.length() - 1);
            List<String> parts = splitTopLevelCommas(inner);
            String tok = parts.get(0).strip();
            String name1 = parts.get(1).strip();
            String name2 = parts.get(2).strip();
            return "ListAdapter.adapt(ctx." + tok + "()).anySatisfy(__c -> __c." + name1 + "() != null || __c." + name2 + "() != null)";
        }

        // selectMapHasAny($ctx.X, name1, name2, fn) — filter $ctx.X to items where name1 OR name2 is
        // non-null, then map each via build<fn>.
        if (e.startsWith("selectMapHasAny($ctx.") && e.endsWith(")"))
        {
            String inner = e.substring("selectMapHasAny($ctx.".length(), e.length() - 1);
            List<String> parts = splitTopLevelCommas(inner);
            String tok = parts.get(0).strip();
            String name1 = parts.get(1).strip();
            String name2 = parts.get(2).strip();
            String fn = parts.get(3).strip();
            String methodRef = fn.startsWith("build") ? fn : ("build" + capitalize(fn));
            return "ListAdapter.adapt(ctx." + tok + "()).select(__c -> __c." + name1 + "() != null || __c." + name2 + "() != null).collect(this::" + methodRef + ")";
        }

        // hasAny($ctx, name1, name2) — boolean: the current context has non-null name1() OR name2().
        if (e.startsWith("hasAny($ctx, ") && e.endsWith(")"))
        {
            String inner = e.substring("hasAny($ctx, ".length(), e.length() - 1);
            List<String> parts = splitTopLevelCommas(inner);
            String name1 = parts.get(0).strip();
            String name2 = parts.get(1).strip();
            return "(ctx." + name1 + "() != null || ctx." + name2 + "() != null)";
        }

        // count($ctx.X) — ctx.X().size() — element count of a list-returning child rule.
        if (e.startsWith("count($ctx.") && e.endsWith(")"))
        {
            String tok = e.substring("count($ctx.".length(), e.length() - 1);
            return "ctx." + tok + "().size()";
        }

        // multBounds(lo, hi) — UserDefinedAdHocMultiplicity with the given numeric bounds (inline).
        if (e.startsWith("multBounds(") && e.endsWith(")"))
        {
            String inner = e.substring("multBounds(".length(), e.length() - 1);
            List<String> parts = splitTopLevelCommas(inner);
            if (parts.size() == 2)
            {
                String lo = exprToJava(parts.get(0), cachedTextToken);
                String hi = exprToJava(parts.get(1), cachedTextToken);
                return "new UserDefinedAdHocMultiplicityImpl()._lowerBound(new MultiplicityValueImpl()._value((long) (" + lo + ")))._upperBound(new MultiplicityValueImpl()._value((long) (" + hi + ")))";
            }
        }

        // joinTextWith($.X, "sep") — concatenate getText() of every $.X item with separator.
        if (e.startsWith("joinTextWith(") && e.endsWith(")"))
        {
            String inner = e.substring("joinTextWith(".length(), e.length() - 1);
            List<String> parts = splitTopLevelCommas(inner);
            if (parts.size() == 2)
            {
                String listJava = exprToJava(parts.get(0), cachedTextToken);
                String sep = parts.get(1).strip();
                return "ListAdapter.adapt(" + listJava + ").collect(__n -> __n.getText()).makeString(" + sep + ")";
            }
        }

        // joinStripped($ctx.X) — concatenate the quote-stripped getText() of every $ctx.X token.
        // Used for grammar like `STRING (PLUS STRING)*` where multiple quoted strings should
        // be folded into one logical value.
        if (e.startsWith("joinStripped(") && e.endsWith(")"))
        {
            String inner = e.substring("joinStripped(".length(), e.length() - 1).strip();
            if (!inner.startsWith("$ctx.")) throw new RuntimeException("joinStripped expects $ctx.X: " + inner);
            String tok = inner.substring("$ctx.".length());
            return "ListAdapter.adapt(ctx." + tok + "()).collect(__n -> { String __raw = __n.getText();"
                    + " return __raw.substring(1, __raw.length() - 1); }).makeString(\"\")";
        }

        // newImpl(TypeName, fieldA=expr, fieldB=expr, ...) — nested object construction.
        // Generates: new TypeNameImpl()._fieldA(expr)._fieldB(expr)... (using `setter()` for
        // the sourceInformation→_p_sourceInformation special case).
        if (e.startsWith("newImpl(") && e.endsWith(")"))
        {
            String inner = e.substring("newImpl(".length(), e.length() - 1);
            List<String> parts = splitTopLevelCommas(inner);
            if (!parts.isEmpty())
            {
                String typeName = parts.get(0).strip();
                StringBuilder out = new StringBuilder("new ").append(typeName).append("Impl()");
                for (int j = 1; j < parts.size(); j++)
                {
                    String kv = parts.get(j);
                    int eqIdx = kv.indexOf('=');
                    if (eqIdx < 0) throw new RuntimeException("newImpl arg lacks '=': " + kv);
                    String key = kv.substring(0, eqIdx).strip();
                    String valExpr = kv.substring(eqIdx + 1).strip();
                    out.append('.').append(setter(key)).append('(')
                            .append(exprToJava(valExpr, cachedTextToken)).append(')');
                }
                return out.toString();
            }
        }

        // Recognized intrinsics (one-arg shape).
        String[][] simpleCalls = {
                {"parseLong(", "Long.parseLong("},
                {"parseDouble(", "Double.parseDouble("},
                {"parseBoolean(", "Boolean.parseBoolean("},
        };
        for (String[] m : simpleCalls)
        {
            if (e.startsWith(m[0]) && e.endsWith(")"))
            {
                String inner = e.substring(m[0].length(), e.length() - 1);
                return m[1] + exprToJava(inner, cachedTextToken) + ")";
            }
        }
        // primitiveType("X") — wrap a Type_Pointer in a UserDefinedGenericType inline.
        if (e.startsWith("primitiveType(") && e.endsWith(")"))
        {
            String inner = e.substring("primitiveType(".length(), e.length() - 1);
            String arg = exprToJava(inner, cachedTextToken);
            return "new UserDefinedGenericTypeImpl()._type(new Type_PointerImpl()._value(" + arg + "))";
        }

        if (e.startsWith("stripQuotes(") && e.endsWith(")"))
        {
            String inner = exprToJava(e.substring("stripQuotes(".length(), e.length() - 1), cachedTextToken);
            return inner + ".substring(1, " + inner + ".length() - 1)";
        }
        if (e.startsWith("stripPercent(") && e.endsWith(")"))
        {
            String inner = exprToJava(e.substring("stripPercent(".length(), e.length() - 1), cachedTextToken);
            return inner + ".startsWith(\"%\") ? " + inner + ".substring(1) : " + inner;
        }
        // stripIfQuoted(s) — strip single-quotes only if the string is wrapped in them.
        // Used for column names which may be either bare identifiers or quoted strings.
        if (e.startsWith("stripIfQuoted(") && e.endsWith(")"))
        {
            String inner = exprToJava(e.substring("stripIfQuoted(".length(), e.length() - 1), cachedTextToken);
            return "(" + inner + ".startsWith(\"'\") && " + inner + ".endsWith(\"'\") ? "
                    + inner + ".substring(1, " + inner + ".length() - 1) : " + inner + ")";
        }

        // stripParens(s) — drop leading and trailing single char: "(none)" → "none".
        if (e.startsWith("stripParens(") && e.endsWith(")"))
        {
            String inner = exprToJava(e.substring("stripParens(".length(), e.length() - 1), cachedTextToken);
            return inner + ".substring(1, " + inner + ".length() - 1)";
        }
        // capitalize(s) — uppercase the first char: "none" → "None".
        if (e.startsWith("capitalize(") && e.endsWith(")"))
        {
            String inner = exprToJava(e.substring("capitalize(".length(), e.length() - 1), cachedTextToken);
            return inner + ".substring(0, 1).toUpperCase() + " + inner + ".substring(1)";
        }
        // enumPointer("a::b::Enum", expr) — inline Enum_PointerImpl construction.
        if (e.startsWith("enumPointer(") && e.endsWith(")"))
        {
            String inner = e.substring("enumPointer(".length(), e.length() - 1);
            List<String> parts = splitTopLevelCommas(inner);
            if (parts.size() == 2)
            {
                String qn = parts.get(0).strip();
                String val = exprToJava(parts.get(1), cachedTextToken);
                return "new Enum_PointerImpl()._value(" + qn + ")._extraPointerValues(Lists.mutable.with(new PointerValueImpl()._value(" + val + ")))";
            }
        }
        if (e.startsWith("dateLiteralType(") && e.endsWith(")"))
        {
            String inner = exprToJava(e.substring("dateLiteralType(".length(), e.length() - 1), cachedTextToken);
            String dateTimeType = "new UserDefinedGenericTypeImpl()._type(new Type_PointerImpl()._value(\"DateTime\"))";
            String strictDateType = "new UserDefinedGenericTypeImpl()._type(new Type_PointerImpl()._value(\"StrictDate\"))";
            return inner + ".contains(\"T\") ? " + dateTimeType + " : " + strictDateType;
        }

        if (e.equals("$ctx")) return "ctx";
        if (e.equals("acc")) return "acc";
        if (e.startsWith("$it.") && isSimpleTokenRef(e))
        {
            // Multi-segment $it path support: $it.X.Y.text → it.X().Y().getText()
            String inner = e.substring(4);
            boolean isText = inner.endsWith(".text") && inner.length() > ".text".length();
            String toWalk = isText ? inner.substring(0, inner.length() - ".text".length()) : inner;
            String[] segs = toWalk.split("\\.");
            StringBuilder sb = new StringBuilder("it");
            for (String seg : segs)
            {
                sb.append('.').append(seg).append("()");
            }
            if (isText) sb.append(".getText()");
            return sb.toString();
        }
        if (e.startsWith("$ctx.") && e.endsWith(".text") && isSimpleTokenRef(e))
        {
            // Cache-aware $ctx.X.text shortcut (single-segment paths only).
            String inner = e.substring("$ctx.".length(), e.length() - ".text".length());
            if (!inner.contains(".") && cachedTextToken != null && cachedTextToken.equals(inner))
            {
                return "__t";
            }
            return pathToJava(inner, true);
        }
        if (e.startsWith("$ctx.") && isSimpleTokenRef(e)) return pathToJava(e.substring("$ctx.".length()), false);
        // Pass-through (e.g. a helper invocation like `buildGenericType($ctx.type)` or
        // `parseMultiplicity($ctx.multiplicity.text)`). Recursively substitute embedded
        // $ctx.X.text and $ctx.X references so the resulting expression is valid Java.
        return substituteContextRefs(e, cachedTextToken);
    }

    /** True if the expression is a single $.X[.Y[.Z]][.text] reference (no parens, no extra tokens). */
    private static boolean isSimpleTokenRef(String e)
    {
        for (int i = 0; i < e.length(); i++)
        {
            char c = e.charAt(i);
            if (c == '(' || c == ' ') return false;
        }
        return true;
    }

    /**
     * Translate a dotted path (e.g. {@code X.Y.Z}) to a Java method chain
     * {@code ctx.X().Y().Z()}. When {@code lastIsText} is true, the final
     * segment is rendered as {@code .getText()} instead of {@code .text()}.
     * Used for both bare path refs ({@code $.X.Y}) and source-info refs
     * ({@code $loc($.X.Y)}).
     */
    private static String pathToJava(String dottedPath, boolean lastIsText)
    {
        String[] segs = dottedPath.split("\\.");
        StringBuilder sb = new StringBuilder("ctx");
        for (int k = 0; k < segs.length; k++)
        {
            sb.append('.').append(segs[k]).append("()");
        }
        if (lastIsText) sb.append(".getText()");
        return sb.toString();
    }

    /**
     * Replace every {@code $.X.text} with {@code ctx.X().getText()} (or {@code __t}
     * if cached), every remaining {@code $.X} with {@code ctx.X()}, and substitute
     * known DSL-primitive names with their Java equivalents (so e.g. a nested
     * {@code -parseLong(...)} produces {@code -Long.parseLong(...)}). Used for
     * pass-through expressions where the primitive call isn't at the top.
     */
    /** DSL primitive names that take parenthesized args; used to detect nested calls
     * inside otherwise-pass-through expressions (e.g. `buildAllFunction(..., mapList(...))`). */
    private static final String[] PRIMITIVE_NAMES = {
            "mapList", "listOf", "ifPresent", "newImpl", "prepended",
            "joinTextWith", "joinStripped", "parseLong", "parseDouble", "parseBoolean",
            "primitiveType", "stripQuotes", "stripPercent", "stripParens", "capitalize",
            "enumPointer", "count", "multBounds", "dateLiteralType", "stripIfQuoted",
            "notEmpty", "simpleNameOf", "filterMap", "filterMapNot",
            "packagePrefix", "hasPackagePrefix", "beforeFirstDot",
            "firstOf", "anyHas", "anyHasAny", "selectMapHasAny", "hasAny",
            "match"
    };

    /**
     * If position {@code start} in {@code e} begins a known primitive call (e.g.
     * `mapList(...)`), return the index just past its closing `)`; otherwise return
     * {@code start} unchanged. The caller can then slice {@code e[start..matched]}
     * and recurse with {@link #exprToJava} to expand the primitive.
     */
    private static int tryMatchPrimitiveCall(String e, int start)
    {
        for (String p : PRIMITIVE_NAMES)
        {
            if (e.regionMatches(start, p, 0, p.length())
                    && start + p.length() < e.length()
                    && e.charAt(start + p.length()) == '(')
            {
                // Ensure the char before `start` is not part of a longer identifier
                // (so we don't match `buildMapList(` as `mapList(`).
                if (start > 0 && (Character.isLetterOrDigit(e.charAt(start - 1)) || e.charAt(start - 1) == '_'))
                {
                    continue;
                }
                int j = start + p.length() + 1;  // past `(`
                int depth = 1;
                while (j < e.length() && depth > 0)
                {
                    char c = e.charAt(j);
                    if (c == '(') depth++;
                    else if (c == ')') depth--;
                    j++;
                }
                if (depth == 0) return j;
            }
        }
        return start;
    }

    private static String substituteContextRefs(String e, String cachedTextToken)
    {
        StringBuilder out = new StringBuilder(e.length());
        int i = 0;
        while (i < e.length())
        {
            // Nested primitive call: expand via exprToJava and skip past it.
            int matched = tryMatchPrimitiveCall(e, i);
            if (matched > i)
            {
                out.append(exprToJava(e.substring(i, matched), cachedTextToken));
                i = matched;
                continue;
            }
            // $ctx (bare) → ctx — the rule's parser context.
            // Must check BEFORE $ctx.X path so that $ctx alone isn't consumed as a path prefix.
            if (i + 3 < e.length() + 1 && e.regionMatches(i, "$ctx", 0, 4)
                    && (i + 4 == e.length() || (e.charAt(i + 4) != '.' && !Character.isLetterOrDigit(e.charAt(i + 4)))))
            {
                out.append("ctx");
                i += 4;
                continue;
            }
            // $it.X[.Y[.Z]][.text] (chain_fold/grow_list iteration-variable form). Walks each
            // segment; trailing `.text` becomes `.getText()`.
            if (i + 3 < e.length() && e.charAt(i) == '$' && e.charAt(i + 1) == 'i'
                    && e.charAt(i + 2) == 't' && e.charAt(i + 3) == '.')
            {
                int j = i + 4;
                java.util.List<String> segs = new ArrayList<>();
                while (j < e.length() && (Character.isLetter(e.charAt(j)) || e.charAt(j) == '_'))
                {
                    int segStart = j;
                    while (j < e.length() && (Character.isLetterOrDigit(e.charAt(j)) || e.charAt(j) == '_'))
                    {
                        j++;
                    }
                    segs.add(e.substring(segStart, j));
                    if (j < e.length() && e.charAt(j) == '.'
                            && j + 1 < e.length() && (Character.isLetter(e.charAt(j + 1)) || e.charAt(j + 1) == '_'))
                    {
                        j++;
                    }
                    else
                    {
                        break;
                    }
                }
                boolean endsInText = segs.size() > 1 && "text".equals(segs.get(segs.size() - 1));
                out.append("it");
                if (endsInText)
                {
                    for (int k = 0; k < segs.size() - 1; k++)
                    {
                        out.append('.').append(segs.get(k)).append("()");
                    }
                    out.append(".getText()");
                }
                else
                {
                    for (String seg : segs)
                    {
                        out.append('.').append(seg).append("()");
                    }
                }
                i = j;
            }
            else if (i + 4 < e.length() && e.regionMatches(i, "$ctx.", 0, 5))
            {
                // Walk a $ctx.X[.Y[.Z]][.text] path. Each `.X` segment becomes `.X()` in
                // Java; a trailing `.text` becomes `.getText()`. Single-segment .text
                // honors the cachedTextToken optimization.
                // If the path is followed by `(` (e.g. `$ctx.getText()`), treat it as a
                // Java method call and leave the parens to the source — don't add `()`
                // after the final segment.
                int j = i + "$ctx.".length();
                java.util.List<String> segs = new ArrayList<>();
                while (j < e.length() && (Character.isLetter(e.charAt(j)) || e.charAt(j) == '_'))
                {
                    int segStart = j;
                    while (j < e.length() && (Character.isLetterOrDigit(e.charAt(j)) || e.charAt(j) == '_'))
                    {
                        j++;
                    }
                    segs.add(e.substring(segStart, j));
                    if (j < e.length() && e.charAt(j) == '.'
                            && j + 1 < e.length() && (Character.isLetter(e.charAt(j + 1)) || e.charAt(j + 1) == '_'))
                    {
                        j++;  // consume `.`, loop for next segment
                    }
                    else
                    {
                        break;
                    }
                }
                boolean trailingCall = j < e.length() && e.charAt(j) == '(';
                boolean endsInText = !trailingCall && segs.size() > 1 && "text".equals(segs.get(segs.size() - 1));
                if (endsInText && segs.size() == 2 && cachedTextToken != null && cachedTextToken.equals(segs.get(0)))
                {
                    out.append("__t");
                }
                else if (endsInText)
                {
                    out.append("ctx");
                    for (int k = 0; k < segs.size() - 1; k++)
                    {
                        out.append('.').append(segs.get(k)).append("()");
                    }
                    out.append(".getText()");
                }
                else if (trailingCall)
                {
                    // Java method-call form: emit `ctx.X().Y.method` and let the source's
                    // `(` continue the call. All-but-last segments are path-style; the
                    // final segment is the Java method name (no trailing `()`).
                    out.append("ctx");
                    for (int k = 0; k < segs.size() - 1; k++)
                    {
                        out.append('.').append(segs.get(k)).append("()");
                    }
                    out.append('.').append(segs.get(segs.size() - 1));
                }
                else
                {
                    out.append("ctx");
                    for (String seg : segs)
                    {
                        out.append('.').append(seg).append("()");
                    }
                }
                i = j;
            }
            else
            {
                out.append(e.charAt(i++));
            }
        }
        // Primitive calls are now expanded recursively via tryMatchPrimitiveCall +
        // exprToJava within the walker above, so no text-level fallback substitution
        // is needed (and would double-rewrite e.g. `Long.Long.parseLong(...)`).
        return out.toString();
    }

    private static String capitalize(String s)
    {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    /**
     * Emit the rule's `let`-bindings (TYPE NAME = EXPR) followed by `set` rebinds
     * (NAME = EXPR) as Java locals at the top of the method body. Both are emitted
     * in declared order. Non-final so `set` can rebind safely.
     */
    private static void emitLetBindings(StringBuilder sb, Rule r)
    {
        for (String[] l : r.lets)
        {
            String type = l[0];
            String name = l[1];
            String expr = l[2];
            if (!emitConditionalNewImplLet(sb, type, name, expr))
            {
                sb.append("        ").append(type).append(' ').append(name)
                        .append(" = ").append(exprToJava(expr, null)).append(";\n");
            }
        }
        for (String[] s : r.sets)
        {
            sb.append("        ").append(s[0]).append(" = ")
                    .append(exprToJava(s[1], null)).append(";\n");
        }
    }

    /**
     * If the let value is `newImpl(T, k=v, k=ifPresent(p, e), ...)` and has at least one
     * 2-arg `ifPresent` field, emit it as a local-var multi-statement block:
     *   T NAME = new TImpl()._k1(v1)._k2(v2);
     *   if (p) NAME._kCond(e);
     * Returns true if emitted, false to let the caller fall back to the default
     * single-expression form. Mirrors the rule-level decompose-newImpl path.
     */
    private static boolean emitConditionalNewImplLet(StringBuilder sb, String type, String name, String expr)
    {
        String inner = expr.strip();
        if (!inner.startsWith("newImpl(") || !inner.endsWith(")")) return false;
        if (tryMatchPrimitiveCall(inner, 0) != inner.length()) return false;
        String args = inner.substring("newImpl(".length(), inner.length() - 1);
        List<String> parts = splitTopLevelCommas(args);
        if (parts.size() < 2) return false;
        List<Field> fields = new ArrayList<>();
        boolean anyConditional = false;
        for (int j = 1; j < parts.size(); j++)
        {
            Field f = parseUnifiedField(parts.get(j).strip());
            if (f == null) return false;
            if (f.predicate != null) anyConditional = true;
            fields.add(f);
        }
        if (!anyConditional) return false;
        String implType = parts.get(0).strip() + "Impl";
        sb.append("        ").append(type).append(' ').append(name)
                .append(" = new ").append(implType).append("()");
        for (Field f : fields)
        {
            if (f.predicate == null)
            {
                sb.append("\n                .").append(setter(f.name))
                        .append('(').append(exprToJava(f.expr, null)).append(')');
            }
        }
        sb.append(";\n");
        for (Field f : fields)
        {
            if (f.predicate != null)
            {
                sb.append("        if (").append(predicateAsJava(f.predicate))
                        .append(") ").append(name).append('.').append(setter(f.name))
                        .append('(').append(exprToJava(f.expr, null)).append(");\n");
            }
        }
        return true;
    }

    /**
     * Format the extra-parameter prefix for a rule's generated method signature.
     * Each `param TYPE name` directive becomes `final TYPE name, ` prepended to
     * the `final CtxType ctx` parameter. Returns "" when no extras are declared.
     */
    private static String extraParamsPrefix(Rule r)
    {
        if (r.extraParams.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (String[] p : r.extraParams)
        {
            sb.append("final ").append(p[0]).append(' ').append(p[1]).append(", ");
        }
        return sb.toString();
    }

    /**
     * Generated method name from a grammar rule name. Most rules get a `build`
     * prefix (so that they don't collide with ANTLR's M3ParserVisitor.visit*
     * methods whose return type is fixed to Object). Rules whose name already
     * begins with `build` are emitted verbatim (e.g. {@code buildMilestoningVariableExpression}).
     */
    private static String methodNameFor(String ruleName)
    {
        if (ruleName.startsWith("build"))
        {
            return ruleName;
        }
        return "build" + capitalize(ruleName);
    }

    /**
     * Split a comma-separated expression list into its top-level parts, ignoring
     * commas nested inside parentheses. Used for parsing multi-arg DSL primitives
     * like {@code ifPresent($.X, THEN, ELSE)}.
     */
    private static List<String> splitTopLevelCommas(String s)
    {
        List<String> out = new ArrayList<>();
        int depth = 0;
        int start = 0;
        for (int i = 0; i < s.length(); i++)
        {
            char c = s.charAt(i);
            if (c == '(') depth++;
            else if (c == ')') depth--;
            else if (c == ',' && depth == 0)
            {
                out.add(s.substring(start, i));
                start = i + 1;
            }
        }
        out.add(s.substring(start));
        return out;
    }

    private static String unquote(String s)
    {
        s = s.strip();
        if (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\""))
        {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }
}
