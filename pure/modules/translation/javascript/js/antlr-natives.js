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

// Pure ANTLR-bridge natives — implementations of the 17 functions declared in
// pure/specification/runtime/functions/meta/parser/antlr/*.pure that the
// translated parser-mappings interpreter (parseDocument, buildClass, …) calls
// to walk a ParserRuleContext. Each function mirrors the semantics of the
// Truffle-side AntlrNodes.java helper of the same name so the translated JS
// produces identical PureFile ASTs.
//
// Contexts (ParserRuleContext, TerminalNode) flow opaquely through Pure as
// `meta::pure::functions::meta::antlr::AntlrContext` PDOs; here they are the
// real antlr4 objects, and `ctx[ruleName]()` reflectively reads the generated
// rule-accessor methods (same pattern AntlrNodes uses on the Java-generated
// context classes).

import antlr4 from "antlr4";

import TopLexer from "./generated/TopLexer.js";
import TopParser from "./generated/TopParser.js";
import M3Lexer from "./generated/M3Lexer.js";
import M3Parser from "./generated/M3Parser.js";

const { ParserRuleContext, Token } = antlr4;
const TerminalNode = antlr4.tree.TerminalNode;

// Grammar registry — mirrors the Truffle-side GrammarExtension dispatch.
// Each entry knows how to turn a source string into a top-level
// ParserRuleContext for that grammar. Add new grammars here.
// Run a lexer+parser, collecting syntax errors via a listener that REPLACES the
// default ConsoleErrorListener (which only logs to stderr and lets the parser
// recover silently). If any syntax error fired, throw — so callers see parse
// failures instead of getting a quietly-broken tree. The thrown error carries a
// structured `parseErrors: [{line, column, msg}]` (column 0-based, antlr4
// convention) for editors that want to map errors back to source spans.
function parseWith(LexerClass, ParserClass, rule, source) {
    const lexer = new LexerClass(new antlr4.CharStream(source));
    const parser = new ParserClass(new antlr4.CommonTokenStream(lexer));
    const parseErrors = [];
    const listener = {
        syntaxError(_recognizer, _offendingSymbol, line, column, msg) { parseErrors.push({ line, column, msg }); },
        reportAmbiguity() {},
        reportAttemptingFullContext() {},
        reportContextSensitivity() {},
    };
    lexer.removeErrorListeners();
    lexer.addErrorListener(listener);
    parser.removeErrorListeners();
    parser.addErrorListener(listener);
    const ctx = parser[rule]();
    rememberRuleNames(ctx, parser.ruleNames);
    if (parseErrors.length > 0) {
        const err = new Error(
            "Parse error:\n" + parseErrors.map((e) => `  line ${e.line}:${e.column} ${e.msg}`).join("\n"),
        );
        err.parseErrors = parseErrors;
        throw err;
    }
    return ctx;
}

const GRAMMARS = {
    "TopParser": (source) => parseWith(TopLexer, TopParser, "document", source),
    "M3Parser": (source) => parseWith(M3Lexer, M3Parser, "definition", source),
};

// Each root context is registered with the ruleNames array of the parser that
// produced it. `__grammarRuleName` walks `ctx.parentCtx` up to the root to find
// the matching ruleNames. antlr4 contexts don't carry a `.parser` reference
// directly (the Lexer/Parser instance is short-lived once parse returns), so we
// keep a side table keyed by the root.
const ROOT_RULE_NAMES = new WeakMap();
function rememberRuleNames(root, ruleNames) {
    ROOT_RULE_NAMES.set(root, ruleNames);
}
function ruleNamesOf(ctx) {
    let cur = ctx;
    while (cur) {
        const r = ROOT_RULE_NAMES.get(cur);
        if (r) return r;
        cur = cur.parentCtx;
    }
    throw new Error("ruleNamesOf: context has no registered root (was it produced by __parseAntlr?)");
}

// ============================================================
// parseAntlr — drive ANTLR by grammar name; return the root context.
// ============================================================
export function __parseAntlr(source, grammarName, sourceId, lineOffset) {
    const grammar = GRAMMARS[grammarName];
    if (!grammar) {
        throw new Error("Unknown grammar: " + grammarName + " (known: " + Object.keys(GRAMMARS).join(", ") + ")");
    }
    return grammar(source, sourceId, Number(lineOffset));
}

// ============================================================
// Text + rule-name accessors.
// ============================================================
export function __getText(ctx) {
    return ctx.getText();
}

export function __grammarRuleName(ctx) {
    // Mirrors AntlrNodes.GrammarRuleNameNode: look up the rule name from the
    // enclosing parser's ruleNames table. We stashed it on the root context
    // at __parseAntlr time (antlr4 doesn't carry parser through ctx).
    return ruleNamesOf(ctx)[ctx.ruleIndex];
}

// ============================================================
// Navigation: top-level ParserRuleContext children + reflective accessors.
// ============================================================
export function __getTopLevelChildren(ctx) {
    const children = ctx.children;
    if (!children || children.length === 0) return [];
    const out = [];
    for (const c of children) {
        if (c instanceof ParserRuleContext) out.push(c);
    }
    return out;
}

// Reflective dispatch — same idea as AntlrNodes.invokeNamed(ctx, name): call
// the no-arg accessor method on the context. Generated antlr4 contexts expose
// one method per grammar rule + per token reference.
//
// Some ANTLR targets append `_` to rule names that clash with target-language
// reserved words. The JavaScript target generally does not, but keep the
// fallback so Pure-side parser-mappings code stays grammar-agnostic: try the
// bare name first, then fall back to `name_`.
function invokeNamed(ctx, name) {
    let fn = ctx[name];
    if (typeof fn !== "function") {
        fn = ctx[name + "_"];
    }
    if (typeof fn !== "function") {
        throw new Error("Antlr accessor '" + name + "' missing on " + (ctx.constructor && ctx.constructor.name));
    }
    return fn.call(ctx);
}

export function __getChild(ctx, name) {
    const r = invokeNamed(ctx, name);
    return r instanceof ParserRuleContext ? r : null;
}

export function __getChildren(ctx, name) {
    const r = invokeNamed(ctx, name);
    if (r == null) return [];
    if (Array.isArray(r)) return r;
    return [r];
}

export function __getTokenText(ctx, name) {
    const r = invokeNamed(ctx, name);
    return r instanceof TerminalNode ? r.getText() : null;
}

export function __getTokenTexts(ctx, name) {
    const r = invokeNamed(ctx, name);
    if (r == null) return [];
    if (Array.isArray(r)) {
        return r.map((o) => (o instanceof TerminalNode ? o.getText() : null)).filter((t) => t != null);
    }
    return r instanceof TerminalNode ? [r.getText()] : [];
}

export function __hasChild(ctx, name) {
    return invokeNamed(ctx, name) != null;
}

export function __hasToken(ctx, name) {
    return invokeNamed(ctx, name) instanceof TerminalNode;
}

// ============================================================
// Source position accessors — bigint-typed for Pure Integer compat.
// antlr4's `column` is 0-based; Pure expects 1-based start columns.
// ============================================================
export function __getStartLine(ctx) {
    return BigInt(ctx.start.line);
}

export function __getStartColumn(ctx) {
    return BigInt(ctx.start.column + 1);
}

export function __getStopLine(ctx) {
    return BigInt(ctx.stop.line);
}

export function __getStopColumn(ctx) {
    const stop = ctx.stop;
    return BigInt(stop.column + (stop.text ? stop.text.length : 0));
}

// ============================================================
// getChildTextAt — text of the i-th child (ParserRuleContext or TerminalNode).
// Used by the precedence ladder to read operator tokens.
// ============================================================
export function __getChildTextAt(ctx, idx) {
    const i = Number(idx);
    const children = ctx.children;
    if (!children || i >= children.length) return null;
    return children[i].getText();
}

// ============================================================
// stripTripleQuotesDedented — port of TripleStringStripper.strip (JEP-378).
// Drop the 3-char `'''` delimiters; strip the minimum-leading-whitespace
// column across continuation lines (the last line — where the closing `'''`
// sits — always contributes, even when blank).
// ============================================================
export function __stripTripleQuotesDedented(tripleQuoted) {
    const body = tripleQuoted.substring(3, tripleQuoted.length - 3);
    if (body.indexOf("\n") < 0) return body;

    const leadingNewline = body.charAt(0) === "\n";
    const firstNewline = leadingNewline ? -1 : body.indexOf("\n");
    const firstLine = leadingNewline ? null : body.substring(0, firstNewline);
    const rest = leadingNewline ? body.substring(1) : body.substring(firstNewline + 1);

    const continuationLines = rest.split("\n");

    let minLeading = Number.MAX_SAFE_INTEGER;
    for (let i = 0; i < continuationLines.length; i++) {
        const line = continuationLines[i];
        const isLast = i === continuationLines.length - 1;
        if (!isLast && isBlank(line)) continue;
        const n = countLeadingWhitespace(line);
        if (n < minLeading) minLeading = n;
    }
    if (minLeading === Number.MAX_SAFE_INTEGER) minLeading = 0;

    const out = [];
    if (!leadingNewline) out.push(firstLine);
    for (let i = 0; i < continuationLines.length; i++) {
        if (i > 0 || !leadingNewline) out.push("\n");
        out.push(stripUpTo(continuationLines[i], minLeading));
    }
    return out.join("");
}

function isBlank(s) {
    for (let i = 0; i < s.length; i++) {
        const c = s.charAt(i);
        if (c !== " " && c !== "\t") return false;
    }
    return true;
}

function countLeadingWhitespace(s) {
    let n = 0;
    while (n < s.length) {
        const c = s.charAt(n);
        if (c !== " " && c !== "\t") break;
        n++;
    }
    return n;
}

function stripUpTo(s, n) {
    const strip = Math.min(n, countLeadingWhitespace(s));
    return s.substring(strip);
}

// ============================================================
// computeFirstNonNewlineLine — Section body line-offset computation.
// Walks children, skipping EOF + whitespace-only nodes; returns the
// 0-based line offset of the first content node (adjusted by 1 when the
// host prepended a synthetic ###Pure header).
// ============================================================
export function __computeFirstNonNewlineLine(ctx, syntheticHeader) {
    if (ctx == null) return 0n;
    const childCount = ctx.getChildCount();
    for (let i = 0; i < childCount; i++) {
        const child = ctx.getChild(i);
        if (child instanceof TerminalNode && child.symbol.type === Token.EOF) continue;
        const text = child.getText();
        if (text != null && text.trim().length === 0) continue;
        const line =
            child instanceof TerminalNode
                ? child.symbol.line
                : child.start.line;
        let offset = line - 1;
        if (syntheticHeader) offset -= 1;
        return BigInt(offset);
    }
    return 0n;
}
