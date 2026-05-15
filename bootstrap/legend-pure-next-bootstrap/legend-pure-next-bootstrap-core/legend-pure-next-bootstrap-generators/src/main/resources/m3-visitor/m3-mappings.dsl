# M3 Visitor Mapping DSL — prototype (slice: variable + literal token).
#
# Each `rule X` corresponds to an ANTLR rule in M3Parser.g4. The generator
# emits a `visitX(M3Parser.XContext ctx)` method that constructs the AST node
# described here.
#
# Expression grammar:
#   $.<rule_or_token>        →  ctx.<rule_or_token>()  (returns Context or TerminalNode)
#   $.<rule_or_token>.text   →  ctx.<rule_or_token>().getText()
#   $loc                      →  buildSourceInfo(ctx)  (intrinsic)
#   parseLong(expr)           →  Long.parseLong(expr)
#   parseDouble(expr)         →  Double.parseDouble(expr)
#   parseBoolean(expr)        →  Boolean.parseBoolean(expr)
#   stripQuotes(expr)         →  expr.substring(1, expr.length() - 1)
#   stripPercent(expr)        →  expr.startsWith("%") ? expr.substring(1) : expr
#   primitiveType(name)       →  buildPrimitiveGenericType(name)
#   dateLiteralType(textExpr) →  textExpr.contains("T") ? primitiveType("DateTime") : primitiveType("StrictDate")
#
# `alt when <pred> { ... }` introduces an alternative; predicate `$.X` means
# `ctx.X() != null`. Alternatives are tested in order. `else error(msg)`
# matches anything else.
#
# `shared field` applies the field assignment across all alternatives.

rule variable {
  emit VariableExpression
  field name = $.identifier.text
  field sourceInformation = $loc
}

rule instanceLiteralToken {
  emit AtomicValue
  shared field sourceInformation = $loc

  alt when $.INTEGER {
    field value = parseLong($.INTEGER.text)
    field genericType = primitiveType("Integer")
  }
  alt when $.STRING {
    field value = stripQuotes($.STRING.text)
    field genericType = primitiveType("String")
  }
  alt when $.FLOAT {
    field value = parseDouble($.FLOAT.text)
    field genericType = primitiveType("Float")
  }
  alt when $.DECIMAL {
    field value = parseDouble($.DECIMAL.text)
    field genericType = primitiveType("Decimal")
  }
  alt when $.BOOLEAN {
    field value = parseBoolean($.BOOLEAN.text)
    field genericType = primitiveType("Boolean")
  }
  alt when $.DATE {
    field value = stripPercent($.DATE.text)
    field genericType = dateLiteralType($.DATE.text)
  }
  alt when $.STRICTTIME {
    field value = stripPercent($.STRICTTIME.text)
    field genericType = primitiveType("StrictTime")
  }
  else error("Unsupported literal token")
}

# -----------------------------------------------------------------------
# Operator-precedence ladder (or > and > equality > relational > additive
# > multiplicative). Each rule left-folds over its child rule, emitting
# binary FunctionInvocation calls per operator token between operands.
#
# `left_fold over $.X { ... }` generates:
#   - operands = ctx.X();
#   - result = build_X(operands.get(0));
#   - for i in 1..n: take the operator token at position 2*i-1, build a
#     binary FunctionInvocation, fold into result.
#
# Body knobs:
#   op "name"                  fixed function name for every operator.
#   op_by_token TOK "name"     dispatch the function name by token type.
#   when_token TOK wrap_with "name"
#                              after building the binary call, wrap it in
#                              a unary FunctionInvocation when the operator
#                              token matches TOK (used for `!=` → not(equal)).
# -----------------------------------------------------------------------

rule orExpression {
  left_fold over $.andExpression {
    op "or"
  }
}

rule andExpression {
  left_fold over $.equalityExpression {
    op "and"
  }
}

rule equalityExpression {
  left_fold over $.relationalExpression {
    op "equal"
    when_token TEST_NOT_EQUAL wrap_with "not"
  }
}

rule relationalExpression {
  left_fold over $.additiveExpression {
    op_by_token LESSTHAN "lessThan"
    op_by_token LESSTHANEQUAL "lessThanEqual"
    op_by_token GREATERTHAN "greaterThan"
    op_by_token GREATERTHANEQUAL "greaterThanEqual"
  }
}

rule additiveExpression {
  left_fold over $.multiplicativeExpression {
    op_by_token PLUS "plus"
    op_by_token MINUS "minus"
  }
}

rule multiplicativeExpression {
  left_fold over $.expression {
    op_by_token STAR "times"
    op_by_token DIVIDE "divide"
  }
}

# -----------------------------------------------------------------------
# Simple sub-builders. These exercise:
#   - sub-rule helper calls (buildGenericType($.type), parseMultiplicity(...))
#   - delegate rules (combinedExpression passes through to orExpression)
#   - specific-child source-info ($loc($.X))
# -----------------------------------------------------------------------

rule combinedExpression {
  delegate $.orExpression
}

# Grammar: multiplicity: BRACKET_OPEN multiplicityArgument BRACKET_CLOSE
# Pass-through: the structured multiplicity-argument context is what carries the
# actual value (identifier, `?`, or bounds). Avoids re-parsing the bracketed
# text representation back into a structure.
rule multiplicity as Multiplicity_Protocol {
  return parseMultiplicityArgument($.multiplicityArgument)
}

# Grammar: multiplicityArgument: identifier | ((fromMultiplicity DOTDOT)? toMultiplicity)
# Three alts:
#   - `?`        → UndefinedMultiplicity (wildcard, compiler fills in)
#   - identifier → UserDefinedMultiplicityParameter (named parameter like `n`)
#   - numeric    → UserDefinedAdHocMultiplicity with explicit lower/upper bounds.
#                  Bound rules: an explicit `from..to` always uses `from` as lower
#                  and `to` as upper unless `to` is `*` (open-ended upper).
#                  A bare `to` uses it for both lower and upper unless it is `*`,
#                  in which case lower=0 and upper is left unset (any count).
rule multiplicityArgument as Multiplicity_Protocol {
  method parseMultiplicityArgument
  alt when $.QUESTION {
    emit UndefinedMultiplicity
  }
  alt when $.identifier {
    emit UserDefinedMultiplicityParameter
    field name = $.identifier.text
  }
  alt else {
    emit UserDefinedAdHocMultiplicity
    field lowerBound = newImpl(MultiplicityValue,
                               value=ifPresent($.fromMultiplicity,
                                               parseLong($.fromMultiplicity.text),
                                               ifPresent($.toMultiplicity.STAR,
                                                         parseLong("0"),
                                                         parseLong($.toMultiplicity.text))))
    optional field upperBound when $.toMultiplicity.INTEGER = newImpl(MultiplicityValue, value=parseLong($.toMultiplicity.text))
  }
}

rule functionVariableExpression {
  emit VariableExpression
  field name = $.identifier.text
  field sourceInformation = $loc
  field genericType = buildGenericType($.type)
  field multiplicity = buildMultiplicity($.multiplicity)
}

# -----------------------------------------------------------------------
# Per-alt return/delegate forms:
#   - `as ReturnType` on the rule declares the method's return type.
#   - inside an alt: `return EXPR` returns the value of EXPR.
#   - inside an alt: `delegate $.X` returns buildX(ctx.X()).
#   - inside an alt: `emit T` overrides the rule-level emit type for that alt.
# -----------------------------------------------------------------------

# Grammar: atomicExpression: variable | instanceLiteralToken | anyLambda | instanceReference
#                          | expressionInstance | dsl | columnBuilders | AT ...
# A multi-way dispatcher: simple delegates for most sub-rules, an inline DSL block
# alternative, and AT (TypeRef) and columnBuilders dispatched to their own rules.
rule atomicExpression as ValueSpecification {
  alt when $.variable {
    delegate $.variable
  }
  alt when $.instanceLiteralToken {
    delegate $.instanceLiteralToken
  }
  alt when $.anyLambda {
    delegate $.anyLambda
  }
  alt when $.instanceReference {
    delegate $.instanceReference
  }
  alt when $.expressionInstance {
    delegate $.expressionInstance
  }
  alt when $.dsl {
    return newImpl(AtomicValue,
                   sourceInformation=$loc($.dsl),
                   genericType=primitiveType("String"),
                   value=$.dsl.DSL_TEXT.text)
  }
  alt when $.columnBuilders {
    delegate $.columnBuilders
  }
  alt when $.AT {
    return buildAtomicTypeRef($self)
  }
  else error("Unsupported atomicExpression")
}

# Grammar: `@Type[mul]` / `@Type|mul` / `@Type` / `@|mul` / `@[mul]` — a "TypeHolder"
# value-spec that names a type and/or a multiplicity. Missing halves default to
# the Undefined variants. Built as a separate rule so the conditional defaults
# stay declarative.
rule atomicTypeRef {
  context AtomicExpressionContext
  method buildAtomicTypeRef
  emit UserDefinedGenericTypeAndMultiplicityHolder
  field sourceInformation = $loc
  field genericType = ifPresent($.type, buildGenericType($.type), newImpl(UndefinedGenericType))
  field multiplicity = ifPresent($.multiplicityArgument,
                                  parseMultiplicityArgument($.multiplicityArgument),
                                  ifPresent($.multiplicity,
                                            buildMultiplicity($.multiplicity),
                                            newImpl(UndefinedMultiplicity)))
}

# Grammar: columnBuilders: TILDE (oneColSpec | (BRACKET_OPEN (oneColSpec (COMMA oneColSpec)*)? BRACKET_CLOSE))
# Dispatch (order matters):
#  1. Array form with any aggregator (extraFunction present) → aggColSpecArray
#  2. Array form with any map-only lambda           → funcColSpecArray
#  3. Other array form                              → colSpecArray (names + combined RelationType holder)
#  4. Bare single column                            → just the inner colSpec call
# Function-name dispatch (colSpec vs funcColSpec vs aggColSpec, and *Array variants)
# is decided here based on lambda presence; the *2 suffix dispatch is delegated to
# the compiler.
rule columnBuilders as ValueSpecification {
  alt when $.BRACKET_OPEN && anyHas($.oneColSpec, extraFunction) {
    return newImpl(FunctionInvocation,
                   sourceInformation=$loc,
                   functionName="aggColSpecArray",
                   parametersValues=listOf(
                     newImpl(Collection,
                             values=mapList($.oneColSpec, buildOneColSpec),
                             multiplicity=multBounds(count($.oneColSpec), count($.oneColSpec))),
                     newImpl(CompilerGenericTypeAndMultiplicityHolder)))
  }
  alt when $.BRACKET_OPEN && anyHas($.oneColSpec, anyLambda) {
    return newImpl(FunctionInvocation,
                   sourceInformation=$loc,
                   functionName="funcColSpecArray",
                   parametersValues=listOf(
                     newImpl(Collection,
                             values=mapList($.oneColSpec, buildOneColSpec),
                             multiplicity=multBounds(count($.oneColSpec), count($.oneColSpec))),
                     newImpl(CompilerGenericTypeAndMultiplicityHolder)))
  }
  alt when $.BRACKET_OPEN {
    return newImpl(FunctionInvocation,
                   sourceInformation=$loc,
                   functionName="colSpecArray",
                   parametersValues=listOf(
                     newImpl(Collection,
                             values=mapList($.oneColSpec, buildColumnNameAtomic),
                             multiplicity=multBounds(count($.oneColSpec), count($.oneColSpec))),
                     buildColSpecArrayHolder($self)))
  }
  alt else {
    return buildOneColSpec(firstOf($.oneColSpec))
  }
}

# Grammar: oneColSpec: columnName (COLON (type multiplicity? | anyLambda) extraFunction?)?
# Builds the per-column FunctionInvocation. The function name picks one of
# `colSpec` / `funcColSpec` / `aggColSpec` from whether `anyLambda` and
# `extraFunction` are present. The parameter list, in order, is:
#   - mapping lambda     (only when anyLambda is present)
#   - reducing lambda    (only when extraFunction is present)
#   - column-name as String AtomicValue
#   - type holder        — a RelationType-wrapping holder when this column carries
#                          its own type/multiplicity, otherwise a CompilerHolder
#                          (placeholder for the compiler to fill in).
rule oneColSpec as ValueSpecification {
  emit FunctionInvocation
  let ValueSpecification nameAtomic = newImpl(AtomicValue,
                                                sourceInformation=$loc($.columnName),
                                                genericType=primitiveType("String"),
                                                value=$.columnName.text)
  let ValueSpecification typeHolder = ifPresent(hasAny($self, type, multiplicity),
                                                  newImpl(UserDefinedGenericTypeAndMultiplicityHolder,
                                                          genericType=newImpl(UserDefinedGenericType,
                                                                              type=newImpl(RelationType,
                                                                                            columns=listOf(buildOneColSpecColumn($self))))),
                                                  newImpl(CompilerGenericTypeAndMultiplicityHolder))
  field sourceInformation = $loc
  field functionName = ifPresent($.anyLambda,
                                  ifPresent($.extraFunction, "aggColSpec", "funcColSpec"),
                                  "colSpec")
  field parametersValues = ifPresent($.anyLambda,
                                      ifPresent($.extraFunction,
                                                listOf(buildAnyLambda($.anyLambda),
                                                       buildAnyLambda($.extraFunction.anyLambda),
                                                       nameAtomic,
                                                       typeHolder),
                                                listOf(buildAnyLambda($.anyLambda),
                                                       nameAtomic,
                                                       typeHolder)),
                                      listOf(nameAtomic, typeHolder))
}

# Helper rule: a single Column for a RelationType, derived from a oneColSpec context.
# Used both for the per-column type-holder and for the combined colSpecArray holder.
rule oneColSpecColumn {
  context OneColSpecContext
  method buildOneColSpecColumn
  emit Column
  field name = $.columnName.text
  optional field genericType when $.type = buildGenericType($.type)
  optional field multiplicity when $.multiplicity = buildMultiplicity($.multiplicity)
}

# Helper rule: the String AtomicValue carrying a column name, used in colSpecArray.
rule columnNameAtomic as ValueSpecification {
  context OneColSpecContext
  method buildColumnNameAtomic
  emit AtomicValue
  field sourceInformation = $loc($.columnName)
  field genericType = primitiveType("String")
  field value = $.columnName.text
}

# Helper rule: the type holder for the colSpecArray case (no lambdas anywhere).
# Either a UserDefinedGenericTypeAndMultiplicityHolder wrapping a RelationType
# built from all typed/multiplied colSpecs, or a CompilerHolder when none have
# explicit types.
rule colSpecArrayHolder as ValueSpecification {
  context ColumnBuildersContext
  method buildColSpecArrayHolder
  alt when anyHasAny($.oneColSpec, type, multiplicity) {
    return newImpl(UserDefinedGenericTypeAndMultiplicityHolder,
                   genericType=newImpl(UserDefinedGenericType,
                                       type=newImpl(RelationType,
                                                    columns=selectMapHasAny($.oneColSpec, type, multiplicity, oneColSpecColumn))))
  }
  alt else {
    emit CompilerGenericTypeAndMultiplicityHolder
  }
}

# Grammar: typeWithOperation: type equalType? (typeAddSubOperation)* subsetType?
# Sequentially wraps a base GenericType with type-algebra operations:
#   - equalType?  → wrap in `Equal`
#   - addSub list → wrap in `Union` / `Difference` each
#   - subsetType? → wrap in `Subset`
# Uses `set` to rebind the running result across the four stages.
rule typeWithOperation as GenericType {
  let GenericType result = buildGenericType($.type)
  set result = ifPresent($.equalType,
                          newImpl(GenericTypeOperation,
                                  operationType=enumPointer("meta::pure::metamodel::relation::GenericTypeOperationType", "Equal"),
                                  left=result,
                                  right=buildGenericType($.equalType.type)),
                          result)
  set result = ListAdapter.adapt($.typeAddSubOperation).injectInto(result, this::buildWrapAddSubOp)
  set result = ifPresent($.subsetType,
                          newImpl(GenericTypeOperation,
                                  operationType=enumPointer("meta::pure::metamodel::relation::GenericTypeOperationType", "Subset"),
                                  left=result,
                                  right=buildGenericType($.subsetType.type)),
                          result)
  return result
}

# Helper rule: one Union/Difference wrap step for a single typeAddSubOperation
# context. Used as the lambda body of the injectInto fold in typeWithOperation.
rule wrapAddSubOp as GenericType {
  context TypeAddSubOperationContext
  param GenericType base
  alt when $.addType {
    return newImpl(GenericTypeOperation,
                   operationType=enumPointer("meta::pure::metamodel::relation::GenericTypeOperationType", "Union"),
                   left=base,
                   right=buildGenericType($.addType.type))
  }
  alt else {
    return newImpl(GenericTypeOperation,
                   operationType=enumPointer("meta::pure::metamodel::relation::GenericTypeOperationType", "Difference"),
                   left=base,
                   right=buildGenericType($.subType.type))
  }
}

rule typeOrUndefined as GenericType {
  alt when $.QUESTION {
    emit UndefinedGenericType
  }
  alt else {
    delegate $.typeWithOperation
  }
  else error("Unexpected typeOrUndefined")
}

rule buildMilestoningVariableExpression as ValueSpecification {
  alt when $.variable {
    return buildVariable($.variable)
  }
  else error("Milestoning date expressions not yet supported")
}

# -----------------------------------------------------------------------
# Signed numeric literals — grammar:
#   instanceLiteral: instanceLiteralToken | ((MINUS | PLUS) (INTEGER | FLOAT | DECIMAL))
# Uses the ifPresent(test, then, else) primitive to handle optional MINUS.
# -----------------------------------------------------------------------

# -----------------------------------------------------------------------
# notExpression: `!expr` → not(expr)
# sliceExpression: `[expr]` / `[a:b]` / `[a:b:c]` → slice(a, b, ...)
#
# Uses listOf(...) and mapList(...) primitives.
# -----------------------------------------------------------------------

# Grammar: instanceReference: (PATH_SEPARATOR | qualifiedName) allOrFunction?
# Dispatches on which sub-rule of allOrFunction is present. The first alt
# handles a direct function call (with explicit args); the next four emit
# the `.all()` / `.allVersions(...)` / etc invocation with a package-pointer
# head; the fallback is a bare package-pointer reference. The package-pointer
# value is the qualifiedName when present, else everything before the first dot
# in the raw text (the PATH_SEPARATOR-prefixed form).
rule instanceReference as ValueSpecification {
  alt when $.allOrFunction.functionExpressionParameters {
    return newImpl(FunctionInvocation,
                   sourceInformation=$loc,
                   functionName=ifPresent($.qualifiedName, $.qualifiedName.text, $self.getText()),
                   parametersValues=mapList($.allOrFunction.functionExpressionParameters.combinedExpression, buildCombinedExpression))
  }
  alt when $.allOrFunction.allFunction {
    return newImpl(FunctionInvocation,
                   sourceInformation=$loc,
                   functionName="getAll",
                   parametersValues=listOf(
                     newImpl(AtomicValue,
                             sourceInformation=$loc,
                             value=newImpl(Package_Pointer,
                                           value=ifPresent($.qualifiedName, $.qualifiedName.text, beforeFirstDot($self.getText()))))))
  }
  alt when $.allOrFunction.allVersionsFunction {
    return newImpl(FunctionInvocation,
                   sourceInformation=$loc,
                   functionName="getAllVersions",
                   parametersValues=listOf(
                     newImpl(AtomicValue,
                             sourceInformation=$loc,
                             value=newImpl(Package_Pointer,
                                           value=ifPresent($.qualifiedName, $.qualifiedName.text, beforeFirstDot($self.getText()))))))
  }
  alt when $.allOrFunction.allVersionsInRangeFunction {
    return newImpl(FunctionInvocation,
                   sourceInformation=$loc,
                   functionName="getAllVersionsInRange",
                   parametersValues=prepended(
                     newImpl(AtomicValue,
                             sourceInformation=$loc,
                             value=newImpl(Package_Pointer,
                                           value=ifPresent($.qualifiedName, $.qualifiedName.text, beforeFirstDot($self.getText())))),
                     mapList($.allOrFunction.allVersionsInRangeFunction.buildMilestoningVariableExpression, buildMilestoningVariableExpression)))
  }
  alt when $.allOrFunction.allFunctionWithMilestoning {
    return newImpl(FunctionInvocation,
                   sourceInformation=$loc,
                   functionName="getAll",
                   parametersValues=prepended(
                     newImpl(AtomicValue,
                             sourceInformation=$loc,
                             value=newImpl(Package_Pointer,
                                           value=ifPresent($.qualifiedName, $.qualifiedName.text, beforeFirstDot($self.getText())))),
                     mapList($.allOrFunction.allFunctionWithMilestoning.buildMilestoningVariableExpression, buildMilestoningVariableExpression)))
  }
  alt else {
    return newImpl(AtomicValue,
                   sourceInformation=$loc,
                   value=newImpl(Package_Pointer, value=$self.getText()))
  }
}

# Grammar: lambdaParam: identifier lambdaParamType?
# A lambda parameter slot: either bare (`x`) or typed (`x:T[m]`). The three
# optional fields share one predicate ($.lambdaParamType) — set together.
# Grammar: propertyExpression: DOT propertyName functionExpressionParameters?
# Property access on a `receiver`: `receiver.propName` or `receiver.propName(args)`.
# Takes the receiver value-spec as an extra method parameter.
# Grammar: expressionInstance: NEW (variable | qualifiedName) typeArguments? multiplicityArguments?
#                              typeVariableValues? GROUP_OPEN expressionInstanceParserPropertyAssignment*
#                              GROUP_CLOSE
# Two alts: `^$var(...)` (copy) and `^Type(...)` (new). Property assignments are
# wrapped in a single Collection appended to the params list when non-empty.
rule expressionInstance as ValueSpecification {
  emit FunctionInvocation
  shared field sourceInformation = $loc
  alt when $.variable {
    field functionName = "copy"
    field parametersValues = ifPresent(notEmpty($.expressionInstanceParserPropertyAssignment),
      listOf(newImpl(VariableExpression, name=$.variable.identifier.text, sourceInformation=$loc($.variable)),
             newImpl(Collection,
                     sourceInformation=$loc,
                     values=mapList($.expressionInstanceParserPropertyAssignment, buildExpressionInstanceParserPropertyAssignment),
                     multiplicity=multBounds(count($.expressionInstanceParserPropertyAssignment), count($.expressionInstanceParserPropertyAssignment)))),
      listOf(newImpl(VariableExpression, name=$.variable.identifier.text, sourceInformation=$loc($.variable))))
  }
  alt else {
    field functionName = "new"
    field parametersValues = ifPresent(notEmpty($.expressionInstanceParserPropertyAssignment),
      listOf(buildExpressionInstanceNewHead($self),
             newImpl(Collection,
                     sourceInformation=$loc,
                     values=mapList($.expressionInstanceParserPropertyAssignment, buildExpressionInstanceParserPropertyAssignment),
                     multiplicity=multBounds(count($.expressionInstanceParserPropertyAssignment), count($.expressionInstanceParserPropertyAssignment)))),
      listOf(buildExpressionInstanceNewHead($self)))
  }
}

# Builds the GenericType + Multiplicity holder that is the first param of a
# `new(...)` call. The multiplicity is always `[1]`. Type name is the
# qualifiedName when present (named `^Type(...)`) or the literal "Unknown"
# (anonymous `^(...)` form). Inner GenericType is produced by a sub-rule so
# its optional `typeArguments` / `multiplicityArguments` / `typeVariableValues`
# can stay declarative.
rule expressionInstanceNewHead {
  context ExpressionInstanceContext
  method buildExpressionInstanceNewHead
  emit UserDefinedGenericTypeAndMultiplicityHolder
  field sourceInformation = $loc
  field genericType = buildExpressionInstanceGenericType($self)
  field multiplicity = multBounds(1, 1)
}

rule expressionInstanceGenericType {
  context ExpressionInstanceContext
  method buildExpressionInstanceGenericType
  emit UserDefinedGenericType
  field type = newImpl(Type_Pointer, value=ifPresent($.qualifiedName, $.qualifiedName.text, "Unknown"))
  optional field typeArguments when $.typeArguments = mapList($.typeArguments.typeOrUndefined, buildTypeOrUndefined)
  optional field multiplicityArguments when $.multiplicityArguments = mapList($.multiplicityArguments.multiplicityArgument, parseMultiplicityArgument)
  optional field typeVariableValues when $.typeVariableValues = mapList($.typeVariableValues.instanceLiteral, buildInstanceLiteral)
}

# Grammar: expressionInstanceParserPropertyAssignment: propertyName (DOT propertyName)*
#          (PLUS)? EQUAL expressionInstanceRightSide
# Each assignment becomes a `keyExpression(nameStr, rhs[, plusFlag])` invocation.
rule expressionInstanceParserPropertyAssignment {
  emit FunctionInvocation
  field sourceInformation = $loc
  field functionName = "keyExpression"
  field parametersValues = ifPresent($.PLUS,
    listOf(
      newImpl(AtomicValue, sourceInformation=$loc, genericType=primitiveType("String"), value=joinTextWith($.propertyName, ".")),
      buildExpressionInstanceRightSide($.expressionInstanceRightSide),
      newImpl(AtomicValue, sourceInformation=$loc, genericType=primitiveType("Boolean"), value=true)),
    listOf(
      newImpl(AtomicValue, sourceInformation=$loc, genericType=primitiveType("String"), value=joinTextWith($.propertyName, ".")),
      buildExpressionInstanceRightSide($.expressionInstanceRightSide)))
}

# Grammar: expressionInstanceRightSide: expressionInstanceAtomicRightSide
# expressionInstanceAtomicRightSide:
#     parentReference | combinedExpression | expressionInstance | qualifiedName
# parentReference is `~.~.~...propertyName.propertyName`: count of TILDEs gives
# the "depth", DOT-joined propertyNames give the "path".
rule expressionInstanceRightSide as ValueSpecification {
  alt when $.expressionInstanceAtomicRightSide.parentReference {
    return newImpl(FunctionInvocation,
                   sourceInformation=$loc($.expressionInstanceAtomicRightSide.parentReference),
                   functionName="parentReference",
                   parametersValues=listOf(
                     newImpl(AtomicValue,
                             sourceInformation=$loc($.expressionInstanceAtomicRightSide.parentReference),
                             genericType=primitiveType("Integer"),
                             value=(long)($.expressionInstanceAtomicRightSide.parentReference.TILDE.size - 1)),
                     newImpl(AtomicValue,
                             sourceInformation=$loc($.expressionInstanceAtomicRightSide.parentReference),
                             genericType=primitiveType("String"),
                             value=joinTextWith($.expressionInstanceAtomicRightSide.parentReference.propertyName, "."))))
  }
  alt when $.expressionInstanceAtomicRightSide.combinedExpression {
    return buildCombinedExpression($.expressionInstanceAtomicRightSide.combinedExpression)
  }
  alt when $.expressionInstanceAtomicRightSide.expressionInstance {
    return buildExpressionInstance($.expressionInstanceAtomicRightSide.expressionInstance)
  }
  alt when $.expressionInstanceAtomicRightSide.qualifiedName {
    return newImpl(VariableExpression,
                   sourceInformation=$loc($.expressionInstanceAtomicRightSide),
                   name=$.expressionInstanceAtomicRightSide.qualifiedName.text)
  }
  else error("Unsupported expressionInstanceRightSide")
}

# Grammar: functionExpression: arrowStep+
#          arrowStep: ARROW qualifiedName functionExpressionParameters
# Left-fold over the arrow steps, each producing an ArrowInvocation that takes
# the running result (acc) as its first parameter and the per-step args as the rest.
rule functionExpression as ValueSpecification {
  param ValueSpecification receiver
  chain_fold from receiver over $.arrowStep {
    else step newImpl(ArrowInvocation,
                       sourceInformation=$loc,
                       functionName=$it.qualifiedName.text,
                       parametersValues=prepended(acc, mapList($it.functionExpressionParameters.combinedExpression, buildCombinedExpression)))
  }
}

rule propertyExpression as ValueSpecification {
  param ValueSpecification receiver
  emit DotApplication
  field sourceInformation = $loc
  field functionName = $.propertyName.text
  field parametersValues = ifPresent($.functionExpressionParameters,
                                      prepended(receiver, mapList($.functionExpressionParameters.combinedExpression, buildCombinedExpression)),
                                      listOf(receiver))
}

rule lambdaParam {
  emit VariableExpression
  field name = $.identifier.text
  optional field sourceInformation when $.lambdaParamType = $loc
  optional field genericType when $.lambdaParamType = buildGenericType($.lambdaParamType.type)
  optional field multiplicity when $.lambdaParamType = buildMultiplicity($.lambdaParamType.multiplicity)
}

# Grammar: lambdaFunction: LBRACE (lambdaParam (COMMA lambdaParam)*)? lambdaPipe RBRACE
# Builds the LambdaFunction value directly (the wrapping AtomicValue is done by
# the caller in `anyLambda`).
rule lambdaFunction {
  emit LambdaFunction
  field sourceInformation = $loc
  field parameters = mapList($.lambdaParam, buildLambdaParam)
  field expressionSequence = buildCodeBlock($.lambdaPipe.codeBlock)
}

# Grammar: anyLambda: lambdaFunction | (lambdaParam? lambdaPipe)
# Three alternatives; each wraps the resulting LambdaFunction in an AtomicValue
# whose source info matches the lambda. Predicate `$.X && $.Y` combines.
rule anyLambda as ValueSpecification {
  alt when $.lambdaFunction {
    return newImpl(AtomicValue,
                   sourceInformation=$loc($.lambdaFunction),
                   value=buildLambdaFunction($.lambdaFunction))
  }
  alt when $.lambdaPipe && $.lambdaParam {
    return newImpl(AtomicValue,
                   sourceInformation=$loc,
                   value=newImpl(LambdaFunction,
                                  sourceInformation=$loc,
                                  parameters=listOf(buildLambdaParam($.lambdaParam)),
                                  expressionSequence=buildCodeBlock($.lambdaPipe.codeBlock)))
  }
  alt when $.lambdaPipe {
    return newImpl(AtomicValue,
                   sourceInformation=$loc,
                   value=newImpl(LambdaFunction,
                                  sourceInformation=$loc,
                                  parameters=listOf(),
                                  expressionSequence=buildCodeBlock($.lambdaPipe.codeBlock)))
  }
  else error("Unsupported anyLambda")
}

rule notExpression as ValueSpecification {
  emit FunctionInvocation
  field sourceInformation = $loc
  field functionName = "not"
  field parametersValues = listOf(buildSimpleExpression($.simpleExpression))
}

# Grammar: nonArrowOrEqualExpression: atomicExpression | expressionsArray |
#          notExpression | signedExpression | sliceExpression | combinedExpression
# Each branch is a delegate to its sub-rule's build method.
rule nonArrowOrEqualExpression as ValueSpecification {
  alt when $.atomicExpression {
    delegate $.atomicExpression
  }
  alt when $.expressionsArray {
    delegate $.expressionsArray
  }
  alt when $.notExpression {
    delegate $.notExpression
  }
  alt when $.signedExpression {
    delegate $.signedExpression
  }
  alt when $.sliceExpression {
    delegate $.sliceExpression
  }
  alt when $.combinedExpression {
    delegate $.combinedExpression
  }
  else error("Unexpected nonArrowOrEqualExpression")
}

# Grammar: signedExpression: (MINUS | PLUS) simpleExpression
# When MINUS, wrap the inner in a `minus(...)` call. When PLUS, pass-through.
rule signedExpression as ValueSpecification {
  alt when $.MINUS {
    emit FunctionInvocation
    field sourceInformation = $loc
    field functionName = "minus"
    field parametersValues = listOf(buildSimpleExpression($.simpleExpression))
  }
  alt else {
    return buildSimpleExpression($.simpleExpression)
  }
}

rule sliceExpression as ValueSpecification {
  emit FunctionInvocation
  field sourceInformation = $loc
  field functionName = "slice"
  field parametersValues = mapList($.expression, buildExpression)
}

# -----------------------------------------------------------------------
# Inline object construction: newImpl(T, k1=v1, k2=v2, ...) generates
# `new TImpl()._k1(v1)._k2(v2)...`. Used for nested AST nodes that
# require building fresh sub-objects inline (e.g. PointerValue for a
# stereotype, AtomicValue for the let-variable name, etc.).
#
# Field expressions may span multiple lines; the parser joins
# continuation lines until parens are balanced.
# -----------------------------------------------------------------------

# Grammar: letExpression: LET identifier EQUAL combinedExpression
# → letFunction(StringAtomic(name), expr)
rule letExpression as ValueSpecification {
  emit FunctionInvocation
  field sourceInformation = $loc
  field functionName = "letFunction"
  field parametersValues = listOf(
    newImpl(AtomicValue,
            sourceInformation=$loc($.identifier),
            genericType=primitiveType("String"),
            value=$.identifier.text),
    buildCombinedExpression($.combinedExpression)
  )
}

# Top-level element rule: primitiveDefinition.
# Grammar: primitiveDefinition: PRIMITIVE stereotypes? taggedValues? qualifiedName
#                              typeVariableParameters? EXTENDS type constraints?
# Single extends type (wrapped in a 1-element list of generalizations).
rule primitiveDefinition {
  topLevel
  emit PrimitiveType
  field sourceInformation = $loc
  optional field typeVariables when $.typeVariableParameters = mapList($.typeVariableParameters.functionVariableExpression, buildFunctionVariableExpression)
  optional field generalizations when $.type = listOf(buildClassGeneralization($.type))
  optional field constraints when $.constraints = mapList($.constraints.constraint, buildConstraint)
  optional field stereotypes when $.stereotypes = mapList($.stereotypes.stereotype, buildStereotype)
  optional field taggedValues when $.taggedValues = mapList($.taggedValues.taggedValue, buildTaggedValue)
  post $$result._name(simpleNameOf($.qualifiedName.text))
  post when hasPackagePrefix($.qualifiedName.text) => $$result._package(newImpl(Package_Pointer, value=packagePrefix($.qualifiedName.text)))
}

# Top-level element rule: enumDefinition.
# Grammar: enumDefinition: ENUM stereotypes? taggedValues? qualifiedName
#                          { enumValue (COMMA enumValue)* }
# Each enumValue maps to a Property carrying its own annotations.
rule enumDefinition {
  topLevel
  emit Enumeration
  field sourceInformation = $loc
  field properties = mapList($.enumValue, buildEnumValue)
  optional field stereotypes when $.stereotypes = mapList($.stereotypes.stereotype, buildStereotype)
  optional field taggedValues when $.taggedValues = mapList($.taggedValues.taggedValue, buildTaggedValue)
  post $$result._name(simpleNameOf($.qualifiedName.text))
  post when hasPackagePrefix($.qualifiedName.text) => $$result._package(newImpl(Package_Pointer, value=packagePrefix($.qualifiedName.text)))
}

# Grammar: enumValue: stereotypes? taggedValues? identifier
rule enumValue {
  emit Property
  field name = $.identifier.text
  field sourceInformation = $loc
  optional field stereotypes when $.stereotypes = mapList($.stereotypes.stereotype, buildStereotype)
  optional field taggedValues when $.taggedValues = mapList($.taggedValues.taggedValue, buildTaggedValue)
}

# Grammar: typeParameter: identifier
rule typeParameter {
  emit TypeParameter
  field name = $.identifier.text
}

# Grammar: typeParameterWithVariance: MINUS? identifier
rule typeParameterWithVariance {
  emit TypeParameter
  field name = $.identifier.text
  optional field contravariant when $.MINUS = true
}

# Helper rule: a single identifier from a multiplicity-parameters list becomes a
# UserDefinedMultiplicityParameter. Context type override since `multParamDef`
# isn't a grammar rule.
rule multParamDef {
  context IdentifierContext
  emit UserDefinedMultiplicityParameter
  field name = $self.getText()
}

# Top-level element rule: classDefinition.
# Grammar: classDefinition: CLASS stereotypes? taggedValues? qualifiedName
#                          typeParametersWithVarianceAndMultiplicityParameters?
#                          typeVariableParameters? (EXTENDS type (COMMA type)*)?
#                          constraints? classBody?
rule classDefinition {
  topLevel
  emit Class
  field sourceInformation = $loc
  optional field typeParameters when $.typeParametersWithVarianceAndMultiplicityParameters.typeParametersWithVariance = mapList($.typeParametersWithVarianceAndMultiplicityParameters.typeParametersWithVariance.typeParameterWithVariance, buildTypeParameterWithVariance)
  optional field multiplicityParameters when $.typeParametersWithVarianceAndMultiplicityParameters.multiplictyParameters = mapList($.typeParametersWithVarianceAndMultiplicityParameters.multiplictyParameters.identifier, buildMultParamDef)
  optional field typeVariables when $.typeVariableParameters = mapList($.typeVariableParameters.functionVariableExpression, buildFunctionVariableExpression)
  optional field generalizations when notEmpty($.type) = mapList($.type, classGeneralization)
  optional field constraints when $.constraints = mapList($.constraints.constraint, buildConstraint)
  optional field properties when $.classBody.properties = mapList($.classBody.properties.property, buildProperty)
  optional field qualifiedProperties when $.classBody.properties = mapList($.classBody.properties.qualifiedProperty, buildQualifiedProperty)
  optional field stereotypes when $.stereotypes = mapList($.stereotypes.stereotype, buildStereotype)
  optional field taggedValues when $.taggedValues = mapList($.taggedValues.taggedValue, buildTaggedValue)
  post $$result._name(simpleNameOf($.qualifiedName.text))
  post when hasPackagePrefix($.qualifiedName.text) => $$result._package(newImpl(Package_Pointer, value=packagePrefix($.qualifiedName.text)))
}

# Helper rule: wrap a TypeContext into a Generalization (general type + source info).
rule classGeneralization {
  context TypeContext
  emit Generalization
  field general = buildGenericType($self)
  field sourceInformation = $loc
}

# Top-level element rule: functionDefinition.
# Grammar: functionDefinition: FUNCTION stereotypes? taggedValues? qualifiedName
#                              typeAndMultiplicityParameters? functionTypeSignature
#                              constraints? codeBlock
# Captures typeParams and multParams in let-bindings so the predicate and value
# share one computation. After the qualified-name split posts, two more posts
# apply the function-specific naming convention (simpleNameOf + buildId).
rule functionDefinition {
  topLevel
  emit UserDefinedFunction
  field sourceInformation = $loc
  optional field typeParameters when $.typeAndMultiplicityParameters.typeParameters = mapList($.typeAndMultiplicityParameters.typeParameters.typeParameter, buildTypeParameter)
  optional field multiplicityParameters when $.typeAndMultiplicityParameters.multiplictyParameters = mapList($.typeAndMultiplicityParameters.multiplictyParameters.identifier, buildMultParamDef)
  field parameters = mapList($.functionTypeSignature.functionVariableExpression, buildFunctionVariableExpression)
  field returnGenericType = buildGenericType($.functionTypeSignature.type)
  field returnMultiplicity = buildMultiplicity($.functionTypeSignature.multiplicity)
  optional field preConstraints when $.constraints = filterMapNot($.constraints.constraint, "$return", buildConstraint)
  optional field postConstraints when $.constraints = filterMap($.constraints.constraint, "$return", buildConstraint)
  field expressionSequence = buildCodeBlock($.codeBlock)
  optional field stereotypes when $.stereotypes = mapList($.stereotypes.stereotype, buildStereotype)
  optional field taggedValues when $.taggedValues = mapList($.taggedValues.taggedValue, buildTaggedValue)
  post $$result._name(simpleNameOf($.qualifiedName.text))
  post when hasPackagePrefix($.qualifiedName.text) => $$result._package(newImpl(Package_Pointer, value=packagePrefix($.qualifiedName.text)))
  post $$result._functionName(simpleNameOf($.qualifiedName.text))
  post $$result._name(_G_PackageableFunction.buildId($$result))
}

# Top-level element rule: nativeFunction.
# Grammar: nativeFunction: NATIVE FUNCTION stereotypes? taggedValues? qualifiedName
#                         typeAndMultiplicityParameters? functionTypeSignature
# Same shape as functionDefinition minus the body and constraints.
rule nativeFunction {
  topLevel
  emit NativeFunction
  field sourceInformation = $loc
  optional field typeParameters when $.typeAndMultiplicityParameters.typeParameters = mapList($.typeAndMultiplicityParameters.typeParameters.typeParameter, buildTypeParameter)
  optional field multiplicityParameters when $.typeAndMultiplicityParameters.multiplictyParameters = mapList($.typeAndMultiplicityParameters.multiplictyParameters.identifier, buildMultParamDef)
  field parameters = mapList($.functionTypeSignature.functionVariableExpression, buildFunctionVariableExpression)
  field returnGenericType = buildGenericType($.functionTypeSignature.type)
  field returnMultiplicity = buildMultiplicity($.functionTypeSignature.multiplicity)
  optional field stereotypes when $.stereotypes = mapList($.stereotypes.stereotype, buildStereotype)
  optional field taggedValues when $.taggedValues = mapList($.taggedValues.taggedValue, buildTaggedValue)
  post $$result._name(simpleNameOf($.qualifiedName.text))
  post when hasPackagePrefix($.qualifiedName.text) => $$result._package(newImpl(Package_Pointer, value=packagePrefix($.qualifiedName.text)))
  post $$result._functionName(simpleNameOf($.qualifiedName.text))
  post $$result._name(_G_PackageableFunction.buildId($$result))
}

# Top-level element rule: association.
# Grammar: association: ASSOCIATION stereotypes? taggedValues? qualifiedName associationBody?
# associationBody contains properties() with property() and qualifiedProperty() lists.
rule association {
  topLevel
  emit Association
  field sourceInformation = $loc
  optional field properties when $.associationBody.properties = mapList($.associationBody.properties.property, buildProperty)
  optional field qualifiedProperties when $.associationBody.properties = mapList($.associationBody.properties.qualifiedProperty, buildQualifiedProperty)
  optional field stereotypes when $.stereotypes = mapList($.stereotypes.stereotype, buildStereotype)
  optional field taggedValues when $.taggedValues = mapList($.taggedValues.taggedValue, buildTaggedValue)
  post $$result._name(simpleNameOf($.qualifiedName.text))
  post when hasPackagePrefix($.qualifiedName.text) => $$result._package(newImpl(Package_Pointer, value=packagePrefix($.qualifiedName.text)))
}

# Top-level element rule: profile.
# Grammar: profile: PROFILE qualifiedName CURLY_BRACKET_OPEN stereotypeDefinitions?
#                   tagDefinitions? CURLY_BRACKET_CLOSE
# The two trailing posts split the qualified name into `_name` (simpleNameOf) and
# `_package` (only when "::" is present). The thin `visitProfile` wrapper in
# M3ProtocolBuilder calls `buildProfile` and adds the result to the parser-level
# `elements` list.
rule profile {
  topLevel
  emit Profile
  field sourceInformation = $loc
  optional field p_stereotypes when $.stereotypeDefinitions = mapList($.stereotypeDefinitions.identifier, profileStereotypeDef)
  optional field p_tags when $.tagDefinitions = mapList($.tagDefinitions.identifier, profileTagDef)
  post $$result._name(simpleNameOf($.qualifiedName.text))
  post when hasPackagePrefix($.qualifiedName.text) => $$result._package(newImpl(Package_Pointer, value=packagePrefix($.qualifiedName.text)))
}

# Helper rule: convert an identifier context to a stereotype definition (just name+srcInfo).
# Context type override since `profileStereotypeDef` isn't a grammar rule.
rule profileStereotypeDef {
  context IdentifierContext
  emit Stereotype
  field sourceInformation = $loc
  field value = $self.getText()
}

rule profileTagDef {
  context IdentifierContext
  emit Tag
  field sourceInformation = $loc
  field value = $self.getText()
}

# Grammar: stereotype: qualifiedName DOT identifier
# qualifiedName = profile path, identifier = stereotype name
rule stereotype {
  emit Stereotype_Pointer
  field sourceInformation = $loc($.qualifiedName)
  field value = $.qualifiedName.text
  field extraPointerValues = listOf(
    newImpl(PointerValue,
            sourceInformation=$loc($.identifier),
            value=$.identifier.text)
  )
}

# Grammar: taggedValue: qualifiedName DOT identifier EQUAL STRING (PLUS STRING)*
# Multiple STRINGs are concatenated (after quote-stripping).
rule taggedValue {
  emit TaggedValue
  field tag = newImpl(Tag_Pointer,
                      sourceInformation=$loc($.qualifiedName),
                      value=$.qualifiedName.text,
                      extraPointerValues=listOf(
                          newImpl(PointerValue,
                                  sourceInformation=$loc($.identifier),
                                  value=$.identifier.text)))
  field value = joinStripped($.STRING)
}

# -----------------------------------------------------------------------
# chain_fold — `seed (chained-element)*` shape with alt-dispatch on each
# element's sub-context. Generates ListAdapter.adapt(...).injectInto(seed,
# (acc, it) -> { if (it.X() != null) return EXPR; ... return ELSE; }).
#
# Inside a chain_fold body:
#   $it.X      → it.X()    (current iteration's sub-context)
#   $it.X.text → it.X().getText()
#   acc        → acc       (accumulator variable)
# -----------------------------------------------------------------------

rule simpleExpression as ValueSpecification {
  chain_fold from buildNonArrowOrEqualExpression($.nonArrowOrEqualExpression) over $.propertyOrFunctionExpression {
    alt when $it.propertyExpression {
      step buildPropertyExpression(acc, $it.propertyExpression)
    }
    alt when $it.functionExpression {
      step buildFunctionExpression(acc, $it.functionExpression)
    }
    else step acc
  }
}

# -----------------------------------------------------------------------
# grow_list — `(filtered, dispatch-mapped)` shape over a list of items.
# Each `yield EXPR` body produces a list element; items that don't match
# any alt are filtered out (collectIf semantics).
# -----------------------------------------------------------------------

# Grammar: codeBlock: programLine (END_LINE (programLine END_LINE)*)?
#          programLine: combinedExpression | letExpression
# Each program line is folded to its value spec; blank/comment-only lines drop out.
# Grammar: expressionsArray — `[ expr, expr, ... ]`
# Multiplicity is fixed to the element count on both bounds.
# Grammar: qualifiedProperty: stereotypes? taggedValues? identifier qualifiedPropertyBody COLON propertyReturnType
# Properties whose body is a lambda body (codeBlock). Stereotypes & taggedValues
# are applied as a post-build side-effect via the existing helper.
# Grammar: property: stereotypes? taggedValues? propertyName aggregation? COLON propertyReturnType defaultValue?
# Simple property (not a qualified-property body). Aggregation and defaultValue are
# optional; stereotypes/taggedValues applied via the post-build helper.
# Grammar: constraint: simpleConstraint | complexConstraint
# simpleConstraint: constraintId? combinedExpression
# complexConstraint: VALID_STRING constraintOwner? constraintExternalId?
#                    constraintFunction constraintEnforcementLevel? constraintMessage?
# Shared sourceInformation; per-alt mix of always-set and optional fields.
# Grammar: functionTypePureType: type multiplicity
# A single function-type parameter slot (name not part of the grammar).
# Grammar: columnType: mayColumnName COLON mayColumnType multiplicity?
# mayColumnName: (QUESTION | columnName); mayColumnType: (QUESTION | type)
# A column inside a relation-type literal. Name may be a bare identifier, a
# single-quoted string (stripped), or `?` (wildcard).
# Grammar: type — qualifiedName | functionType ({ ... -> T[m] }) | relationType ((c:T,...))
# The dispatcher: produces a UserDefinedGenericType wrapping the appropriate
# inner Type. Method name overrides the default convention since this rule
# corresponds to `type` in the grammar but is called `buildGenericType`
# throughout the codebase.
rule type {
  method buildGenericType
  emit UserDefinedGenericType
  alt when $.qualifiedName {
    field type = newImpl(Type_Pointer,
                          sourceInformation=$loc($.qualifiedName),
                          value=$.qualifiedName.text)
    optional field typeArguments when $.typeArguments = mapList($.typeArguments.typeOrUndefined, buildTypeOrUndefined)
    optional field multiplicityArguments when $.multiplicityArguments = mapList($.multiplicityArguments.multiplicityArgument, parseMultiplicityArgument)
    optional field typeVariableValues when $.typeVariableValues = mapList($.typeVariableValues.instanceLiteral, buildInstanceLiteral)
  }
  alt when $.CURLY_BRACKET_OPEN {
    field type = newImpl(FunctionType,
                          parameters=mapList($.functionTypePureType, buildFunctionTypePureType),
                          returnType=buildGenericType($.type),
                          returnMultiplicity=buildMultiplicity($.multiplicity))
  }
  alt when $.GROUP_OPEN {
    field type = newImpl(RelationType,
                          columns=mapList($.columnType, buildColumnType))
  }
}

rule columnType {
  emit Column
  field sourceInformation = $loc
  optional field name when $.mayColumnName.columnName = stripIfQuoted($.mayColumnName.columnName.text)
  optional field nameWildCard when $.mayColumnName.QUESTION = true
  optional field genericType when $.mayColumnType.type = buildGenericType($.mayColumnType.type)
  optional field multiplicity when $.multiplicity = buildMultiplicity($.multiplicity)
}

rule functionTypePureType {
  emit VariableExpression
  field genericType = buildGenericType($.type)
  field multiplicity = buildMultiplicity($.multiplicity)
}

rule constraint {
  emit Constraint
  shared field sourceInformation = $loc
  alt when $.simpleConstraint {
    optional field name when $.simpleConstraint.constraintId = $.simpleConstraint.constraintId.VALID_STRING.text
    field functionDefinition = newImpl(LambdaFunction,
                                        sourceInformation=$loc($.simpleConstraint),
                                        expressionSequence=listOf(buildCombinedExpression($.simpleConstraint.combinedExpression)))
  }
  alt when $.complexConstraint {
    field name = $.complexConstraint.VALID_STRING.text
    optional field owner when $.complexConstraint.constraintOwner = $.complexConstraint.constraintOwner.VALID_STRING.text
    optional field externalId when $.complexConstraint.constraintExternalId = stripQuotes($.complexConstraint.constraintExternalId.STRING.text)
    field functionDefinition = newImpl(LambdaFunction,
                                        sourceInformation=$loc($.complexConstraint.constraintFunction),
                                        expressionSequence=listOf(buildCombinedExpression($.complexConstraint.constraintFunction.combinedExpression)))
    optional field enforcementLevel when $.complexConstraint.constraintEnforcementLevel = $.complexConstraint.constraintEnforcementLevel.ENFORCEMENT_LEVEL.text
    optional field messageFunction when $.complexConstraint.constraintMessage = newImpl(LambdaFunction,
                                                                                          sourceInformation=$loc($.complexConstraint.constraintMessage),
                                                                                          expressionSequence=listOf(buildCombinedExpression($.complexConstraint.constraintMessage.combinedExpression)))
  }
}

rule property {
  emit Property
  field name = $.propertyName.text
  field sourceInformation = $loc
  field genericType = buildGenericType($.propertyReturnType.type)
  field multiplicity = buildMultiplicity($.propertyReturnType.multiplicity)
  optional field aggregation when $.aggregation = enumPointer("meta::pure::metamodel::function::property::AggregationKind", capitalize(stripParens($.aggregation.text)))
  optional field defaultValue when $.defaultValue = newImpl(LambdaFunction,
                                                            sourceInformation=$loc($.defaultValue),
                                                            expressionSequence=listOf(buildCombinedExpression($.defaultValue.combinedExpression)))
  optional field stereotypes when $.stereotypes = mapList($.stereotypes.stereotype, buildStereotype)
  optional field taggedValues when $.taggedValues = mapList($.taggedValues.taggedValue, buildTaggedValue)
}

rule qualifiedProperty {
  emit QualifiedProperty
  field name = $.identifier.text
  field sourceInformation = $loc
  field parameters = mapList($.qualifiedPropertyBody.functionVariableExpression, buildFunctionVariableExpression)
  field expressionSequence = buildCodeBlock($.qualifiedPropertyBody.codeBlock)
  field genericType = buildGenericType($.propertyReturnType.type)
  field multiplicity = buildMultiplicity($.propertyReturnType.multiplicity)
  optional field stereotypes when $.stereotypes = mapList($.stereotypes.stereotype, buildStereotype)
  optional field taggedValues when $.taggedValues = mapList($.taggedValues.taggedValue, buildTaggedValue)
}

rule expressionsArray {
  emit Collection
  field sourceInformation = $loc
  field values = mapList($.combinedExpression, buildCombinedExpression)
  field multiplicity = multBounds(count($.combinedExpression), count($.combinedExpression))
}

rule codeBlock as MutableList<ValueSpecification> {
  grow_list over $.programLine {
    alt when $it.combinedExpression {
      yield buildCombinedExpression($it.combinedExpression)
    }
    alt when $it.letExpression {
      yield buildLetExpression($it.letExpression)
    }
  }
}

rule expression as ValueSpecification {
  chain_fold from buildNonArrowOrEqualExpression($.nonArrowOrEqualExpression) over $.propertyOrFunctionExpression {
    alt when $it.propertyExpression {
      step buildPropertyExpression(acc, $it.propertyExpression)
    }
    alt when $it.functionExpression {
      step buildFunctionExpression(acc, $it.functionExpression)
    }
    else step acc
  }
}

rule instanceLiteral as AtomicValueImpl {
  alt when $.instanceLiteralToken {
    delegate $.instanceLiteralToken
  }
  alt when $.INTEGER {
    emit AtomicValue
    field sourceInformation = $loc
    field value = ifPresent($.MINUS, -parseLong($.INTEGER.text), parseLong($.INTEGER.text))
    field genericType = primitiveType("Integer")
  }
  alt when $.FLOAT {
    emit AtomicValue
    field sourceInformation = $loc
    field value = ifPresent($.MINUS, -parseDouble($.FLOAT.text), parseDouble($.FLOAT.text))
    field genericType = primitiveType("Float")
  }
  alt when $.DECIMAL {
    emit AtomicValue
    field sourceInformation = $loc
    field value = ifPresent($.MINUS, -parseDouble($.DECIMAL.text), parseDouble($.DECIMAL.text))
    field genericType = primitiveType("Decimal")
  }
  else error("Unsupported literal")
}

