// Copyright 2026 Goldman Sachs
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

parser grammar TopParser;

options
{
    tokenVocab = TopLexer;
}

// A document is the text before its first header, then its sections.
document
    : implicitSection section* EOF
    ;

// Everything before the first ###header: a section with no header. In a file that
// names no section it is the whole file, which is Pure. Above a header it may be only
// comments and blank lines — a licence header — and as the lexer skips comments that
// leaves NEWLINEs alone: the builders then drop it rather than make an empty section.
implicitSection
    : NEWLINE* importStatement* sectionContent?
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

