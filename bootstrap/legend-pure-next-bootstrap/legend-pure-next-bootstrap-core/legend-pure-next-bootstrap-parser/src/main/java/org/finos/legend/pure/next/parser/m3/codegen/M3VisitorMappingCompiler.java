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

package org.finos.legend.pure.next.parser.m3.codegen;

import org.antlr.v4.runtime.BaseErrorListener;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Recognizer;
import org.antlr.v4.runtime.misc.Interval;
import org.finos.legend.pure.next.parser.m3.codegen.mapping.VisitorMappingLexer;
import org.finos.legend.pure.next.parser.m3.codegen.mapping.VisitorMappingParser;

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
public final class M3VisitorMappingCompiler
{
    public static void main(String[] args) throws IOException
    {
        if (args.length < 2)
        {
            System.err.println("Usage: M3VisitorMappingCompiler <dsl-file> <output-java-file>");
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
        sb.append("// AUTO-GENERATED from ").append(dslFileName).append(" by M3VisitorMappingCompiler — DO NOT EDIT\n");
        sb.append("// Concrete parser: extends M3ParserBaseVisitor directly. Contains the elements\n");
        sb.append("// accumulator, parser entry points, build<RuleName> methods, and @Override\n");
        sb.append("// visit wrappers for topLevel rules. Fully self-contained — no hand-written\n");
        sb.append("// parent class. To port to another language, port this generator + the DSL.\n");
        sb.append("package org.finos.legend.pure.next.parser.m3;\n\n");
        sb.append("import meta.pure.protocol.grammar.Enum_PointerImpl;\n");
        sb.append("import meta.pure.protocol.grammar.Package_PointerImpl;\n");
        sb.append("import meta.pure.protocol.grammar.PackageableElement;\n");
        sb.append("import meta.pure.protocol.grammar.SourceInformation;\n");
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
        sb.append("import org.eclipse.collections.impl.list.mutable.ListAdapter;\n\n");
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
        sb.append("    /** Operator token between the i-th and (i-1)-th operand in a left-fold context. */\n");
        sb.append("    protected Token operatorTokenAt(final ParserRuleContext ctx, final int operandIndex)\n");
        sb.append("    {\n");
        sb.append("        return ((org.antlr.v4.runtime.tree.TerminalNode) ctx.getChild(2 * operandIndex - 1)).getSymbol();\n");
        sb.append("    }\n\n");
    }

    /**
     * Emit an @Override `visit<RuleName>` wrapper that calls the rule's build method,
     * appends to `elements`, and returns the built value as Object (ANTLR convention).
     */
    private static void emitTopLevelVisitWrapper(StringBuilder sb, Rule r)
    {
        String ctxType = qualifyCtxType(r.contextTypeOverride != null ? r.contextTypeOverride : capitalize(r.name) + "Context");
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
    // The DSL grammar lives in src/main/antlr4/.../dsl/M3Dsl.g4. This `parse`
    // walks the ANTLR parse tree and populates the existing Rule / Alt / Field
    // IR — the emitter below is unchanged, so expressions are stored as their
    // recovered source text (via the token stream's interval text) and the
    // existing exprToJava continues to translate them.

    private static List<Rule> parse(String source)
    {
        VisitorMappingLexer lexer = new VisitorMappingLexer(CharStreams.fromString(source));
        CommonTokenStream tokens = new CommonTokenStream(lexer);
        VisitorMappingParser parser = new VisitorMappingParser(tokens);
        parser.removeErrorListeners();
        parser.addErrorListener(new BaseErrorListener()
        {
            @Override
            public void syntaxError(Recognizer<?, ?> recognizer, Object offendingSymbol,
                                    int line, int charPositionInLine, String msg, RecognitionException e)
            {
                throw new RuntimeException("M3 DSL syntax error at " + line + ":" + charPositionInLine + " — " + msg);
            }
        });
        VisitorMappingParser.DslContext tree = parser.dsl();
        List<Rule> rules = new ArrayList<>();
        for (VisitorMappingParser.DeclarationContext decl : tree.declaration())
        {
            Rule r = new Rule();
            if (decl.ruleDecl() != null)
            {
                buildRule(r, decl.ruleDecl(), tokens);
            }
            else
            {
                buildHelper(r, decl.helperDecl(), tokens);
            }
            inferUnifiedReturnType(r);
            rules.add(r);
        }
        return rules;
    }

    /** Recover the original source text (with whitespace) for a parse-tree node. */
    private static String srcText(ParserRuleContext ctx, CommonTokenStream tokens)
    {
        if (ctx == null) return null;
        Interval range = new Interval(ctx.getStart().getStartIndex(), ctx.getStop().getStopIndex());
        return ctx.getStart().getInputStream().getText(range);
    }

    private static void buildRule(Rule r, VisitorMappingParser.RuleDeclContext ctx, CommonTokenStream tokens)
    {
        r.name = ctx.name.getText();
        if (ctx.returnType != null) r.returnType = srcText(ctx.returnType, tokens);
        for (VisitorMappingParser.BodyStmtContext s : ctx.bodyStmt())
        {
            applyBodyStmt(r, s, tokens);
        }
    }

    private static void buildHelper(Rule r, VisitorMappingParser.HelperDeclContext ctx, CommonTokenStream tokens)
    {
        r.name = ctx.name.getText();
        r.methodNameOverride = r.name;
        if (ctx.returnType != null) r.returnType = srcText(ctx.returnType, tokens);
        List<VisitorMappingParser.HelperParamContext> params = ctx.helperParams().helperParam();
        VisitorMappingParser.HelperParamContext last = params.get(params.size() - 1);
        if (!"ctx".equals(last.IDENT().getText()))
        {
            throw new RuntimeException("helper's last param must be `CtxType ctx` — " + r.name);
        }
        r.contextTypeOverride = srcText(last.type, tokens);
        for (int j = 0; j < params.size() - 1; j++)
        {
            VisitorMappingParser.HelperParamContext p = params.get(j);
            r.extraParams.add(new String[] {srcText(p.type, tokens), p.IDENT().getText()});
        }
        for (VisitorMappingParser.BodyStmtContext s : ctx.bodyStmt())
        {
            applyBodyStmt(r, s, tokens);
        }
    }

    private static void applyBodyStmt(Rule r, VisitorMappingParser.BodyStmtContext stmt, CommonTokenStream tokens)
    {
        if (stmt.letStmt() != null)
        {
            applyLet(r, stmt.letStmt(), tokens, null);
        }
        else if (stmt.postStmt() != null)
        {
            r.postActions.add(buildPost(stmt.postStmt(), tokens));
        }
        else if (stmt.returnStmt() != null)
        {
            applyRuleReturn(r, srcText(stmt.returnStmt().value, tokens));
        }
        else if (stmt.altWhen() != null)
        {
            r.alts.add(buildAltWhen(stmt.altWhen(), tokens));
        }
        else if (stmt.altElse() != null)
        {
            r.alts.add(buildAltElse(stmt.altElse(), tokens));
        }
        else if (stmt.elseErrorStmt() != null)
        {
            r.elseError = stmt.elseErrorStmt().msg.getText();
        }
        else if (stmt.paramStmt() != null)
        {
            VisitorMappingParser.ParamStmtContext p = stmt.paramStmt();
            r.extraParams.add(new String[] {srcText(p.type, tokens), p.name.getText()});
        }
        else if (stmt.methodStmt() != null)
        {
            r.methodNameOverride = stmt.methodStmt().name.getText();
        }
        else if (stmt.setStmt() != null)
        {
            VisitorMappingParser.SetStmtContext s = stmt.setStmt();
            r.sets.add(new String[] {s.name.getText(), srcText(s.value, tokens)});
        }
        else if (stmt.leftFoldStmt() != null)
        {
            applyLeftFold(r, stmt.leftFoldStmt(), tokens);
        }
        else if (stmt.chainFoldStmt() != null)
        {
            applyChainFold(r, stmt.chainFoldStmt(), tokens);
        }
        else if (stmt.growListStmt() != null)
        {
            applyGrowList(r, stmt.growListStmt(), tokens);
        }
    }

    /** Apply a `let TYPE NAME = EXPR` to the enclosing container (rule-level or left_fold). */
    private static void applyLet(Rule r, VisitorMappingParser.LetStmtContext ctx, CommonTokenStream tokens, LeftFold targetFold)
    {
        String[] entry = new String[] {srcText(ctx.type, tokens), ctx.name.getText(), srcText(ctx.value, tokens)};
        if (targetFold != null)
        {
            targetFold.lets.add(entry);
        }
        else
        {
            r.lets.add(entry);
        }
    }

    private static PostAction buildPost(VisitorMappingParser.PostStmtContext ctx, CommonTokenStream tokens)
    {
        PostAction pa = new PostAction();
        if (ctx instanceof VisitorMappingParser.ConditionalPostContext)
        {
            VisitorMappingParser.ConditionalPostContext c = (VisitorMappingParser.ConditionalPostContext) ctx;
            pa.predicate = srcText(c.guard, tokens);
            pa.stmt = srcText(c.stmt, tokens);
        }
        else
        {
            VisitorMappingParser.UnconditionalPostContext c = (VisitorMappingParser.UnconditionalPostContext) ctx;
            pa.stmt = srcText(c.stmt, tokens);
        }
        return pa;
    }

    /**
     * Rule-level `return EXPR`: try to decompose `register(newImpl(T, ...))` or `newImpl(T, ...)`
     * into emit + fields. If the return is anything else (e.g. a helper call), peel any
     * `register(...)` wrap into `r.topLevel` and store the inner expression as a synth alt.
     */
    private static void applyRuleReturn(Rule r, String returnExpr)
    {
        VisitorMappingParser.ExpressionContext exprTree = parseExpr(returnExpr);
        if (tryDecomposeReturnTree(r, exprTree)) return;
        // The return is something other than a decomposable newImpl/register-newImpl.
        // Peel a top-level `register(...)` if present, flag the rule as topLevel, and
        // store the inner expression as a synth alt.
        VisitorMappingParser.CallPrimContext call = extractCallPrim(exprTree);
        if (call != null && "register".equals(call.func.getText()))
        {
            List<VisitorMappingParser.ArgContext> args = callArgs(call);
            if (args.size() == 1 && args.get(0).namedArg() == null)
            {
                r.topLevel = true;
                returnExpr = args.get(0).getText();
            }
        }
        Alt synth = new Alt();
        synth.predicate = null;
        synth.returnExpr = returnExpr;
        r.alts.add(synth);
    }

    private static Alt buildAltWhen(VisitorMappingParser.AltWhenContext ctx, CommonTokenStream tokens)
    {
        Alt a = new Alt();
        a.predicate = srcText(ctx.guard, tokens);
        for (VisitorMappingParser.AltBodyContext b : ctx.altBody())
        {
            applyAltBody(a, b, tokens);
        }
        return a;
    }

    private static Alt buildAltElse(VisitorMappingParser.AltElseContext ctx, CommonTokenStream tokens)
    {
        Alt a = new Alt();
        a.predicate = null;
        for (VisitorMappingParser.AltBodyContext b : ctx.altBody())
        {
            applyAltBody(a, b, tokens);
        }
        return a;
    }

    /**
     * Apply an alt-body statement: `return`, `let`, `step` (chain_fold/grow_list bodies),
     * `yield` (grow_list body), or `post`. `step` and `yield` both store the expression in
     * `returnExpr` (reused as the dispatch-branch value).
     */
    private static void applyAltBody(Alt a, VisitorMappingParser.AltBodyContext ctx, CommonTokenStream tokens)
    {
        if (ctx.returnStmt() != null)
        {
            String e = srcText(ctx.returnStmt().value, tokens);
            if (!tryDecomposeUnifiedAltReturn(a, e))
            {
                a.returnExpr = e;
            }
        }
        else if (ctx.letStmt() != null)
        {
            // Alt-local lets are rare; lift to rule-level by storing on the alt directly
            // is not supported in the current IR. The existing emit just expects per-alt
            // fields/returnExpr, so a let inside an alt body is intentionally rejected here.
            throw new RuntimeException("let is not supported inside alt body — use rule-level let");
        }
        else if (ctx.stepStmt() != null)
        {
            a.returnExpr = srcText(ctx.stepStmt().value, tokens);
        }
        else if (ctx.yieldStmt() != null)
        {
            a.returnExpr = srcText(ctx.yieldStmt().value, tokens);
        }
        else if (ctx.postStmt() != null)
        {
            throw new RuntimeException("post is not supported inside alt body");
        }
    }

    private static void applyLeftFold(Rule r, VisitorMappingParser.LeftFoldStmtContext ctx, CommonTokenStream tokens)
    {
        r.leftFold = new LeftFold();
        // `$ctx.X` — the operand sub-rule. Strip the `$ctx.` prefix.
        String over = srcText(ctx.over, tokens);
        r.leftFold.operandRule = over.substring("$ctx.".length());
        for (VisitorMappingParser.LeftFoldBodyContext b : ctx.leftFoldBody())
        {
            if (b.letStmt() != null)
            {
                applyLet(r, b.letStmt(), tokens, r.leftFold);
            }
            else if (b.stepStmt() != null)
            {
                r.leftFold.stepExpr = srcText(b.stepStmt().value, tokens);
            }
            else if (b.leftFoldAltWhen() != null)
            {
                VisitorMappingParser.LeftFoldAltWhenContext lf = b.leftFoldAltWhen();
                Alt a = new Alt();
                a.predicate = lf.tokName.getText();
                for (VisitorMappingParser.AltBodyContext ab : lf.altBody())
                {
                    applyAltBody(a, ab, tokens);
                }
                r.leftFold.alts.add(a);
            }
            else if (b.altElse() != null)
            {
                Alt elseAlt = new Alt();
                elseAlt.predicate = null;
                for (VisitorMappingParser.AltBodyContext ab : b.altElse().altBody())
                {
                    applyAltBody(elseAlt, ab, tokens);
                }
                // The alt-else step inside a left_fold body becomes the elseStep expression.
                r.leftFold.elseStep = elseAlt.returnExpr;
            }
        }
    }

    private static void applyChainFold(Rule r, VisitorMappingParser.ChainFoldStmtContext ctx, CommonTokenStream tokens)
    {
        r.chainFold = new ChainFold();
        r.chainFold.seedExpr = srcText(ctx.seed, tokens);
        String over = srcText(ctx.over, tokens);
        r.chainFold.overRule = over.substring("$ctx.".length());
        for (VisitorMappingParser.ChainFoldBodyContext b : ctx.chainFoldBody())
        {
            if (b.altWhen() != null)
            {
                r.chainFold.alts.add(buildAltWhen(b.altWhen(), tokens));
            }
            else if (b.elseStepStmt() != null)
            {
                r.chainFold.elseStep = srcText(b.elseStepStmt().value, tokens);
            }
        }
    }

    private static void applyGrowList(Rule r, VisitorMappingParser.GrowListStmtContext ctx, CommonTokenStream tokens)
    {
        r.growList = new GrowList();
        String over = srcText(ctx.over, tokens);
        r.growList.overRule = over.substring("$ctx.".length());
        for (VisitorMappingParser.GrowListBodyContext b : ctx.growListBody())
        {
            if (b.altWhen() != null)
            {
                r.growList.alts.add(buildAltWhen(b.altWhen(), tokens));
            }
            else if (b.altElse() != null)
            {
                r.growList.alts.add(buildAltElse(b.altElse(), tokens));
            }
        }
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
                        VisitorMappingParser.CallPrimContext letCall = extractCallPrim(parseExpr(l[2]));
                        if (letCall != null && "newImpl".equals(letCall.func.getText()))
                        {
                            List<VisitorMappingParser.ArgContext> args = callArgs(letCall);
                            if (!args.isEmpty())
                            {
                                r.emitType = args.get(0).getText();
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
                VisitorMappingParser.CallPrimContext altCall = extractCallPrim(parseExpr(a.returnExpr));
                if (altCall == null || !"newImpl".equals(altCall.func.getText())) return;
                List<VisitorMappingParser.ArgContext> args = callArgs(altCall);
                if (args.isEmpty()) return;
                t = args.get(0).getText();
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
    private static boolean tryDecomposeUnifiedReturn(Rule r, String exprStr)
    {
        return tryDecomposeReturnTree(r, parseExpr(exprStr));
    }

    /**
     * Tree-based decomposer for a rule-level return. Recognizes:
     *   register(newImpl(T, …))      — sets r.topLevel=true, decomposes inner
     *   newImpl(T, k=v, k=ifPresent(p,e), …)
     * Returns false for anything else; caller falls back to a synth alt.
     */
    private static boolean tryDecomposeReturnTree(Rule r, VisitorMappingParser.ExpressionContext expr)
    {
        VisitorMappingParser.CallPrimContext call = extractCallPrim(expr);
        if (call == null) return false;
        boolean wrappedInRegister = false;
        if ("register".equals(call.func.getText()))
        {
            List<VisitorMappingParser.ArgContext> rArgs = callArgs(call);
            if (rArgs.size() != 1 || rArgs.get(0).namedArg() != null) return false;
            VisitorMappingParser.CallPrimContext inner = extractCallPrim(rArgs.get(0).expression());
            if (inner == null) return false;
            wrappedInRegister = true;
            call = inner;
        }
        if (!"newImpl".equals(call.func.getText())) return false;
        List<VisitorMappingParser.ArgContext> args = callArgs(call);
        if (args.isEmpty()) return false;
        String emitType = args.get(0).getText();
        Alt defaultAlt = new Alt();
        for (int j = 1; j < args.size(); j++)
        {
            Field f = parseFieldFromArg(args.get(j));
            if (f == null) return false;
            defaultAlt.fields.add(f);
        }
        r.emitType = emitType;
        r.alts.add(defaultAlt);
        if (wrappedInRegister) r.topLevel = true;
        return true;
    }

    /**
     * Per-alt decomposer (tree form): if the alt return is `newImpl(T, k=v, …)` with
     * at least one 2-arg ifPresent, set alt.altEmitType=T and alt.fields. Otherwise
     * return false so the caller stores returnExpr verbatim.
     */
    private static boolean tryDecomposeUnifiedAltReturn(Alt a, String exprStr)
    {
        VisitorMappingParser.ExpressionContext expr = parseExpr(exprStr);
        VisitorMappingParser.CallPrimContext call = extractCallPrim(expr);
        if (call == null || !"newImpl".equals(call.func.getText())) return false;
        List<VisitorMappingParser.ArgContext> args = callArgs(call);
        if (args.isEmpty()) return false;
        List<Field> fields = new ArrayList<>();
        boolean anyConditional = false;
        for (int j = 1; j < args.size(); j++)
        {
            Field f = parseFieldFromArg(args.get(j));
            if (f == null) return false;
            if (f.predicate != null) anyConditional = true;
            fields.add(f);
        }
        if (!anyConditional) return false;
        a.altEmitType = args.get(0).getText();
        a.fields.addAll(fields);
        return true;
    }

    /**
     * If `expr` is a bare CallPrim (no chain segments), return the call; else null.
     * Used by the decomposers to recognize `register(...)`, `newImpl(...)`, `ifPresent(...)`.
     */
    private static VisitorMappingParser.CallPrimContext extractCallPrim(VisitorMappingParser.ExpressionContext expr)
    {
        if (!(expr instanceof VisitorMappingParser.ChainedPrimaryContext)) return null;
        VisitorMappingParser.ChainedPrimaryContext cp = (VisitorMappingParser.ChainedPrimaryContext) expr;
        if (!cp.chainSegment().isEmpty()) return null;
        if (!(cp.primary() instanceof VisitorMappingParser.CallPrimContext)) return null;
        return (VisitorMappingParser.CallPrimContext) cp.primary();
    }

    private static List<VisitorMappingParser.ArgContext> callArgs(VisitorMappingParser.CallPrimContext call)
    {
        return call.argList() == null ? java.util.Collections.emptyList() : call.argList().arg();
    }

    /**
     * Parse a newImpl argument as a Field. The arg MUST be a namedArg (`k = v`).
     * If `v` is a 2-arg `ifPresent(pred, expr)`, the field is optional. Returns null
     * if the arg shape doesn't match (caller bails out of decomposition).
     */
    private static Field parseFieldFromArg(VisitorMappingParser.ArgContext arg)
    {
        if (arg.namedArg() == null) return null;
        VisitorMappingParser.NamedArgContext na = arg.namedArg();
        Field f = new Field();
        f.name = na.name.getText();
        VisitorMappingParser.CallPrimContext call = extractCallPrim(na.value);
        if (call != null && "ifPresent".equals(call.func.getText()))
        {
            List<VisitorMappingParser.ArgContext> args = callArgs(call);
            if (args.size() == 2 && args.get(0).namedArg() == null && args.get(1).namedArg() == null)
            {
                f.predicate = args.get(0).getText();
                f.expr = args.get(1).getText();
                return f;
            }
        }
        f.expr = na.value.getText();
        return f;
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
        String ctxType = qualifyCtxType(r.contextTypeOverride != null ? r.contextTypeOverride : capitalize(r.name) + "Context");
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
        String ctxType = qualifyCtxType(r.contextTypeOverride != null ? r.contextTypeOverride : capitalize(r.name) + "Context");
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
        String ctxType = qualifyCtxType(r.contextTypeOverride != null ? r.contextTypeOverride : capitalize(r.name) + "Context");
        String rhsCtxType = qualifyCtxType(capitalize(lf.operandRule) + "Context");
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
        return emitExpr(parseExpr(dslExpr), null, true);
    }

    // ============================================================================
    // Tree-walking expression emitter.
    //
    // Walks an ANTLR ExpressionContext (parsed from a DSL expression string) and
    // produces the equivalent Java text. Replaces the prior `startsWith` cascade
    // of primitive dispatchers in `exprToJava` with a single switch over the
    // function name in callPrim nodes. The `inFoldBody` flag selects fold-local
    // semantics for the bare identifiers `acc` / `rhs` and the $tok / $rhsCtx
    // primaries (which map to the per-iteration locals `result` / `rhs` / `opTok`
    // / `rhsCtx` in the emitted loop body).
    // ============================================================================

    /** Parse a DSL expression string into an ANTLR ExpressionContext. */
    private static VisitorMappingParser.ExpressionContext parseExpr(String dsl)
    {
        VisitorMappingLexer lex = new VisitorMappingLexer(CharStreams.fromString(dsl));
        CommonTokenStream toks = new CommonTokenStream(lex);
        VisitorMappingParser p = new VisitorMappingParser(toks);
        p.removeErrorListeners();
        p.addErrorListener(new BaseErrorListener()
        {
            @Override
            public void syntaxError(Recognizer<?, ?> recognizer, Object offendingSymbol,
                                    int line, int charPositionInLine, String msg, RecognitionException e)
            {
                throw new RuntimeException("expression syntax error in `" + dsl + "` at "
                        + line + ":" + charPositionInLine + " — " + msg);
            }
        });
        return p.exprEntry().expression();
    }

    /** Parse a DSL predicate string into an ANTLR PredicateContext. */
    private static VisitorMappingParser.PredicateContext parsePred(String dsl)
    {
        VisitorMappingLexer lex = new VisitorMappingLexer(CharStreams.fromString(dsl));
        CommonTokenStream toks = new CommonTokenStream(lex);
        VisitorMappingParser p = new VisitorMappingParser(toks);
        p.removeErrorListeners();
        p.addErrorListener(new BaseErrorListener()
        {
            @Override
            public void syntaxError(Recognizer<?, ?> recognizer, Object offendingSymbol,
                                    int line, int charPositionInLine, String msg, RecognitionException e)
            {
                throw new RuntimeException("predicate syntax error in `" + dsl + "` at "
                        + line + ":" + charPositionInLine + " — " + msg);
            }
        });
        return p.predEntry().predicate();
    }

    private static String emitExpr(VisitorMappingParser.ExpressionContext ctx, String cachedTextToken, boolean inFoldBody)
    {
        StringBuilder out = new StringBuilder();
        emitExprInto(out, ctx, cachedTextToken, inFoldBody);
        return out.toString();
    }

    private static void emitExprInto(StringBuilder out, VisitorMappingParser.ExpressionContext ctx, String cachedTextToken, boolean inFoldBody)
    {
        if (ctx instanceof VisitorMappingParser.NegExprContext)
        {
            out.append('-');
            emitExprInto(out, ((VisitorMappingParser.NegExprContext) ctx).expression(), cachedTextToken, inFoldBody);
            return;
        }
        if (ctx instanceof VisitorMappingParser.MulDivExprContext)
        {
            VisitorMappingParser.MulDivExprContext m = (VisitorMappingParser.MulDivExprContext) ctx;
            emitExprInto(out, m.left, cachedTextToken, inFoldBody);
            out.append(' ').append(m.op.getText()).append(' ');
            emitExprInto(out, m.right, cachedTextToken, inFoldBody);
            return;
        }
        if (ctx instanceof VisitorMappingParser.AddSubExprContext)
        {
            VisitorMappingParser.AddSubExprContext a = (VisitorMappingParser.AddSubExprContext) ctx;
            emitExprInto(out, a.left, cachedTextToken, inFoldBody);
            out.append(' ').append(a.op.getText()).append(' ');
            emitExprInto(out, a.right, cachedTextToken, inFoldBody);
            return;
        }
        if (ctx instanceof VisitorMappingParser.AndExprContext)
        {
            VisitorMappingParser.AndExprContext a = (VisitorMappingParser.AndExprContext) ctx;
            emitExprInto(out, a.left, cachedTextToken, inFoldBody);
            out.append(" && ");
            emitExprInto(out, a.right, cachedTextToken, inFoldBody);
            return;
        }
        // ChainedPrimary
        VisitorMappingParser.ChainedPrimaryContext cp = (VisitorMappingParser.ChainedPrimaryContext) ctx;
        emitChainedPrimary(out, cp, cachedTextToken, inFoldBody);
    }

    /**
     * Emit a chained primary expression. Special-cases $ctx-rooted and $it-rooted paths
     * so each `.X` segment becomes `.X()` (sub-rule accessor) and a trailing `.text`
     * becomes `.getText()` (with the __t cache optimization). On any other primary the
     * chain segments are emitted as plain Java member access.
     */
    private static void emitChainedPrimary(StringBuilder out, VisitorMappingParser.ChainedPrimaryContext ctx, String cachedTextToken, boolean inFoldBody)
    {
        VisitorMappingParser.PrimaryContext primary = ctx.primary();
        List<VisitorMappingParser.ChainSegmentContext> chain = ctx.chainSegment();
        if (primary instanceof VisitorMappingParser.CtxBarePrimContext)
        {
            emitCtxPathChain(out, chain, cachedTextToken);
            return;
        }
        if (primary instanceof VisitorMappingParser.ItBarePrimContext)
        {
            emitItPathChain(out, chain);
            return;
        }
        emitPrimary(out, primary, cachedTextToken, inFoldBody);
        for (VisitorMappingParser.ChainSegmentContext seg : chain)
        {
            emitChainSegment(out, seg, cachedTextToken, inFoldBody);
        }
    }

    /**
     * $ctx + chain → ctx.X().Y()… in Java. Path-style segments add `()`; a trailing
     * `.text` becomes `.getText()`. If the path is the cached-text shortcut
     * ($ctx.X.text where X == cachedTextToken), emit __t. Method-call segments
     * (`$ctx.X.method(args)`) keep their explicit parens and arg list.
     */
    private static void emitCtxPathChain(StringBuilder out, List<VisitorMappingParser.ChainSegmentContext> chain, String cachedTextToken)
    {
        if (chain.isEmpty())
        {
            out.append("ctx");
            return;
        }
        // Detect trailing .text (only valid when last segment is a plain pathSeg named "text")
        VisitorMappingParser.ChainSegmentContext last = chain.get(chain.size() - 1);
        boolean endsInText = (last instanceof VisitorMappingParser.PathSegContext)
                && "text".equals(((VisitorMappingParser.PathSegContext) last).member.getText())
                && chain.size() >= 2;
        // __t cache optimization for single-segment .text
        if (endsInText && chain.size() == 2 && cachedTextToken != null
                && chain.get(0) instanceof VisitorMappingParser.PathSegContext)
        {
            String firstSeg = ((VisitorMappingParser.PathSegContext) chain.get(0)).member.getText();
            if (cachedTextToken.equals(firstSeg))
            {
                out.append("__t");
                return;
            }
        }
        out.append("ctx");
        int upto = endsInText ? chain.size() - 1 : chain.size();
        for (int i = 0; i < upto; i++)
        {
            VisitorMappingParser.ChainSegmentContext seg = chain.get(i);
            if (seg instanceof VisitorMappingParser.PathSegContext)
            {
                out.append('.').append(((VisitorMappingParser.PathSegContext) seg).member.getText()).append("()");
            }
            else
            {
                VisitorMappingParser.CallSegContext cs = (VisitorMappingParser.CallSegContext) seg;
                out.append('.').append(cs.member.getText()).append('(');
                emitArgList(out, cs.argList(), cachedTextToken, false);
                out.append(')');
            }
        }
        if (endsInText) out.append(".getText()");
    }

    /** $it + chain → it.X().Y()…; same shape as $ctx but no cache shortcut and `it` as receiver. */
    private static void emitItPathChain(StringBuilder out, List<VisitorMappingParser.ChainSegmentContext> chain)
    {
        if (chain.isEmpty())
        {
            out.append("it");
            return;
        }
        VisitorMappingParser.ChainSegmentContext last = chain.get(chain.size() - 1);
        boolean endsInText = (last instanceof VisitorMappingParser.PathSegContext)
                && "text".equals(((VisitorMappingParser.PathSegContext) last).member.getText())
                && chain.size() >= 2;
        out.append("it");
        int upto = endsInText ? chain.size() - 1 : chain.size();
        for (int i = 0; i < upto; i++)
        {
            VisitorMappingParser.ChainSegmentContext seg = chain.get(i);
            if (seg instanceof VisitorMappingParser.PathSegContext)
            {
                out.append('.').append(((VisitorMappingParser.PathSegContext) seg).member.getText()).append("()");
            }
            else
            {
                VisitorMappingParser.CallSegContext cs = (VisitorMappingParser.CallSegContext) seg;
                out.append('.').append(cs.member.getText()).append('(');
                emitArgList(out, cs.argList(), null, false);
                out.append(')');
            }
        }
        if (endsInText) out.append(".getText()");
    }

    private static void emitPrimary(StringBuilder out, VisitorMappingParser.PrimaryContext ctx, String cachedTextToken, boolean inFoldBody)
    {
        if (ctx instanceof VisitorMappingParser.IntPrimContext) { out.append(ctx.getText()); return; }
        if (ctx instanceof VisitorMappingParser.StringPrimContext) { out.append(ctx.getText()); return; }
        if (ctx instanceof VisitorMappingParser.TruePrimContext) { out.append("true"); return; }
        if (ctx instanceof VisitorMappingParser.FalsePrimContext) { out.append("false"); return; }
        if (ctx instanceof VisitorMappingParser.CtxBarePrimContext) { out.append("ctx"); return; }
        if (ctx instanceof VisitorMappingParser.ItBarePrimContext) { out.append("it"); return; }
        if (ctx instanceof VisitorMappingParser.TokPrimContext)
        {
            if (!inFoldBody) throw new RuntimeException("$tok used outside left_fold body");
            out.append("opTok");
            return;
        }
        if (ctx instanceof VisitorMappingParser.RhsCtxPrimContext)
        {
            if (!inFoldBody) throw new RuntimeException("$rhsCtx used outside left_fold body");
            out.append("rhsCtx");
            return;
        }
        if (ctx instanceof VisitorMappingParser.MethodRefPrimContext)
        {
            out.append("this::").append(((VisitorMappingParser.MethodRefPrimContext) ctx).member.getText());
            return;
        }
        if (ctx instanceof VisitorMappingParser.QualifiedMethodRefPrimContext)
        {
            VisitorMappingParser.QualifiedMethodRefPrimContext q = (VisitorMappingParser.QualifiedMethodRefPrimContext) ctx;
            out.append(q.qualifier.getText()).append("::").append(q.member.getText());
            return;
        }
        if (ctx instanceof VisitorMappingParser.CallPrimContext)
        {
            emitCallPrim(out, (VisitorMappingParser.CallPrimContext) ctx, cachedTextToken, inFoldBody);
            return;
        }
        if (ctx instanceof VisitorMappingParser.IdentPrimContext)
        {
            String name = ((VisitorMappingParser.IdentPrimContext) ctx).name.getText();
            if (inFoldBody && "acc".equals(name))
            {
                out.append("result");
            }
            else
            {
                out.append(name);
            }
            return;
        }
        if (ctx instanceof VisitorMappingParser.ParenPrimContext)
        {
            out.append('(');
            emitExprInto(out, ((VisitorMappingParser.ParenPrimContext) ctx).inner, cachedTextToken, inFoldBody);
            out.append(')');
            return;
        }
        if (ctx instanceof VisitorMappingParser.CastPrimContext)
        {
            VisitorMappingParser.CastPrimContext c = (VisitorMappingParser.CastPrimContext) ctx;
            out.append('(').append(c.cast.getText()).append(") (");
            emitExprInto(out, c.castInner, cachedTextToken, inFoldBody);
            out.append(')');
            return;
        }
        throw new RuntimeException("unhandled primary: " + ctx.getClass().getSimpleName());
    }

    private static void emitChainSegment(StringBuilder out, VisitorMappingParser.ChainSegmentContext seg, String cachedTextToken, boolean inFoldBody)
    {
        if (seg instanceof VisitorMappingParser.PathSegContext)
        {
            // .X on a non-ctx, non-it primary: emit as Java member access (no auto-parens)
            out.append('.').append(((VisitorMappingParser.PathSegContext) seg).member.getText());
            return;
        }
        VisitorMappingParser.CallSegContext cs = (VisitorMappingParser.CallSegContext) seg;
        out.append('.').append(cs.member.getText()).append('(');
        emitArgList(out, cs.argList(), cachedTextToken, inFoldBody);
        out.append(')');
    }

    /** Emit an arg list as Java arguments (comma-separated). Named args are not allowed here. */
    private static void emitArgList(StringBuilder out, VisitorMappingParser.ArgListContext args, String cachedTextToken, boolean inFoldBody)
    {
        if (args == null) return;
        List<VisitorMappingParser.ArgContext> argList = args.arg();
        for (int i = 0; i < argList.size(); i++)
        {
            if (i > 0) out.append(", ");
            VisitorMappingParser.ArgContext a = argList.get(i);
            if (a.namedArg() != null)
            {
                throw new RuntimeException("named arg only valid inside newImpl(...): " + a.getText());
            }
            emitExprInto(out, a.expression(), cachedTextToken, inFoldBody);
        }
    }

    /**
     * Dispatch a function-call primary (`name(args)`) by name. Known DSL primitives
     * have specific Java translations; an unknown name falls through to a plain Java
     * call (`name(args)`).
     */
    private static void emitCallPrim(StringBuilder out, VisitorMappingParser.CallPrimContext ctx, String cachedTextToken, boolean inFoldBody)
    {
        String name = ctx.func.getText();
        List<VisitorMappingParser.ArgContext> args = ctx.argList() == null ? java.util.Collections.emptyList() : ctx.argList().arg();
        switch (name)
        {
            case "ifPresent": emitIfPresent(out, args, cachedTextToken, inFoldBody); return;
            case "match": emitMatch(out, args, cachedTextToken, inFoldBody); return;
            case "newImpl": emitNewImpl(out, args, cachedTextToken, inFoldBody); return;
            case "listOf": emitListOf(out, args, cachedTextToken, inFoldBody); return;
            case "mapList": emitMapList(out, args, cachedTextToken, inFoldBody); return;
            case "filterMap": emitFilterMap(out, args, false); return;
            case "filterMapNot": emitFilterMap(out, args, true); return;
            case "prepended": emitPrepended(out, args, cachedTextToken, inFoldBody); return;
            case "primitiveType": emitPrimitiveType(out, args); return;
            case "count": emitCount(out, args); return;
            case "multBounds": emitMultBounds(out, args, cachedTextToken, inFoldBody); return;
            case "joinTextWith": emitJoinTextWith(out, args, cachedTextToken, inFoldBody); return;
            case "joinStringsWith": emitJoinStringsWith(out, args, cachedTextToken, inFoldBody); return;
            case "joinStripped": emitJoinStripped(out, args); return;
            case "parseLong": emitParse(out, args, "Long", cachedTextToken, inFoldBody); return;
            case "parseDouble": emitParse(out, args, "Double", cachedTextToken, inFoldBody); return;
            case "parseBoolean": emitParse(out, args, "Boolean", cachedTextToken, inFoldBody); return;
            case "stripQuotes": emitStripQuotes(out, args, cachedTextToken, inFoldBody); return;
            case "stripPercent": emitStripPercent(out, args, cachedTextToken, inFoldBody); return;
            case "stripParens": emitStripParens(out, args, cachedTextToken, inFoldBody); return;
            case "stripIfQuoted": emitStripIfQuoted(out, args, cachedTextToken, inFoldBody); return;
            case "capitalize": emitCapitalizeCall(out, args, cachedTextToken, inFoldBody); return;
            case "enumPointer": emitEnumPointer(out, args, cachedTextToken, inFoldBody); return;
            case "notEmpty": emitNotEmpty(out, args, cachedTextToken, inFoldBody); return;
            case "nonNull": emitNonNull(out, args, cachedTextToken, inFoldBody); return;
            case "simpleNameOf": emitSimpleNameOf(out, args, cachedTextToken, inFoldBody); return;
            case "packagePrefix": emitPackagePrefix(out, args, cachedTextToken, inFoldBody); return;
            case "hasPackagePrefix": emitHasPackagePrefix(out, args, cachedTextToken, inFoldBody); return;
            case "beforeFirstDot": emitBeforeFirstDot(out, args, cachedTextToken, inFoldBody); return;
            case "firstOf": emitFirstOf(out, args); return;
            case "anyHas": emitAnyHas(out, args); return;
            case "anyHasAny": emitAnyHasAny(out, args); return;
            case "selectMapHasAny": emitSelectMapHasAny(out, args); return;
            case "hasAny": emitHasAny(out, args); return;
            case "dateLiteralType": emitDateLiteralType(out, args, cachedTextToken, inFoldBody); return;
            default:
                // Unknown function name — emit as a plain Java call.
                out.append(name).append('(');
                for (int i = 0; i < args.size(); i++)
                {
                    if (i > 0) out.append(", ");
                    VisitorMappingParser.ArgContext a = args.get(i);
                    if (a.namedArg() != null) throw new RuntimeException("named arg not allowed in " + name);
                    emitExprInto(out, a.expression(), cachedTextToken, inFoldBody);
                }
                out.append(')');
        }
    }

    // ---- per-primitive emit methods ----

    private static void emitIfPresent(StringBuilder out, List<VisitorMappingParser.ArgContext> args, String cachedTextToken, boolean inFoldBody)
    {
        if (args.size() == 3)
        {
            // Ternary form: predicate, then, else
            out.append('(');
            out.append(emitPred(parsePred(args.get(0).getText()), inFoldBody));
            out.append(" ? ");
            emitExprInto(out, args.get(1).expression(), cachedTextToken, inFoldBody);
            out.append(" : ");
            emitExprInto(out, args.get(2).expression(), cachedTextToken, inFoldBody);
            out.append(')');
            return;
        }
        if (args.size() == 2)
        {
            throw new RuntimeException("2-arg ifPresent is only valid as a top-level newImpl field "
                    + "(rule return or let binding); found in expression position");
        }
        throw new RuntimeException("ifPresent needs 2 or 3 args, got " + args.size());
    }

    private static void emitMatch(StringBuilder out, List<VisitorMappingParser.ArgContext> args, String cachedTextToken, boolean inFoldBody)
    {
        if (args.size() < 3 || (args.size() - 1) % 2 != 0)
        {
            throw new RuntimeException("match needs discriminator + N (token, value) pairs");
        }
        StringBuilder disc = new StringBuilder();
        emitExprInto(disc, args.get(0).expression(), cachedTextToken, inFoldBody);
        out.append('(');
        int nPairs = (args.size() - 1) / 2;
        for (int j = 0; j < nPairs - 1; j++)
        {
            String tok = args.get(1 + 2 * j).getText();
            out.append(disc).append(".getType() == M3Lexer.").append(tok).append(" ? ");
            emitExprInto(out, args.get(2 + 2 * j).expression(), cachedTextToken, inFoldBody);
            out.append(" : ");
        }
        // Last pair: the value is the fallback (its token name is ignored).
        emitExprInto(out, args.get(args.size() - 1).expression(), cachedTextToken, inFoldBody);
        out.append(')');
    }

    /**
     * newImpl(TypeName, k=v, k=v, …) → new TypeNameImpl()._k(v)._k(v) — fluent chain.
     * For the 2-arg `ifPresent` skip-sentinel, this is the WRONG context (it's only
     * valid at the rule-return or let-binding level, where the decomposer handles it).
     */
    private static void emitNewImpl(StringBuilder out, List<VisitorMappingParser.ArgContext> args, String cachedTextToken, boolean inFoldBody)
    {
        if (args.isEmpty()) throw new RuntimeException("newImpl needs at least a type");
        String typeName = args.get(0).getText();
        out.append("new ").append(typeName).append("Impl()");
        for (int j = 1; j < args.size(); j++)
        {
            VisitorMappingParser.ArgContext a = args.get(j);
            if (a.namedArg() == null) throw new RuntimeException("newImpl args after type must be `key = value`");
            VisitorMappingParser.NamedArgContext na = a.namedArg();
            out.append("._").append(setter(na.name.getText()).substring(1));   // _sourceInformation special-case via setter()
            // setter() returns "_p_sourceInformation" for "sourceInformation"; strip the leading "_" we already added.
            // Rewind: simpler to just compute directly.
            out.setLength(out.length() - setter(na.name.getText()).length());
            out.append(setter(na.name.getText()));
            out.append('(');
            emitExprInto(out, na.value, cachedTextToken, inFoldBody);
            out.append(')');
        }
    }

    private static void emitListOf(StringBuilder out, List<VisitorMappingParser.ArgContext> args, String cachedTextToken, boolean inFoldBody)
    {
        if (args.isEmpty())
        {
            out.append("Lists.mutable.empty()");
            return;
        }
        out.append("Lists.mutable.with(");
        for (int j = 0; j < args.size(); j++)
        {
            if (j > 0) out.append(", ");
            VisitorMappingParser.ArgContext a = args.get(j);
            emitExprInto(out, a.expression(), cachedTextToken, inFoldBody);
        }
        out.append(')');
    }

    private static void emitMapList(StringBuilder out, List<VisitorMappingParser.ArgContext> args, String cachedTextToken, boolean inFoldBody)
    {
        if (args.size() != 2) throw new RuntimeException("mapList needs 2 args");
        out.append("ListAdapter.adapt(");
        emitExprInto(out, args.get(0).expression(), cachedTextToken, inFoldBody);
        out.append(").collect(this::").append(methodRefName(args.get(1).getText())).append(')');
    }

    /** filterMap($ctx.X, "needle", fn) — select-by-text-contains, then collect. */
    private static void emitFilterMap(StringBuilder out, List<VisitorMappingParser.ArgContext> args, boolean negate)
    {
        if (args.size() != 3) throw new RuntimeException((negate ? "filterMapNot" : "filterMap") + " needs 3 args");
        out.append("ListAdapter.adapt(");
        emitExprInto(out, args.get(0).expression(), null, false);
        out.append(").").append(negate ? "reject" : "select")
                .append("(__c -> __c.getText().contains(").append(args.get(1).getText())
                .append(")).collect(this::").append(methodRefName(args.get(2).getText())).append(')');
    }

    private static void emitPrepended(StringBuilder out, List<VisitorMappingParser.ArgContext> args, String cachedTextToken, boolean inFoldBody)
    {
        if (args.size() != 2) throw new RuntimeException("prepended needs 2 args");
        out.append("Lists.mutable.<ValueSpecification>with(");
        emitExprInto(out, args.get(0).expression(), cachedTextToken, inFoldBody);
        out.append(").withAll(");
        emitExprInto(out, args.get(1).expression(), cachedTextToken, inFoldBody);
        out.append(')');
    }

    private static void emitPrimitiveType(StringBuilder out, List<VisitorMappingParser.ArgContext> args)
    {
        if (args.size() != 1) throw new RuntimeException("primitiveType needs 1 arg");
        String nameStr = args.get(0).getText();
        out.append("new UserDefinedGenericTypeImpl()._type(new Type_PointerImpl()._value(").append(nameStr).append("))");
    }

    private static void emitCount(StringBuilder out, List<VisitorMappingParser.ArgContext> args)
    {
        // count($ctx.X) → ctx.X().size()
        if (args.size() != 1) throw new RuntimeException("count needs 1 arg");
        emitExprInto(out, args.get(0).expression(), null, false);
        out.append(".size()");
    }

    private static void emitMultBounds(StringBuilder out, List<VisitorMappingParser.ArgContext> args, String cachedTextToken, boolean inFoldBody)
    {
        if (args.size() != 2) throw new RuntimeException("multBounds needs 2 args");
        out.append("new UserDefinedAdHocMultiplicityImpl()._lowerBound(new MultiplicityValueImpl()._value((long) (");
        emitExprInto(out, args.get(0).expression(), cachedTextToken, inFoldBody);
        out.append(")))._upperBound(new MultiplicityValueImpl()._value((long) (");
        emitExprInto(out, args.get(1).expression(), cachedTextToken, inFoldBody);
        out.append(")))");
    }

    private static void emitJoinTextWith(StringBuilder out, List<VisitorMappingParser.ArgContext> args, String cachedTextToken, boolean inFoldBody)
    {
        if (args.size() != 2) throw new RuntimeException("joinTextWith needs 2 args");
        out.append("ListAdapter.adapt(");
        emitExprInto(out, args.get(0).expression(), cachedTextToken, inFoldBody);
        out.append(").collect(__n -> __n.getText()).makeString(").append(args.get(1).getText()).append(')');
    }

    /** joinStringsWith(list, sep) — emits `list.makeString(sep)`. List elements must already be Strings. */
    private static void emitJoinStringsWith(StringBuilder out, List<VisitorMappingParser.ArgContext> args, String cachedTextToken, boolean inFoldBody)
    {
        if (args.size() != 2) throw new RuntimeException("joinStringsWith needs 2 args");
        emitExprInto(out, args.get(0).expression(), cachedTextToken, inFoldBody);
        out.append(".makeString(");
        emitExprInto(out, args.get(1).expression(), cachedTextToken, inFoldBody);
        out.append(")");
    }

    private static void emitJoinStripped(StringBuilder out, List<VisitorMappingParser.ArgContext> args)
    {
        if (args.size() != 1) throw new RuntimeException("joinStripped needs 1 arg");
        out.append("ListAdapter.adapt(");
        emitExprInto(out, args.get(0).expression(), null, false);
        out.append(").collect(__n -> { String __raw = __n.getText();")
                .append(" return __raw.substring(1, __raw.length() - 1); }).makeString(\"\")");
    }

    private static void emitParse(StringBuilder out, List<VisitorMappingParser.ArgContext> args, String boxed, String cachedTextToken, boolean inFoldBody)
    {
        out.append(boxed).append(".parse").append(boxed).append('(');
        emitExprInto(out, args.get(0).expression(), cachedTextToken, inFoldBody);
        out.append(')');
    }

    private static void emitStripQuotes(StringBuilder out, List<VisitorMappingParser.ArgContext> args, String cachedTextToken, boolean inFoldBody)
    {
        // strip leading + trailing one char
        emitInnerStrExpr(out, args.get(0), cachedTextToken, inFoldBody);
        out.append(".substring(1, ");
        emitInnerStrExpr(out, args.get(0), cachedTextToken, inFoldBody);
        out.append(".length() - 1)");
    }

    private static void emitStripPercent(StringBuilder out, List<VisitorMappingParser.ArgContext> args, String cachedTextToken, boolean inFoldBody)
    {
        out.append('(');
        emitInnerStrExpr(out, args.get(0), cachedTextToken, inFoldBody);
        out.append(".startsWith(\"%\") ? ");
        emitInnerStrExpr(out, args.get(0), cachedTextToken, inFoldBody);
        out.append(".substring(1) : ");
        emitInnerStrExpr(out, args.get(0), cachedTextToken, inFoldBody);
        out.append(')');
    }

    private static void emitStripParens(StringBuilder out, List<VisitorMappingParser.ArgContext> args, String cachedTextToken, boolean inFoldBody)
    {
        out.append('(');
        emitInnerStrExpr(out, args.get(0), cachedTextToken, inFoldBody);
        out.append(".startsWith(\"(\") ? ");
        emitInnerStrExpr(out, args.get(0), cachedTextToken, inFoldBody);
        out.append(".substring(1, ");
        emitInnerStrExpr(out, args.get(0), cachedTextToken, inFoldBody);
        out.append(".length() - 1) : ");
        emitInnerStrExpr(out, args.get(0), cachedTextToken, inFoldBody);
        out.append(')');
    }

    private static void emitStripIfQuoted(StringBuilder out, List<VisitorMappingParser.ArgContext> args, String cachedTextToken, boolean inFoldBody)
    {
        out.append('(');
        emitInnerStrExpr(out, args.get(0), cachedTextToken, inFoldBody);
        out.append(".startsWith(\"'\") ? ");
        emitInnerStrExpr(out, args.get(0), cachedTextToken, inFoldBody);
        out.append(".substring(1, ");
        emitInnerStrExpr(out, args.get(0), cachedTextToken, inFoldBody);
        out.append(".length() - 1) : ");
        emitInnerStrExpr(out, args.get(0), cachedTextToken, inFoldBody);
        out.append(')');
    }

    private static void emitInnerStrExpr(StringBuilder out, VisitorMappingParser.ArgContext a, String cachedTextToken, boolean inFoldBody)
    {
        emitExprInto(out, a.expression(), cachedTextToken, inFoldBody);
    }

    private static void emitCapitalizeCall(StringBuilder out, List<VisitorMappingParser.ArgContext> args, String cachedTextToken, boolean inFoldBody)
    {
        // (s.length() > 0 ? Character.toUpperCase(s.charAt(0)) + s.substring(1) : s)
        String exprJava = emitExpr(args.get(0).expression(), cachedTextToken, inFoldBody);
        out.append('(').append(exprJava).append(".length() > 0 ? Character.toUpperCase(").append(exprJava)
                .append(".charAt(0)) + ").append(exprJava).append(".substring(1) : ").append(exprJava).append(')');
    }

    private static void emitEnumPointer(StringBuilder out, List<VisitorMappingParser.ArgContext> args, String cachedTextToken, boolean inFoldBody)
    {
        if (args.size() != 2) throw new RuntimeException("enumPointer needs 2 args (qualifiedName, value)");
        String qn = args.get(0).getText();
        out.append("new Enum_PointerImpl()._value(").append(qn).append(")._extraPointerValues(Lists.mutable.with(new PointerValueImpl()._value(");
        emitExprInto(out, args.get(1).expression(), cachedTextToken, inFoldBody);
        out.append(")))");
    }

    private static void emitNotEmpty(StringBuilder out, List<VisitorMappingParser.ArgContext> args, String cachedTextToken, boolean inFoldBody)
    {
        out.append("!");
        emitExprInto(out, args.get(0).expression(), cachedTextToken, inFoldBody);
        out.append(".isEmpty()");
    }

    /** nonNull(expr) — boolean predicate: emits `expr != null`. */
    private static void emitNonNull(StringBuilder out, List<VisitorMappingParser.ArgContext> args, String cachedTextToken, boolean inFoldBody)
    {
        if (args.size() != 1) throw new RuntimeException("nonNull needs 1 arg");
        emitExprInto(out, args.get(0).expression(), cachedTextToken, inFoldBody);
        out.append(" != null");
    }

    private static void emitSimpleNameOf(StringBuilder out, List<VisitorMappingParser.ArgContext> args, String cachedTextToken, boolean inFoldBody)
    {
        String inner = emitExpr(args.get(0).expression(), cachedTextToken, inFoldBody);
        out.append('(').append(inner).append(".contains(\"::\") ? ").append(inner)
                .append(".substring(").append(inner).append(".lastIndexOf(\"::\") + 2) : ").append(inner).append(')');
    }

    private static void emitPackagePrefix(StringBuilder out, List<VisitorMappingParser.ArgContext> args, String cachedTextToken, boolean inFoldBody)
    {
        String inner = emitExpr(args.get(0).expression(), cachedTextToken, inFoldBody);
        out.append(inner).append(".substring(0, ").append(inner).append(".lastIndexOf(\"::\"))");
    }

    private static void emitHasPackagePrefix(StringBuilder out, List<VisitorMappingParser.ArgContext> args, String cachedTextToken, boolean inFoldBody)
    {
        emitExprInto(out, args.get(0).expression(), cachedTextToken, inFoldBody);
        out.append(".contains(\"::\")");
    }

    private static void emitBeforeFirstDot(StringBuilder out, List<VisitorMappingParser.ArgContext> args, String cachedTextToken, boolean inFoldBody)
    {
        emitExprInto(out, args.get(0).expression(), cachedTextToken, inFoldBody);
        out.append(".split(\"\\\\.\")[0]");
    }

    private static void emitFirstOf(StringBuilder out, List<VisitorMappingParser.ArgContext> args)
    {
        // firstOf($ctx.X) — emit ctx.X().get(0)
        emitExprInto(out, args.get(0).expression(), null, false);
        out.append(".get(0)");
    }

    private static void emitAnyHas(StringBuilder out, List<VisitorMappingParser.ArgContext> args)
    {
        // anyHas($ctx.X, name) — ListAdapter.adapt(ctx.X()).anySatisfy(__c -> __c.name() != null)
        out.append("ListAdapter.adapt(");
        emitExprInto(out, args.get(0).expression(), null, false);
        out.append(").anySatisfy(__c -> __c.").append(args.get(1).getText()).append("() != null)");
    }

    private static void emitAnyHasAny(StringBuilder out, List<VisitorMappingParser.ArgContext> args)
    {
        // anyHasAny($ctx.X, n1, n2) — ListAdapter.adapt(ctx.X()).anySatisfy(__c -> __c.n1() != null || __c.n2() != null)
        out.append("ListAdapter.adapt(");
        emitExprInto(out, args.get(0).expression(), null, false);
        out.append(").anySatisfy(__c -> __c.").append(args.get(1).getText())
                .append("() != null || __c.").append(args.get(2).getText()).append("() != null)");
    }

    private static void emitSelectMapHasAny(StringBuilder out, List<VisitorMappingParser.ArgContext> args)
    {
        out.append("ListAdapter.adapt(");
        emitExprInto(out, args.get(0).expression(), null, false);
        out.append(").select(__c -> __c.").append(args.get(1).getText())
                .append("() != null || __c.").append(args.get(2).getText()).append("() != null).collect(this::")
                .append(methodRefName(args.get(3).getText())).append(')');
    }

    private static void emitHasAny(StringBuilder out, List<VisitorMappingParser.ArgContext> args)
    {
        // hasAny($ctx, n1, n2) — boolean: the current ctx has non-null n1() OR n2()
        out.append("(ctx.").append(args.get(1).getText())
                .append("() != null || ctx.").append(args.get(2).getText()).append("() != null)");
    }

    private static void emitDateLiteralType(StringBuilder out, List<VisitorMappingParser.ArgContext> args, String cachedTextToken, boolean inFoldBody)
    {
        String inner = emitExpr(args.get(0).expression(), cachedTextToken, inFoldBody);
        String dateTimeType = "new UserDefinedGenericTypeImpl()._type(new Type_PointerImpl()._value(\"DateTime\"))";
        String strictDateType = "new UserDefinedGenericTypeImpl()._type(new Type_PointerImpl()._value(\"StrictDate\"))";
        out.append(inner).append(".contains(\"T\") ? ").append(dateTimeType).append(" : ").append(strictDateType);
    }

    /**
     * Resolve a method-reference identifier in a primitive argument (e.g. the `fn` in
     * `mapList(list, fn)`). Names already starting with a verb prefix that matches an
     * existing rule's `method NAME` override (build / parse) are used verbatim; bare
     * rule names get the conventional "build" prefix.
     */
    private static String methodRefName(String name)
    {
        if (name.startsWith("build") || name.startsWith("parse")) return name;
        return "build" + capitalize(name);
    }

    /** Walk a predicate (= expression) tree, emitting the Java boolean expression. */
    private static String emitPred(VisitorMappingParser.PredicateContext ctx, boolean inFoldBody)
    {
        StringBuilder out = new StringBuilder();
        emitExprAsPredicate(out, ctx.expression(), inFoldBody);
        return out.toString();
    }

    /**
     * Walk an expression in predicate context: `$ctx.X` and `$it.X` paths emit as
     * null-safe chains (`ctx.X() != null && …`), `&&` is recursed on both sides so
     * each clause gets the path-aware treatment, and everything else is emitted as a
     * regular boolean expression.
     */
    private static void emitExprAsPredicate(StringBuilder out, VisitorMappingParser.ExpressionContext e, boolean inFoldBody)
    {
        if (e instanceof VisitorMappingParser.AndExprContext)
        {
            VisitorMappingParser.AndExprContext a = (VisitorMappingParser.AndExprContext) e;
            emitExprAsPredicate(out, a.left, inFoldBody);
            out.append(" && ");
            emitExprAsPredicate(out, a.right, inFoldBody);
            return;
        }
        if (e instanceof VisitorMappingParser.ChainedPrimaryContext)
        {
            VisitorMappingParser.ChainedPrimaryContext cp = (VisitorMappingParser.ChainedPrimaryContext) e;
            if (cp.primary() instanceof VisitorMappingParser.CtxBarePrimContext)
            {
                emitCtxPathPredicate(out, cp.chainSegment());
                return;
            }
            if (cp.primary() instanceof VisitorMappingParser.ItBarePrimContext)
            {
                emitItPathPredicate(out, cp.chainSegment());
                return;
            }
        }
        emitExprInto(out, e, null, inFoldBody);
    }

    /** $ctx.X.Y predicate → ctx.X() != null && ctx.X().Y() != null. */
    private static void emitCtxPathPredicate(StringBuilder out, List<VisitorMappingParser.ChainSegmentContext> chain)
    {
        StringBuilder cum = new StringBuilder("ctx");
        for (int i = 0; i < chain.size(); i++)
        {
            if (i > 0) out.append(" && ");
            VisitorMappingParser.ChainSegmentContext seg = chain.get(i);
            String member;
            if (seg instanceof VisitorMappingParser.PathSegContext)
            {
                member = ((VisitorMappingParser.PathSegContext) seg).member.getText();
                cum.append('.').append(member).append("()");
            }
            else
            {
                VisitorMappingParser.CallSegContext cs = (VisitorMappingParser.CallSegContext) seg;
                cum.append('.').append(cs.member.getText()).append('(');
                emitArgList(cum, cs.argList(), null, false);
                cum.append(')');
            }
            out.append(cum).append(" != null");
        }
    }

    /** $it.X predicate → it.X() != null. */
    private static void emitItPathPredicate(StringBuilder out, List<VisitorMappingParser.ChainSegmentContext> chain)
    {
        StringBuilder cum = new StringBuilder("it");
        for (int i = 0; i < chain.size(); i++)
        {
            if (i > 0) out.append(" && ");
            VisitorMappingParser.ChainSegmentContext seg = chain.get(i);
            if (seg instanceof VisitorMappingParser.PathSegContext)
            {
                cum.append('.').append(((VisitorMappingParser.PathSegContext) seg).member.getText()).append("()");
            }
            else
            {
                VisitorMappingParser.CallSegContext cs = (VisitorMappingParser.CallSegContext) seg;
                cum.append('.').append(cs.member.getText()).append('(');
                emitArgList(cum, cs.argList(), null, false);
                cum.append(')');
            }
            out.append(cum).append(" != null");
        }
    }

    private static void emitChainFold(StringBuilder sb, Rule r)
    {
        ChainFold cf = r.chainFold;
        String ctxType = qualifyCtxType(r.contextTypeOverride != null ? r.contextTypeOverride : capitalize(r.name) + "Context");
        String itemCtxType = qualifyCtxType(capitalize(cf.overRule) + "Context");
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
        String ctxType = qualifyCtxType(r.contextTypeOverride != null ? r.contextTypeOverride : capitalize(r.name) + "Context");
        String itemCtxType = qualifyCtxType(capitalize(gl.overRule) + "Context");
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

    /**
     * Translate a predicate DSL string to Java. Parses with ANTLR, then walks the
     * predicate tree: `$ctx.X` becomes a null-safe chain (`ctx.X() != null && …`),
     * any other clause is emitted as a regular boolean expression.
     */
    private static String predicateAsJava(String pred)
    {
        if (pred == null) return "true";
        return emitPred(parsePred(pred), false);
    }

    /**
     * Translate a DSL expression to Java. Parses the DSL string with the ANTLR
     * grammar and walks the resulting parse tree (see `emitExpr` and friends).
     * The `cachedTextToken` argument enables the `$ctx.X.text → __t` shortcut
     * when X matches a token whose text was already extracted into the local.
     */
    private static String exprToJava(String dslExpr, String cachedTextToken)
    {
        return emitExpr(parseExpr(dslExpr), cachedTextToken, false);
    }

    /** Compatibility shim — used by post-stmt emission; behaves identically to `exprToJava`. */
    private static String substituteContextRefs(String e, String cachedTextToken)
    {
        return emitExpr(parseExpr(e), cachedTextToken, false);
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
        VisitorMappingParser.CallPrimContext call = extractCallPrim(parseExpr(expr));
        if (call == null || !"newImpl".equals(call.func.getText())) return false;
        List<VisitorMappingParser.ArgContext> args = callArgs(call);
        if (args.size() < 2) return false;
        List<Field> fields = new ArrayList<>();
        boolean anyConditional = false;
        for (int j = 1; j < args.size(); j++)
        {
            Field f = parseFieldFromArg(args.get(j));
            if (f == null) return false;
            if (f.predicate != null) anyConditional = true;
            fields.add(f);
        }
        if (!anyConditional) return false;
        String implType = args.get(0).getText() + "Impl";
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
     * Qualify a context type with `M3Parser.` if it's an M3 grammar-rule context.
     * Pre-imported ANTLR runtime types (ParserRuleContext, RuleContext, Token) are
     * emitted bare so helpers can declare them directly.
     */
    private static final java.util.Set<String> ANTLR_RUNTIME_TYPES =
            java.util.Set.of("ParserRuleContext", "RuleContext", "Token");

    private static String qualifyCtxType(String t)
    {
        if (t.contains(".")) return t;                // already qualified
        if (ANTLR_RUNTIME_TYPES.contains(t)) return t; // pre-imported runtime type
        return "M3Parser." + t;
    }

}
