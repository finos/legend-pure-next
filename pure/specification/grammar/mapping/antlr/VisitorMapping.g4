// ANTLR-visitor mapping DSL.
//
// Describes how to translate an ANTLR parser's contexts into build<RuleName>(ctx)
// methods that construct protocol AST nodes. A mapping file is a sequence of
// `rule` (grammar-rule visitor) and `helper` (ordinary build method) declarations.
// Each declaration body is a sequence of statements: let / post / return / alt /
// fold blocks / etc. The expression sub-language supports $ctx-rooted paths,
// $it-rooted paths inside folds, and ordinary Java-like chained calls
// (primary . member ( args )?)*.

grammar VisitorMapping;

// === Top level ==========================================================

dsl: declaration* EOF;

// Alternate entry point used by the emitter to re-parse a stored expression text.
exprEntry: expression EOF ;
predEntry: predicate EOF ;

declaration: ruleDecl | helperDecl;

ruleDecl
  : 'rule' name=IDENT ('as' returnType=typeName)? '{' bodyStmt* '}'
  ;

helperDecl
  : 'helper' name=IDENT '(' helperParams ')' ('as' returnType=typeName)? '{' bodyStmt* '}'
  ;

helperParams: helperParam (',' helperParam)* ;
helperParam: type=typeName IDENT ;

// === Body statements ====================================================

bodyStmt
  : letStmt
  | setStmt
  | postStmt
  | returnStmt
  | altWhen
  | altElse
  | elseErrorStmt
  | leftFoldStmt
  | chainFoldStmt
  | growListStmt
  | paramStmt
  | methodStmt
  ;

letStmt: 'let' type=typeName name=IDENT '=' value=expression ;
setStmt: 'set' name=IDENT '=' value=expression ;

postStmt
  : 'post' 'when' guard=predicate '=>' stmt=expression   # conditionalPost
  | 'post' stmt=expression                               # unconditionalPost
  ;

returnStmt: 'return' value=expression ;

altWhen: 'alt' 'when' guard=predicate '{' altBody* '}' ;
altElse: 'alt' 'else' '{' altBody* '}' ;

altBody: returnStmt | letStmt | stepStmt | yieldStmt | postStmt ;

elseErrorStmt: 'else' 'error' '(' msg=STRING ')' ;

paramStmt: 'param' type=typeName name=IDENT ;
methodStmt: 'method' name=IDENT ;

// === Folds ==============================================================

leftFoldStmt: 'left_fold' 'over' over=ctxPath '{' leftFoldBody* '}' ;
leftFoldBody
  : letStmt
  | stepStmt
  | leftFoldAltWhen
  | altElse
  ;
leftFoldAltWhen: 'alt' 'when' '$tok' '=' tokName=IDENT '{' altBody* '}' ;
stepStmt: 'step' value=expression ;

chainFoldStmt: 'chain_fold' 'from' seed=expression 'over' over=ctxPath '{' chainFoldBody* '}' ;
chainFoldBody: altWhen | elseStepStmt ;
elseStepStmt: 'else' 'step' value=expression ;

growListStmt: 'grow_list' 'over' over=ctxPath '{' growListBody* '}' ;
growListBody: altWhen | altElse ;
yieldStmt: 'yield' value=expression ;

// === Predicates and expressions =========================================
// A predicate is one or more expressions ANDed together. Each clause can be:
//   * a $ctx-rooted or $it-rooted path  → emit a null-check on the path
//   * any other boolean-valued expression (notEmpty, anyHas, hasPackagePrefix, etc.)

// A predicate is just any boolean-valued expression; multi-clause AND is the `&&`
// binary operator in the expression grammar below.
predicate: expression ;

// A single expression. Uses the standard primary + chained-segment shape so
// `$ctx.foo.bar(args)`, `acc.method()`, and `ListAdapter.adapt(...)` all parse
// the same way. The visitor distinguishes path-style (segment with no parens)
// from method-style (segment with parens) when generating Java.

expression
  : primary chainSegment*                                  # chainedPrimary
  | '-' expression                                         # negExpr
  | left=expression op=('*' | '/') right=expression        # mulDivExpr
  | left=expression op=('+' | '-') right=expression        # addSubExpr
  | left=expression '&&' right=expression                  # andExpr
  ;

chainSegment
  : '.' member=IDENT '(' argList? ')'   # callSeg
  | '.' member=IDENT                    # pathSeg
  ;

primary
  : INTEGER                                          # intPrim
  | STRING                                           # stringPrim
  | 'true'                                           # truePrim
  | 'false'                                          # falsePrim
  | '$ctx'                                           # ctxBarePrim
  | '$tok'                                           # tokPrim
  | '$rhsCtx'                                        # rhsCtxPrim
  | '$it'                                            # itBarePrim
  | 'this' '::' member=IDENT                         # methodRefPrim
  | qualifier=IDENT '::' member=IDENT                # qualifiedMethodRefPrim
  | func=IDENT '(' argList? ')'                      # callPrim
  | name=IDENT                                       # identPrim
  | '(' inner=expression ')'                         # parenPrim
  | '(' cast=typeName ')' '(' castInner=expression ')'  # castPrim
  ;

// $ctx-rooted paths used as fold-over targets and head-of-rule head decls.
// These don't allow method-call segments — they're always pure paths.
ctxPath: '$ctx' '.' IDENT ('.' IDENT)* ;

argList: arg (',' arg)* ;
arg
  : namedArg
  | expression
  ;
namedArg: name=IDENT '=' value=expression ;

// Types: identifier path with optional generic arguments.
typeName
  : IDENT typeArgs?
  | IDENT '.' IDENT typeArgs?
  ;
typeArgs: '<' typeName (',' typeName)* '>' ;

// === Lexer ==============================================================

IDENT: [a-zA-Z_][a-zA-Z0-9_]* ;
STRING: '"' ( ~["\\] | '\\' . )* '"' ;
INTEGER: [0-9]+ ;

COMMENT: '#' ~[\r\n]* -> skip ;
WS: [ \t\r\n]+ -> skip ;
