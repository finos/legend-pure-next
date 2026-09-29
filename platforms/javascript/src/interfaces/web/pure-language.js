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

// pure-language.js — the `pure` language for Monaco: a Monarch tokenizer, bracket
// handling, and the translation from this compiler's error strings to editor markers.
//
// The keywords are the M3 lexer's own (pure/specification/grammar/antlr/m3/M3Lexer.g4);
// keep them in step when the grammar gains one. Monarch is a syntax highlighter, not a
// parser — it never has to agree with the grammar about structure, only about words.

/**
 * The keywords, read from the grammar itself rather than copied here. M3Lexer.g4's keyword
 * rules are `NAME: 'literal';`, sometimes with alternatives (`AGGREGATION_TYPE: 'composite'
 * | 'shared' | 'none';`). Only alphabetic literals are keywords — `'->'`, `'{'`, `'#'` and
 * friends are punctuation the tokenizer handles structurally.
 *
 * Derived, not duplicated: a keyword added to the grammar highlights here without anyone
 * remembering to update a list.
 */
export function keywordsFromGrammar(grammarText) {
    const words = new Set(["true", "false"]); // literals, not lexer rules
    for (const m of grammarText.matchAll(/^[A-Z_]+\s*:([^;]*);/gm)) {
        for (const lit of m[1].matchAll(/'([A-Za-z][A-Za-z]*)'/g)) words.add(lit[1]);
    }
    return [...words];
}

/**
 * The primitive types, read from the loaded graph: every element stored as a
 * `PrimitiveType`. Also derived rather than listed — Binary and Byte were added to the
 * metamodel after this page was written, and a hand-kept list would have missed them.
 */
export function primitivesFromRegistry(registry) {
    return registry.elementKinds()
        .filter(([, kind]) => kind === "PrimitiveType")
        .map(([path]) => path.slice(path.lastIndexOf("::") + 2));
}

export function registerPureLanguage(monaco, { keywords, primitives }) {
    if (monaco.languages.getLanguages().some((l) => l.id === "pure")) return;
    monaco.languages.register({ id: "pure", extensions: [".pure"], aliases: ["Pure"] });

    monaco.languages.setLanguageConfiguration("pure", {
        comments: { lineComment: "//", blockComment: ["/*", "*/"] },
        brackets: [["{", "}"], ["[", "]"], ["(", ")"]],
        autoClosingPairs: [
            { open: "{", close: "}" }, { open: "[", close: "]" }, { open: "(", close: ")" },
            { open: "'", close: "'", notIn: ["string", "comment"] },
        ],
        surroundingPairs: [{ open: "{", close: "}" }, { open: "[", close: "]" }, { open: "(", close: ")" }],
    });

    monaco.languages.setMonarchTokensProvider("pure", {
        defaultToken: "",
        keywords,
        primitives,
        // `->` and `|` carry most of Pure's meaning, so they are called out; `^` opens an
        // instance, `@` a cast's type, `::` a path separator.
        operators: ["->", "|", "^", "@", "==", "!=", "<=", ">=", "<", ">", "+", "-", "*", "/", "&&", "||", "=", ":", ";", ","],
        tokenizer: {
            root: [
                // Stereotypes and tagged values: <<PCT.test>>, {doc.doc = '...'}
                [/<<[^>]*>>/, "annotation"],
                // Multiplicity: [1] [*] [0..1] [1..*] [m]
                [/\[\s*(\d+(\.\.(\d+|\*))?|\*|[a-z])\s*\]/, "number.multiplicity"],
                // A qualified path — highlighted whole so meta::pure::… reads as one thing
                [/[a-zA-Z_][\w]*(::[a-zA-Z_][\w]*)+/, "type.identifier"],
                [/\$[a-zA-Z_]\w*/, "variable"],
                [/[a-zA-Z_]\w*/, {
                    cases: {
                        "@keywords": "keyword",
                        "@primitives": "type",
                        "@default": "identifier",
                    },
                }],
                { include: "@whitespace" },
                [/'''/, { token: "string", next: "@tripleString" }],
                [/'/, { token: "string", next: "@string" }],
                [/\d+\.\d+([eE][+-]?\d+)?/, "number.float"],
                [/\d+/, "number"],
                [/[{}()\[\]]/, "@brackets"],
                [/->|\|\||&&|[=!<>]=|[-+*/<>|^@=:;,.]/, {
                    cases: { "@operators": "operator", "@default": "" },
                }],
            ],
            whitespace: [
                [/[ \t\r\n]+/, ""],
                [/\/\*/, { token: "comment", next: "@comment" }],
                [/\/\/.*$/, "comment"],
            ],
            comment: [
                [/[^/*]+/, "comment"],
                [/\*\//, { token: "comment", next: "@pop" }],
                [/[/*]/, "comment"],
            ],
            // Pure strings are single-quoted; '' is an escaped quote.
            string: [
                [/[^'\\]+/, "string"],
                [/\\./, "string.escape"],
                [/''/, "string"],
                [/'/, { token: "string", next: "@pop" }],
            ],
            tripleString: [
                [/[^']+/, "string"],
                [/'''/, { token: "string", next: "@pop" }],
                [/'/, "string"],
            ],
        },
    });
}

/**
 * Compile errors carry "... (at <id>:<sl>c<sc>-<el>c<ec>)" with 1-based lines and columns
 * and an INCLUSIVE end column; Monaco's end column is exclusive, hence the +1. An error
 * with no position still becomes a marker, on line 1, so nothing is lost silently.
 */
export function compileErrorMarkers(monaco, errors) {
    const re = /\(at [^:)]+:(\d+)c(\d+)-(\d+)c(\d+)\)/g;
    const markers = [];
    for (const err of errors) {
        let m, found = false;
        re.lastIndex = 0;
        while ((m = re.exec(err))) {
            found = true;
            markers.push({
                severity: monaco.MarkerSeverity.Error,
                message: err,
                startLineNumber: +m[1], startColumn: +m[2],
                endLineNumber: +m[3], endColumn: +m[4] + 1,
            });
        }
        if (!found) {
            markers.push({
                severity: monaco.MarkerSeverity.Error, message: err,
                startLineNumber: 1, startColumn: 1, endLineNumber: 1, endColumn: 2,
            });
        }
    }
    return markers;
}

/** Parse errors give a 1-based line and a 0-based column; mark to the end of that token. */
export function parseErrorMarkers(monaco, parseErrors, model) {
    return parseErrors.map(({ line, column, message }) => {
        const text = model.getLineContent(Math.min(Math.max(line, 1), model.getLineCount()));
        let end = column;
        while (end < text.length && !/\s/.test(text[end])) end++;
        return {
            severity: monaco.MarkerSeverity.Error,
            message: message || "Parse error",
            startLineNumber: line, startColumn: column + 1,
            endLineNumber: line, endColumn: Math.max(end + 1, column + 2),
        };
    });
}
