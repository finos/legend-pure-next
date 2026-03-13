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
    : '###' IDENTIFIER
    ;

IMPORT_STATEMENT
    : 'import' [ \t]+ IDENTIFIER ('::' IDENTIFIER)* '::' '*'
    ;

SEMICOLON
    : ';'
    ;

LINE_COMMENT
    : '//' ~[\r\n]* -> skip
    ;

BLOCK_COMMENT
    : '/*' .*? '*/' -> skip
    ;

// Any text (line) that does not start with ### or //
CONTENT_LINE
    : ~[;/\r\n#]+
    ;

// A '/' that is not part of a comment
SLASH
    : '/'
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
