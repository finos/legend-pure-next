lexer grammar TopLexer;

// ==========================================================================
// Island grammar for section-based dispatch.
//
// Recognizes ###SectionName headers, import statements, and captures
// everything between headers as raw content text.
//
// Tokens produced:
//   SECTION_HEADER  – the ###Name header (getText() = "###Name")
//   IMPORT          – the 'import' keyword
//   IMPORT_PATH     – an import path like 'protocol::support::*'
//   SEMICOLON       – statement terminator ';'
//   CONTENT_LINE    – any other non-newline text
//   HASH            – a '#' that is not part of a section header
//   NEWLINE         – line break
// ==========================================================================

// Section header — '###Name' at start of input or after a NEWLINE.
// The lexer disambiguates by declaration order: at line start, both SECTION_HEADER
// and CONTENT_LINE match `###Pure` with equal length (CONTENT_LINE's char set
// includes `#`), but SECTION_HEADER wins as the earlier-declared rule. Mid-line
// `###` is consumed by CONTENT_LINE's longest-match before SECTION_HEADER ever
// gets to try. This replaces the target-specific semantic predicate
// `{getCharPositionInLine() == 0}?` so the same grammar works for Java and
// JavaScript codegen without an inline-action divergence.
SECTION_HEADER
    : '###' IDENTIFIER
    ;

IMPORT_STATEMENT
    : 'import' [ \t]+ IDENTIFIER ('::' IDENTIFIER)* '::' '*'
    ;

SEMICOLON
    : ';'
    ;

// String literals — declared BEFORE the comment rules so longest-match
// preserves `'/**...*/'` as a single STRING_CONTENT token rather than letting
// `BLOCK_COMMENT` skip the `/**...*/` substring inside it (which would lose
// that text from `contentToken.getText()` reassembly and feed an empty
// string to the per-section M3Lexer).
//
// Triple-quoted multi-line literals (`'''...'''`) need the same protection:
// without an explicit rule, a `/**...*/` block on a line inside the literal
// would be eaten by BLOCK_COMMENT. Declared before STRING_CONTENT so ANTLR's
// longest-match prefers it when the input starts with three quotes.
STRING_TRIPLE_CONTENT
    : '\'\'\'' .*? '\'\'\''
    ;

STRING_CONTENT
    : '\'' ( '\\' . | ~['\r\n\\] )* '\''
    ;

LINE_COMMENT
    : '//' ~[\r\n]* -> skip
    ;

BLOCK_COMMENT
    : '/*' .*? '*/' -> skip
    ;

// Any text (line) that doesn't start with `///`, a string literal, or end at
// `;`/`\n`. `'` is excluded so STRING_CONTENT can match the opening quote —
// otherwise CONTENT_LINE would greedy-eat the `'` and the lexer would never
// see the start of a string literal. `#` is INCLUDED in the char set (unlike
// the historical exclusion) so mid-line `###` falls into CONTENT_LINE rather
// than starting a SECTION_HEADER — see SECTION_HEADER's doc above for the
// declaration-order tie-breaking that lets a line-start `###Name` still win.
CONTENT_LINE
    : ~[;/\r\n']+
    ;

// A '/' that is not part of a comment
SLASH
    : '/'
    ;

// A stray `'` that isn't paired into a STRING_CONTENT — keeps lexing from
// erroring on malformed input; the inner M3Lexer will produce the real
// diagnostic if the string isn't actually closed.
QUOTE
    : '\''
    ;

// A '#' that is not part of '###Identifier'
HASH
    : '#'
    ;

NEWLINE
    : '\r'? '\n'
    ;

fragment IDENTIFIER
    : [A-Za-z_] [A-Za-z0-9_]*
    ;
