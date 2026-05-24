parser grammar TopParser;

options
{
    tokenVocab = TopLexer;
}

// A document is a sequence of sections.
// If no ### header is present, the Java layer prepends ###Pure.
document
    : section* EOF
    ;

// A named section: ###SectionName followed by imports and content
// until the next section header or EOF.
section
    : SECTION_HEADER NEWLINE* importStatement* sectionContent?
    ;

// import path::to::package::*;
importStatement
    : IMPORT_STATEMENT SEMICOLON NEWLINE*
    ;

// Section content is any sequence of non-header, non-import tokens.
sectionContent
    : contentToken+
    ;

// Any token that is not a section header constitutes content.
// STRING_CONTENT (a `'...'` literal) and STRING_TRIPLE_CONTENT (a `'''...'''`
// multi-line literal) are included so their raw text — possibly containing
// `/*...*/` substrings — flows through to the per-section lexer without the
// top-level BLOCK_COMMENT skip rule eating into it.
contentToken
    : CONTENT_LINE
    | HASH
    | SLASH
    | NEWLINE
    | SEMICOLON
    | STRING_TRIPLE_CONTENT
    | STRING_CONTENT
    | QUOTE
    ;

