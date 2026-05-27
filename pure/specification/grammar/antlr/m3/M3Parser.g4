parser grammar M3Parser;

options
{
    tokenVocab = M3Lexer;
}


identifier: VALID_STRING | CLASS | PRIMITIVE | FUNCTION | NATIVE | EXTENDS | PROFILE | ASSOCIATION | ENUM | STEREOTYPES | TAGS | IMPORT | LET | AGGREGATION_TYPE | PATH_SEPARATOR | AS | ALL | ENFORCEMENT_LEVEL
;

qualifiedName: packagePath? identifier
;

packagePath: (identifier PATH_SEPARATOR)+
;

definition:
            (
                  profile
                | classDefinition
                | primitiveDefinition
                | association
                | enumDefinition
                | nativeFunction
                | functionDefinition
            )*
            EOF
;

classDefinition: CLASS stereotypes? taggedValues? qualifiedName typeVariableParameters? typeParametersWithVarianceAndMultiplicityParameters?
              ( EXTENDS type (COMMA type)* )?
              constraints?
              classBody
;

typeVariableParameters: GROUP_OPEN (functionVariableExpression (COMMA functionVariableExpression)*)? GROUP_CLOSE
;

primitiveDefinition: PRIMITIVE stereotypes? taggedValues? qualifiedName typeVariableParameters? EXTENDS type
                     constraints?
;



classBody: CURLY_BRACKET_OPEN
                properties
           CURLY_BRACKET_CLOSE
;

properties: ( property | qualifiedProperty )*
;

propertyName: (identifier | STRING)
;

property: stereotypes? taggedValues? aggregation? propertyName COLON propertyReturnType defaultValue? END_LINE
;

qualifiedProperty:  stereotypes? taggedValues? identifier qualifiedPropertyBody COLON propertyReturnType  END_LINE
;

qualifiedPropertyBody:
                    GROUP_OPEN
                        (functionVariableExpression (COMMA functionVariableExpression)*)?
                    GROUP_CLOSE
                    CURLY_BRACKET_OPEN
                        codeBlock
                    CURLY_BRACKET_CLOSE
;

association: ASSOCIATION stereotypes? taggedValues? qualifiedName associationBody
;

associationBody:
                 CURLY_BRACKET_OPEN
                     properties
                 CURLY_BRACKET_CLOSE
;

enumDefinition: ENUM stereotypes? taggedValues? qualifiedName
      CURLY_BRACKET_OPEN
         enumValue (COMMA enumValue)*
      CURLY_BRACKET_CLOSE
;

enumValue: stereotypes? taggedValues? identifier
;

nativeFunction: NATIVE FUNCTION stereotypes? taggedValues? qualifiedName typeAndMultiplicityParameters? functionTypeSignature END_LINE
;

functionTypeSignature: GROUP_OPEN (functionVariableExpression (COMMA functionVariableExpression)*)? GROUP_CLOSE COLON type multiplicity
;

functionDefinition: FUNCTION stereotypes? taggedValues? qualifiedName typeAndMultiplicityParameters? functionTypeSignature
          constraints?
          CURLY_BRACKET_OPEN
             codeBlock
          CURLY_BRACKET_CLOSE
;



nonArrowOrEqualExpression:
        (
            sliceExpression
            |
            atomicExpression
            |
            notExpression
            |
            signedExpression
            |
            expressionsArray
            |
            ( GROUP_OPEN combinedExpression GROUP_CLOSE )

        )
;
expression:
        (
            nonArrowOrEqualExpression
            (
                propertyOrFunctionExpression
            )*
        )
;
simpleExpression:
        (
            nonArrowOrEqualExpression
            (
                propertyOrFunctionExpression
            )*
        )
;



propertyReturnType: type multiplicity
;

stereotypes: LESSTHAN LESSTHAN stereotype (COMMA stereotype)* GREATERTHAN GREATERTHAN
;

stereotype: qualifiedName DOT identifier
;

taggedValues: CURLY_BRACKET_OPEN taggedValue (COMMA taggedValue)* CURLY_BRACKET_CLOSE
;

taggedValue: qualifiedName DOT identifier EQUAL STRING (PLUS STRING)*
;

defaultValue: EQUAL combinedExpression
;

profile: PROFILE stereotypes? taggedValues? qualifiedName
         CURLY_BRACKET_OPEN
            stereotypeDefinitions?
            tagDefinitions?
         CURLY_BRACKET_CLOSE
;

stereotypeDefinitions: (STEREOTYPES COLON BRACKET_OPEN identifier (COMMA identifier)* BRACKET_CLOSE END_LINE)
;

tagDefinitions: (TAGS COLON BRACKET_OPEN identifier (COMMA identifier)* BRACKET_CLOSE END_LINE)
;

codeBlock: programLine (END_LINE (programLine END_LINE)*)?
;

programLine: combinedExpression | letExpression
;

letExpression: LET identifier EQUAL combinedExpression
;

// Operator precedence is encoded in the grammar as a rule ladder
// (lowest precedence at the top, highest at the bottom). Each level
// is left-associative; the visitor walks left-to-right and folds.
// Order: || < && < ==,!= < <,<=,>,>= < +,- < *,/ < unary/property/atomic
combinedExpression: orExpression
;

orExpression: andExpression (OR andExpression)*
;

andExpression: equalityExpression (AND equalityExpression)*
;

equalityExpression: relationalExpression ((TEST_EQUAL | TEST_NOT_EQUAL) relationalExpression)*
;

relationalExpression: additiveExpression ((LESSTHAN | LESSTHANEQUAL | GREATERTHAN | GREATERTHANEQUAL) additiveExpression)*
;

additiveExpression: multiplicativeExpression ((PLUS | MINUS) multiplicativeExpression)*
;

multiplicativeExpression: expression ((STAR | DIVIDE) expression)*
;

expressionsArray: BRACKET_OPEN ( combinedExpression (COMMA combinedExpression)* )? BRACKET_CLOSE
;

propertyOrFunctionExpression: propertyExpression | functionExpression
;

propertyExpression: DOT propertyName (functionExpressionLatestMilestoningDateParameter | functionExpressionParameters)?
;

functionExpression: arrowStep+
;

arrowStep: ARROW qualifiedName functionExpressionParameters
;

functionExpressionLatestMilestoningDateParameter: GROUP_OPEN LATEST_DATE (COMMA LATEST_DATE)? GROUP_CLOSE
;

functionExpressionParameters: GROUP_OPEN (combinedExpression (COMMA combinedExpression)*)? GROUP_CLOSE
;

atomicExpression:
                 dsl
                 | instanceLiteralToken
                 | expressionInstance
                 | variable
                 | columnBuilders
                 | parentReference
                 | (AT (type? (PIPE multiplicityArgument)? | multiplicity))
                 | anyLambda
                 | instanceReference
;

columnBuilders: TILDE (oneColSpec | (BRACKET_OPEN (oneColSpec(COMMA oneColSpec)*)? BRACKET_CLOSE))
;
oneColSpec: columnName (COLON (type multiplicity? | anyLambda) extraFunction?)?
;
extraFunction: (COLON anyLambda)
;

instanceReference: (PATH_SEPARATOR | qualifiedName) allOrFunction?
;

anyLambda : lambdaPipe | lambdaFunction | lambdaParam lambdaPipe
;

lambdaFunction: CURLY_BRACKET_OPEN (lambdaParam (COMMA lambdaParam)* )? lambdaPipe CURLY_BRACKET_CLOSE
;

variable: DOLLAR identifier
;

allOrFunction:  allFunction
              | allVersionsFunction
              | allVersionsInRangeFunction
              | allFunctionWithMilestoning
              | functionExpressionParameters
;

allFunction: DOT ALL GROUP_OPEN GROUP_CLOSE
;

allVersionsFunction: DOT ALL_VERSIONS GROUP_OPEN GROUP_CLOSE
;

allVersionsInRangeFunction: DOT ALL_VERSIONS_IN_RANGE GROUP_OPEN buildMilestoningVariableExpression COMMA buildMilestoningVariableExpression GROUP_CLOSE
;

allFunctionWithMilestoning: DOT ALL GROUP_OPEN buildMilestoningVariableExpression (COMMA buildMilestoningVariableExpression)? GROUP_CLOSE
;

buildMilestoningVariableExpression: LATEST_DATE | DATE | variable
;

expressionInstance: NEW_SYMBOL
                          (variable | qualifiedName | combinedExpression)
                          (LESSTHAN typeArguments? (PIPE multiplicityArguments)? GREATERTHAN)? (identifier)?
                          (typeVariableValues)?
                          GROUP_OPEN
                              expressionInstanceParserPropertyAssignment? (COMMA expressionInstanceParserPropertyAssignment)*
                          GROUP_CLOSE
;

expressionInstanceRightSide: expressionInstanceAtomicRightSide
;

parentReference: TILDE parentReferenceStep*
;

// Each step after the leading ~ is either another tilde (one more parent
// level) or a property name. Splitting them as distinct alt branches lets
// the DSL fold uniformly: VariableExpression(name="~") as the seed, and a
// DotApplication wrapping the accumulator per step — functionName="~" for
// the parent step, functionName=propertyName.text for the property step.
parentReferenceStep: DOT (TILDE | propertyName)
;

// parentReference is now an atomicExpression alternative so chains like
// `~.foo->map(...)` parse naturally through combinedExpression. The plain
// `~`, `~.~`, `~.foo` forms still parse, just via the combinedExpression branch.
expressionInstanceAtomicRightSide: combinedExpression | expressionInstance | qualifiedName
;

// Deep-path syntax (e.g. `firm.legalName = 'X'`) is intentionally not part
// of the grammar. To set a nested value, instantiate it inline at the top
// level: `firm = ^LA_Firm(legalName='X', ...)`.
expressionInstanceParserPropertyAssignment: propertyName PLUS? EQUAL expressionInstanceRightSide
;

sliceExpression: BRACKET_OPEN ( (COLON expression) | (expression COLON expression) |  (expression COLON expression COLON expression) ) BRACKET_CLOSE
;

constraints: BRACKET_OPEN  constraint (COMMA constraint)* BRACKET_CLOSE
;

constraint:  simpleConstraint | complexConstraint
;

simpleConstraint: constraintId? combinedExpression
;

complexConstraint:  VALID_STRING
                    GROUP_OPEN
                        constraintOwner?
                        constraintExternalId?
                        constraintFunction
                        constraintEnforcementLevel?
                        constraintMessage?
                    GROUP_CLOSE
;

constraintOwner: CONSTRAINT_OWNER COLON VALID_STRING
;

constraintExternalId: CONSTRAINT_EXTERNAL_ID COLON STRING
;

constraintFunction: CONSTRAINT_FUNCTION COLON combinedExpression
;

constraintEnforcementLevel: CONSTRAINT_ENFORCEMENT COLON ENFORCEMENT_LEVEL
;

constraintMessage: CONSTRAINT_MESSAGE COLON combinedExpression
;

constraintId : VALID_STRING COLON
;

notExpression: NOT simpleExpression
;

signedExpression: (MINUS | PLUS) simpleExpression
;

lambdaPipe: PIPE codeBlock
;

lambdaParam: identifier lambdaParamType?
;

lambdaParamType: COLON type multiplicity
;

instanceLiteral: instanceLiteralToken | (MINUS INTEGER) | (MINUS FLOAT) | (MINUS DECIMAL) | (PLUS INTEGER) | (PLUS FLOAT) | (PLUS DECIMAL)
;

instanceLiteralToken: STRING | STRING_TRIPLE | INTEGER | FLOAT | DECIMAL | DATE | BOOLEAN | STRICTTIME
;

functionVariableExpression: identifier COLON type multiplicity
;

type: ( qualifiedName (LESSTHAN (typeArguments? (PIPE multiplicityArguments)?) GREATERTHAN)?) typeVariableValues?
      |
      (
        CURLY_BRACKET_OPEN
            functionTypePureType? (COMMA functionTypePureType)*
            ARROW type multiplicity
        CURLY_BRACKET_CLOSE
      )
      |
      (
        GROUP_OPEN
            columnType (COMMA columnType)*
        GROUP_CLOSE
      )
;

typeVariableValues: GROUP_OPEN instanceLiteral (COMMA instanceLiteral)* GROUP_CLOSE
;

columnType: mayColumnName COLON mayColumnType multiplicity?
;

mayColumnName: (QUESTION | columnName)
;

mayColumnType: (QUESTION | type)
;

columnName: identifier | STRING
;

multiplicity: BRACKET_OPEN multiplicityArgument BRACKET_CLOSE
;

fromMultiplicity: INTEGER;

toMultiplicity: INTEGER | STAR;



functionTypePureType: type multiplicity
;

typeAndMultiplicityParameters: LESSTHAN ((typeParameters multiplictyParameters?) | multiplictyParameters) GREATERTHAN
;

typeParametersWithVarianceAndMultiplicityParameters: LESSTHAN ((typeParametersWithVariance multiplictyParameters?) | multiplictyParameters) GREATERTHAN
;

typeParameters: typeParameter (COMMA typeParameter)*
;

typeParameter: identifier
;

typeParametersWithVariance: typeParameterWithVariance (COMMA typeParameterWithVariance)*
;

typeParameterWithVariance: MINUS? identifier
;

multiplicityArguments: multiplicityArgument (COMMA multiplicityArgument)*
;

multiplicityArgument: QUESTION | identifier | ((fromMultiplicity DOTDOT)? toMultiplicity)
;

typeArguments: typeOrUndefined (COMMA typeOrUndefined)*
;

typeOrUndefined: (QUESTION | typeWithOperation)
;

typeWithOperation : type equalType? (typeAddSubOperation)* subsetType?
;

typeAddSubOperation: addType | subType
;

addType: PLUS type
;

subType: MINUS type
;

subsetType: SUBSET type
;

equalType: EQUAL type
;

multiplictyParameters: PIPE identifier (COMMA identifier)*
;

dsl: DSL_TEXT
;

aggregation: GROUP_OPEN AGGREGATION_TYPE GROUP_CLOSE
;


