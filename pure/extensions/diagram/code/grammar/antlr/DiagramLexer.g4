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

lexer grammar DiagramLexer;

// ==========================================================================
// The `###Diagram` section: class diagrams, as laid out on a canvas.
//
// Token order matters. The keywords are declared before QUALIFIED_NAME so a
// view's type name wins over the identifier rule, and FLOAT before INTEGER so
// `1.0` is one token rather than two.
// ==========================================================================

DIAGRAM             : 'Diagram';
TYPE_VIEW           : 'TypeView';
PROPERTY_VIEW       : 'PropertyView';
ASSOCIATION_VIEW    : 'AssociationView';
GENERALIZATION_VIEW : 'GeneralizationView';

TRUE  : 'true';
FALSE : 'false';
// `attributeStereotype=none` — a keyword, not an identifier, so a property
// named `none` cannot be confused for a value.
NONE  : 'none';

BRACE_OPEN    : '{';
BRACE_CLOSE   : '}';
PAREN_OPEN    : '(';
PAREN_CLOSE   : ')';
BRACKET_OPEN  : '[';
BRACKET_CLOSE : ']';
COMMA         : ',';
EQUAL         : '=';

// `#FFFFCC`, as the colour of a view.
COLOR : '#' [0-9a-fA-F]+;

// Declared before INTEGER: `-1.0` must not lex as `-1` followed by `.0`.
FLOAT   : '-'? [0-9]+ '.' [0-9]+;
INTEGER : '-'? [0-9]+;

// `tv_a`, `m::Person`, and `m::Person.firm` — a property view names its property
// with a trailing dot segment, so `.` joins segments just as `::` does.
QUALIFIED_NAME : [A-Za-z_] [A-Za-z0-9_]* (('::' | '.') [A-Za-z_] [A-Za-z0-9_]*)*;

LINE_COMMENT  : '//' ~[\r\n]* -> skip;
BLOCK_COMMENT : '/*' .*? '*/' -> skip;
WS            : [ \t\r\n]+ -> skip;
