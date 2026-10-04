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

parser grammar DiagramParser;

options
{
    tokenVocab = DiagramLexer;
}

// A section holds any number of diagrams; the `###Diagram` header itself is
// consumed by the top-level grammar before this one is handed the body.
section
    : diagram* EOF
    ;

// `Diagram m::D(width=10.0, height=10.0) { … }` — the dimension clause is
// optional, and a file that never had one does not acquire one.
// The view alternatives are listed here rather than behind a `view` rule: ANTLR gives a
// context an accessor per rule it directly contains, so inlining them lets the Pure mapping
// ask a diagram for its `typeView`s straight out, instead of walking a `view` wrapper whose
// only job would be to hold one child.
diagram
    : DIAGRAM name=QUALIFIED_NAME dimension? BRACE_OPEN
        (typeView | propertyView | associationView | generalizationView)*
      BRACE_CLOSE
    ;

dimension
    : PAREN_OPEN propertyList? PAREN_CLOSE
    ;

typeView
    : TYPE_VIEW id=QUALIFIED_NAME PAREN_OPEN propertyList? PAREN_CLOSE
    ;

propertyView
    : PROPERTY_VIEW id=QUALIFIED_NAME PAREN_OPEN propertyList? PAREN_CLOSE
    ;

associationView
    : ASSOCIATION_VIEW id=QUALIFIED_NAME PAREN_OPEN propertyList? PAREN_CLOSE
    ;

generalizationView
    : GENERALIZATION_VIEW id=QUALIFIED_NAME PAREN_OPEN propertyList? PAREN_CLOSE
    ;

propertyList
    : property (COMMA property)*
    ;

// Every view is a bag of `key=value`; which keys are meaningful is the
// metamodel's business, not the grammar's, so an unknown key parses and is
// reported rather than failing the file.
property
    : key=QUALIFIED_NAME EQUAL value
    ;

value
    : point
    | pointList
    | COLOR
    | FLOAT
    | INTEGER
    | TRUE
    | FALSE
    | NONE
    | QUALIFIED_NAME
    ;

point
    : PAREN_OPEN x=number COMMA y=number PAREN_CLOSE
    ;

pointList
    : BRACKET_OPEN (point (COMMA point)*)? BRACKET_CLOSE
    ;

number
    : FLOAT
    | INTEGER
    ;
