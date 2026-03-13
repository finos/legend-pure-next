lexer grammar M3Lexer;



AGGREGATION_TYPE: 'composite' | 'shared' | 'none';

DSL_TEXT: '#' .*?  '#';
AS: 'as';
ALL: 'all';
ALL_VERSIONS: 'allVersions';
ALL_VERSIONS_IN_RANGE: 'allVersionsInRange';
ARROW: '->';
CURLY_BRACKET_OPEN: '{';
CURLY_BRACKET_CLOSE: '}';
BRACKET_OPEN: '[';
BRACKET_CLOSE: ']';
GROUP_OPEN: '(';
GROUP_CLOSE: ')';
COLON: ':';
DOT: '.';
DOLLAR: '$';
DOTDOT: '..';
END_LINE: ';';
NEW_SYMBOL: '^';
PIPE: '|';
TILDE: '~';
QUESTION: '?';
PATH_SEPARATOR: PathSeparator;

STRING: String;
BOOLEAN: Boolean;
TRUE: True;
FALSE: False;
INTEGER: Integer;
FLOAT: Float;
DECIMAL: Decimal;
DATE: Date;
STRICTTIME: StrictTime;
LATEST_DATE: '%latest';

AND: '&&';
OR: '||';
NOT: '!';
COMMA: ',';
EQUAL: '=';TEST_EQUAL: '==';
TEST_NOT_EQUAL: '!=';

NATIVE : 'native';
FUNCTION: 'function';
IMPORT: 'import';
EXTENDS: 'extends';

PRIMITIVE: 'Primitive';
CLASS: 'Class';
ASSOCIATION: 'Association';
PROFILE: 'Profile';
ENUM: 'Enum';
MEASURE: 'Measure';
STEREOTYPES: 'stereotypes';
TAGS: 'tags';
LET: 'let';
ENFORCEMENT_LEVEL: 'Error' | 'Warn';
VALID_STRING: ValidString;





CONSTRAINT_OWNER: '~owner';
CONSTRAINT_EXTERNAL_ID: '~externalId';
CONSTRAINT_FUNCTION: '~function';
CONSTRAINT_ENFORCEMENT: '~enforcementLevel';
CONSTRAINT_MESSAGE: '~message';

WHITESPACE:   Whitespace        ->  skip ;
COMMENT:      Comment           -> skip  ;
LINE_COMMENT: LineComment       -> skip  ;

AT: '@';
SUBSET: '⊆';
PLUS: '+';
STAR: '*';
MINUS: '-';
DIVIDE: '/';
LESSTHAN: '<';
LESSTHANEQUAL: '<=';
GREATERTHAN: '>';
GREATERTHANEQUAL: '>=';

// --- Inlined from M4Fragment.g4 ---

fragment PathSeparator: '::'
;
fragment String: ('\'' ( EscSeq | ~['\r\n\\] )*  '\'' )
;
fragment Boolean: True | False
;
fragment True: 'true'
;
fragment False: 'false'
;
fragment Integer: (Digit)+
;
fragment Float: (Digit)* '.' (Digit)+ ( ('e' | 'E') ('+' | '-')? (Digit)+)? ('f' | 'F')?
;
fragment Decimal: ((Digit)* '.' (Digit)+ | (Digit)+) ( ('e' | 'E') ('+' | '-')? (Digit)+)? ('d' | 'D')
;
fragment Date: '%' ('-')? (Digit)+ ('-'(Digit)+ ('-'(Digit)+ ('T' DateTime TimeZone?)?)?)?
;
fragment StrictTime: '%' DateTime
;
fragment ValidString: (Letter | Digit | '_' ) (Letter | Digit | '_' | '$')*
;

fragment DateTime : (Digit)+ (':'(Digit)+ (':'(Digit)+ ('.'(Digit)+)?)?)?
;
fragment TimeZone: (('+' | '-')(Digit)(Digit)(Digit)(Digit))
;
fragment Assign : ([ \r\t\n])* '='
;
fragment EscSeq
	:	Esc
		( [btnfr"'\\]
		| UnicodeEsc
		| .
		| EOF
		)
;
fragment EscAny
	:	Esc .
;
fragment UnicodeEsc
	:	'u' (HexDigit (HexDigit (HexDigit HexDigit?)?)?)?
;
fragment Esc : '\\'
;
fragment Letter : [A-Za-z]
;
fragment Digit : [0-9]
;
fragment HexDigit : [0-9a-fA-F]
;
fragment Whitespace:   [ \r\t\n]+
;
fragment Comment:      '/*' .*? '*/'
;
fragment LineComment: '//' ~[\r\n]*
;