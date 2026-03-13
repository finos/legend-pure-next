parser grammar M3Parser;

options
{
    tokenVocab = M3Lexer;
}


identifier: VALID_STRING | CLASS | FUNCTION | PROFILE | ASSOCIATION | ENUM | MEASURE | STEREOTYPES | TAGS | IMPORT | LET | AGGREGATION_TYPE | PATH_SEPARATOR | AS | ALL | ENFORCEMENT_LEVEL
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
                | measureDefinition
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

measureDefinition: MEASURE stereotypes? taggedValues? qualifiedName
                   measureBody
;

measureBody: CURLY_BRACKET_OPEN
             (
                (
                    unitExpr* canonicalUnitExpr unitExpr*
                )
                |
                nonConvertibleUnitExpr+
             )
             CURLY_BRACKET_CLOSE
;

canonicalUnitExpr: STAR unitExpr
;

unitExpr: identifier COLON unitConversionExpr
;

nonConvertibleUnitExpr : identifier END_LINE
;

unitConversionExpr: identifier ARROW codeBlock
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
            (equalNotEqual)?
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

unitInstance: unitInstanceLiteral unitName
;

unitName: qualifiedName TILDE identifier
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

equalNotEqual: (TEST_EQUAL | TEST_NOT_EQUAL ) combinedArithmeticOnly
;

combinedArithmeticOnly: expressionOrExpressionGroup arithmeticPart*
;

expressionPart: booleanPart | arithmeticPart
;

letExpression: LET identifier EQUAL combinedExpression
;

combinedExpression: expressionOrExpressionGroup expressionPart*
;

expressionOrExpressionGroup: expression
;

expressionsArray: BRACKET_OPEN ( combinedExpression (COMMA combinedExpression)* )? BRACKET_CLOSE
;

propertyOrFunctionExpression: propertyExpression | functionExpression
;

propertyExpression: DOT propertyName (functionExpressionLatestMilestoningDateParameter | functionExpressionParameters)?
;

functionExpression: ARROW qualifiedName functionExpressionParameters (ARROW qualifiedName functionExpressionParameters)*
;

functionExpressionLatestMilestoningDateParameter: GROUP_OPEN LATEST_DATE (COMMA LATEST_DATE)? GROUP_CLOSE
;

functionExpressionParameters: GROUP_OPEN (combinedExpression (COMMA combinedExpression)*)? GROUP_CLOSE
;

atomicExpression:
                 dsl
                 | instanceLiteralToken
                 | expressionInstance
                 | unitInstance
                 | variable
                 | columnBuilders
                 | (AT (type | multiplicity))
                 | anyLambda
                 | instanceReference
;

columnBuilders: TILDE (oneColSpec | (BRACKET_OPEN (oneColSpec(COMMA oneColSpec)*)? BRACKET_CLOSE))
;
oneColSpec: columnName (COLON (type multiplicity? | anyLambda) extraFunction?)?
;
extraFunction: (COLON anyLambda)
;

instanceReference: (PATH_SEPARATOR | qualifiedName | unitName) allOrFunction?
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
                          (variable | qualifiedName)
                          (LESSTHAN typeArguments? (PIPE multiplicityArguments)? GREATERTHAN)? (identifier)?
                          (typeVariableValues)?
                          GROUP_OPEN
                              expressionInstanceParserPropertyAssignment? (COMMA expressionInstanceParserPropertyAssignment)*
                          GROUP_CLOSE
;

expressionInstanceRightSide: expressionInstanceAtomicRightSide
;

expressionInstanceAtomicRightSide: combinedExpression | expressionInstance | qualifiedName
;

expressionInstanceParserPropertyAssignment: propertyName (DOT propertyName)* PLUS? EQUAL expressionInstanceRightSide
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

instanceLiteralToken: STRING | INTEGER | FLOAT | DECIMAL | DATE | BOOLEAN | STRICTTIME
;

unitInstanceLiteral: (MINUS? INTEGER) | (MINUS? FLOAT) | (MINUS? DECIMAL) | (PLUS INTEGER) | (PLUS FLOAT) | (PLUS DECIMAL)
;

arithmeticPart:   PLUS simpleExpression (PLUS simpleExpression)*
                | (STAR simpleExpression (STAR simpleExpression)*)
                | (MINUS simpleExpression (MINUS simpleExpression)*)
                | (DIVIDE simpleExpression (DIVIDE simpleExpression)*)
                | (LESSTHAN simpleExpression)
                | (LESSTHANEQUAL simpleExpression)
                | (GREATERTHAN simpleExpression)
                | (GREATERTHANEQUAL simpleExpression)
;

booleanPart:  AND combinedArithmeticOnly
            | (OR  combinedArithmeticOnly )
            | equalNotEqual
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
      |
      unitName
;

typeVariableValues: GROUP_OPEN (instanceLiteral (COMMA instanceLiteral)*)? GROUP_CLOSE
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

multiplicityArgument: identifier | ((fromMultiplicity DOTDOT)? toMultiplicity)
;

typeArguments: typeWithOperation (COMMA typeWithOperation)*
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


