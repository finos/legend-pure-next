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

SECTION_HEADER
    : {getCharPositionInLine() == 0}? '###' IDENTIFIER
    ;

IMPORT_STATEMENT
    : 'import' [ \t]+ IDENTIFIER ('::' IDENTIFIER)* '::' '*'
    ;

SEMICOLON
    : ';'
    ;

// String-literal token — declared BEFORE the comment rules so longest-match
// preserves `'/**...*/'` as a single STRING_CONTENT token rather than letting
// `BLOCK_COMMENT` skip the `/**...*/` substring inside it (which would lose
// that text from `contentToken.getText()` reassembly and feed an empty
// string to the per-section M3Lexer).
STRING_CONTENT
    : '\'' ( '\\' . | ~['\r\n\\] )* '\''
    ;

LINE_COMMENT
    : '//' ~[\r\n]* -> skip
    ;

BLOCK_COMMENT
    : '/*' .*? '*/' -> skip
    ;

// Any text (line) that does not start with ###, //, or a string literal.
// `'` is excluded so the STRING_CONTENT rule above can start matching at
// the opening quote — otherwise CONTENT_LINE would greedy-eat the `'` and
// the lexer would never see the start of a string literal.
CONTENT_LINE
    : ~[;/\r\n#']+
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
