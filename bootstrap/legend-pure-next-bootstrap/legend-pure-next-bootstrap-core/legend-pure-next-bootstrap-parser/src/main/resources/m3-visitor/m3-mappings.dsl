# M3 Visitor Mapping DSL
#
# Two top-level declaration forms:
#
#   rule X { … }
#     Maps to an ANTLR grammar rule `X` in M3Parser.g4. Emits
#     `buildX(M3Parser.XContext ctx)`. Top-level rules that produce a
#     packageable element via `register(...)` also get an `@Override visitX(...)`
#     wrapper that adds the built object to the `elements` accumulator.
#
#   helper buildName(ExtraT1 n1, …, CtxType ctx) [as ReturnType] { … }
#     Reusable build method that is NOT a grammar-rule visitor. The first
#     args are extra parameters (e.g. an accumulator passed from the caller);
#     the last param MUST be `CtxType ctx` — the ANTLR context type the helper
#     consumes. Emits the method with the exact name written.
#
# === Rule shape ===
# Every rule body is a `return` expression — optionally preceded by `let`
# bindings and `post` mutation statements. Object construction is *always*
# done via `newImpl(T, k=v, ...)`. There is no `emit`, `field`, `optional
# field`, `shared field`, `delegate`, or `topLevel` keyword.
#
#   rule X {
#     return newImpl(T, k=v, k=ifPresent(p, e), …)              # construct + return
#   }
#
#   rule X {
#     return register(newImpl(T, …))                            # construct, register as
#   }                                                           #   top-level element, return
#
#   rule X {                                                    # multi-alt dispatch:
#     alt when $.A { return newImpl(T1, …) }                    #   each alt returns
#     alt when $.B { return newImpl(T2, …) }                    #   its own value;
#     else error("…")                                            #   else error catches
#   }
#
#   rule X {
#     let TImpl __r = newImpl(T, …)                             # let-and-mutate:
#     __r._foo(…)                                                #   build, then patch
#     return register(__r)                                       #   computed-from-self fields
#   }
#
# === Expression sub-language ===
#   $.<rule_or_token>             →  ctx.<rule_or_token>()
#   $.<rule_or_token>.text        →  ctx.<rule_or_token>().getText()
#   $self                          →  ctx
#   $loc                           →  buildSourceInfo(ctx)
#   $loc($.X)                      →  buildSourceInfo(ctx.X())
#
# === Conditional fields ===
# Inside `newImpl(...)`, a property whose value is `ifPresent(p, e)` (2-arg)
# is skipped when `p` is false (the setter is not called). The 3-arg form
# `ifPresent(p, e1, e2)` evaluates to `e1` or `e2` as usual.
#
# === Alts and predicates ===
# `alt when <pred> { return … }` introduces a guarded branch. `$.X` reads
# `ctx.X() != null`. Multiple clauses are combined with `&&`. Alternatives
# are tested in order. `alt else { return … }` and `else error("…")` provide
# fallbacks.
#
# === Primitives (selection) ===
#   newImpl(T, k=v, …)        →  new TImpl()._k(v) — value can be ifPresent(p,e) (skip)
#   register(expr)             →  flags rule as topLevel; visit wrapper adds to `elements`
#   listOf(a, b, …)            →  Lists.mutable.with(a, b, …)
#   mapList($.X, fn)           →  ListAdapter.adapt(ctx.X()).collect(this::fn)
#   prepended(item, list)      →  Lists.mutable.with(item).withAll(list)
#   ifPresent(p, e1, e2)       →  ternary
#   parseLong / parseDouble / parseBoolean
#   stripQuotes / stripPercent / stripParens / stripIfQuoted / capitalize
#   primitiveType(name)        →  inline UserDefinedGenericType wrapping Type_Pointer
#   simpleNameOf(qn)           →  last `::`-suffix
#   packagePrefix(qn) / hasPackagePrefix(qn)
#   multBounds(lo, hi)         →  UserDefinedAdHocMultiplicity with given bounds
#   enumPointer(qn, val)
#
# === Iteration constructs (legacy block syntax) ===
# The expression-precedence ladder uses `left_fold over $.X { … }` for left-
# associative binary operators. Chain-fold and grow-list have similar shapes.
# These keep a small block syntax; everything else is the unified form above.

rule variable {
  return newImpl(VariableExpression,
                 name = $.identifier.text,
                 sourceInformation = $loc)
}

rule instanceLiteralToken {
  alt when $.INTEGER {
    return newImpl(AtomicValue,
                   sourceInformation = $loc,
                   value = parseLong($.INTEGER.text),
                   genericType = primitiveType("Integer"))
  }
  alt when $.STRING {
    return newImpl(AtomicValue,
                   sourceInformation = $loc,
                   value = stripQuotes($.STRING.text),
                   genericType = primitiveType("String"))
  }
  alt when $.FLOAT {
    return newImpl(AtomicValue,
                   sourceInformation = $loc,
                   value = parseDouble($.FLOAT.text),
                   genericType = primitiveType("Float"))
  }
  alt when $.DECIMAL {
    return newImpl(AtomicValue,
                   sourceInformation = $loc,
                   value = parseDouble($.DECIMAL.text),
                   genericType = primitiveType("Decimal"))
  }
  alt when $.BOOLEAN {
    return newImpl(AtomicValue,
                   sourceInformation = $loc,
                   value = parseBoolean($.BOOLEAN.text),
                   genericType = primitiveType("Boolean"))
  }
  alt when $.DATE {
    return newImpl(AtomicValue,
                   sourceInformation = $loc,
                   value = stripPercent($.DATE.text),
                   genericType = dateLiteralType($.DATE.text))
  }
  alt when $.STRICTTIME {
    return newImpl(AtomicValue,
                   sourceInformation = $loc,
                   value = stripPercent($.STRICTTIME.text),
                   genericType = primitiveType("StrictTime"))
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
    step newImpl(FunctionInvocation,
                 sourceInformation = $loc,
                 functionName = "or",
                 parametersValues = listOf(acc, rhs))
  }
}

rule andExpression {
  left_fold over $.equalityExpression {
    step newImpl(FunctionInvocation,
                 sourceInformation = $loc,
                 functionName = "and",
                 parametersValues = listOf(acc, rhs))
  }
}

# `==` is `equal(lhs, rhs)`; `!=` wraps the same call in `not(...)`.
rule equalityExpression {
  left_fold over $.relationalExpression {
    let ValueSpecification eq = newImpl(FunctionInvocation,
                                         sourceInformation = $loc,
                                         functionName = "equal",
                                         parametersValues = listOf(acc, rhs))
    alt when $tok = TEST_NOT_EQUAL {
      step newImpl(FunctionInvocation,
                   sourceInformation = $loc,
                   functionName = "not",
                   parametersValues = listOf(eq))
    }
    alt else {
      step eq
    }
  }
}

rule relationalExpression {
  left_fold over $.additiveExpression {
    step newImpl(FunctionInvocation,
                 sourceInformation = $loc,
                 functionName = match($tok,
                                       LESSTHAN, "lessThan",
                                       LESSTHANEQUAL, "lessThanEqual",
                                       GREATERTHAN, "greaterThan",
                                       GREATERTHANEQUAL, "greaterThanEqual"),
                 parametersValues = listOf(acc, rhs))
  }
}

rule additiveExpression {
  left_fold over $.multiplicativeExpression {
    step newImpl(FunctionInvocation,
                 sourceInformation = $loc,
                 functionName = match($tok, PLUS, "plus", MINUS, "minus"),
                 parametersValues = listOf(acc, rhs))
  }
}

rule multiplicativeExpression {
  left_fold over $.expression {
    step newImpl(FunctionInvocation,
                 sourceInformation = $loc,
                 functionName = match($tok, STAR, "times", DIVIDE, "divide"),
                 parametersValues = listOf(acc, rhs))
  }
}

# -----------------------------------------------------------------------
# Simple sub-builders. These exercise:
#   - sub-rule helper calls (buildGenericType($.type), parseMultiplicity(...))
#   - delegate rules (combinedExpression passes through to orExpression)
#   - specific-child source-info ($loc($.X))
# -----------------------------------------------------------------------

rule combinedExpression as ValueSpecification {
  return buildOrExpression($.orExpression)
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
    return newImpl(UndefinedMultiplicity)
  }
  alt when $.identifier {
    return newImpl(UserDefinedMultiplicityParameter, name = $.identifier.text)
  }
  alt else {
    return newImpl(UserDefinedAdHocMultiplicity,
                   lowerBound = newImpl(MultiplicityValue,
                                        value = ifPresent($.fromMultiplicity,
                                                          parseLong($.fromMultiplicity.text),
                                                          ifPresent($.toMultiplicity.STAR,
                                                                    parseLong("0"),
                                                                    parseLong($.toMultiplicity.text)))),
                   upperBound = ifPresent($.toMultiplicity.INTEGER, newImpl(MultiplicityValue, value = parseLong($.toMultiplicity.text))))
  }
}

rule functionVariableExpression {
  return newImpl(VariableExpression,
                 name = $.identifier.text,
                 sourceInformation = $loc,
                 genericType = buildGenericType($.type),
                 multiplicity = buildMultiplicity($.multiplicity))
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
    return buildVariable($.variable)
  }
  alt when $.instanceLiteralToken {
    return buildInstanceLiteralToken($.instanceLiteralToken)
  }
  alt when $.anyLambda {
    return buildAnyLambda($.anyLambda)
  }
  alt when $.instanceReference {
    return buildInstanceReference($.instanceReference)
  }
  alt when $.expressionInstance {
    return buildExpressionInstance($.expressionInstance)
  }
  alt when $.dsl {
    return newImpl(AtomicValue,
                   sourceInformation = $loc($.dsl),
                   genericType = primitiveType("String"),
                   value = $.dsl.DSL_TEXT.text)
  }
  alt when $.columnBuilders {
    return buildColumnBuilders($.columnBuilders)
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
helper buildAtomicTypeRef(AtomicExpressionContext ctx) {
  return newImpl(UserDefinedGenericTypeAndMultiplicityHolder,
                 sourceInformation = $loc,
                 genericType = ifPresent($.type, buildGenericType($.type), newImpl(UndefinedGenericType)),
                 multiplicity = ifPresent($.multiplicityArgument,
                                          parseMultiplicityArgument($.multiplicityArgument),
                                          ifPresent($.multiplicity,
                                                    buildMultiplicity($.multiplicity),
                                                    newImpl(UndefinedMultiplicity))))
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
  let ValueSpecification nameAtomic = newImpl(AtomicValue,
                                                sourceInformation = $loc($.columnName),
                                                genericType = primitiveType("String"),
                                                value = $.columnName.text)
  let ValueSpecification typeHolder = ifPresent(hasAny($self, type, multiplicity),
                                                  newImpl(UserDefinedGenericTypeAndMultiplicityHolder,
                                                          genericType = newImpl(UserDefinedGenericType,
                                                                                type = newImpl(RelationType,
                                                                                                columns = listOf(buildOneColSpecColumn($self))))),
                                                  newImpl(CompilerGenericTypeAndMultiplicityHolder))
  return newImpl(FunctionInvocation,
                 sourceInformation = $loc,
                 functionName = ifPresent($.anyLambda,
                                          ifPresent($.extraFunction, "aggColSpec", "funcColSpec"),
                                          "colSpec"),
                 parametersValues = ifPresent($.anyLambda,
                                              ifPresent($.extraFunction,
                                                        listOf(buildAnyLambda($.anyLambda),
                                                               buildAnyLambda($.extraFunction.anyLambda),
                                                               nameAtomic,
                                                               typeHolder),
                                                        listOf(buildAnyLambda($.anyLambda),
                                                               nameAtomic,
                                                               typeHolder)),
                                              listOf(nameAtomic, typeHolder)))
}

# A single Column for a RelationType, derived from a oneColSpec context.
# Used both for the per-column type-holder and for the combined colSpecArray holder.
helper buildOneColSpecColumn(OneColSpecContext ctx) {
  return newImpl(Column,
                 name = $.columnName.text,
                 genericType = ifPresent($.type, buildGenericType($.type)),
                 multiplicity = ifPresent($.multiplicity, buildMultiplicity($.multiplicity)))
}

# The String AtomicValue carrying a column name, used in colSpecArray.
helper buildColumnNameAtomic(OneColSpecContext ctx) as ValueSpecification {
  return newImpl(AtomicValue,
                 sourceInformation = $loc($.columnName),
                 genericType = primitiveType("String"),
                 value = $.columnName.text)
}

# The type holder for the colSpecArray case (no lambdas anywhere). Either a
# UserDefinedGenericTypeAndMultiplicityHolder wrapping a RelationType built from
# all typed/multiplied colSpecs, or a CompilerHolder when none have explicit types.
helper buildColSpecArrayHolder(ColumnBuildersContext ctx) as ValueSpecification {
  alt when anyHasAny($.oneColSpec, type, multiplicity) {
    return newImpl(UserDefinedGenericTypeAndMultiplicityHolder,
                   genericType = newImpl(UserDefinedGenericType,
                                         type = newImpl(RelationType,
                                                        columns = selectMapHasAny($.oneColSpec, type, multiplicity, oneColSpecColumn))))
  }
  alt else {
    return newImpl(CompilerGenericTypeAndMultiplicityHolder)
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

# One Union/Difference wrap step for a single typeAddSubOperation context.
# Used as the lambda body of the injectInto fold in typeWithOperation.
helper buildWrapAddSubOp(GenericType base, TypeAddSubOperationContext ctx) as GenericType {
  alt when $.addType {
    return newImpl(GenericTypeOperation,
                   operationType = enumPointer("meta::pure::metamodel::relation::GenericTypeOperationType", "Union"),
                   left = base,
                   right = buildGenericType($.addType.type))
  }
  alt else {
    return newImpl(GenericTypeOperation,
                   operationType = enumPointer("meta::pure::metamodel::relation::GenericTypeOperationType", "Difference"),
                   left = base,
                   right = buildGenericType($.subType.type))
  }
}

rule typeOrUndefined as GenericType {
  alt when $.QUESTION {
    return newImpl(UndefinedGenericType)
  }
  alt else {
    return buildTypeWithOperation($.typeWithOperation)
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
  alt when $.variable {
    return newImpl(FunctionInvocation,
                   sourceInformation = $loc,
                   functionName = "copy",
                   parametersValues = ifPresent(notEmpty($.expressionInstanceParserPropertyAssignment),
                     listOf(newImpl(VariableExpression, name = $.variable.identifier.text, sourceInformation = $loc($.variable)),
                            newImpl(Collection,
                                    sourceInformation = $loc,
                                    values = mapList($.expressionInstanceParserPropertyAssignment, buildExpressionInstanceParserPropertyAssignment),
                                    multiplicity = multBounds(count($.expressionInstanceParserPropertyAssignment), count($.expressionInstanceParserPropertyAssignment)))),
                     listOf(newImpl(VariableExpression, name = $.variable.identifier.text, sourceInformation = $loc($.variable)))))
  }
  alt else {
    return newImpl(FunctionInvocation,
                   sourceInformation = $loc,
                   functionName = "new",
                   parametersValues = ifPresent(notEmpty($.expressionInstanceParserPropertyAssignment),
                     listOf(buildExpressionInstanceNewHead($self),
                            newImpl(Collection,
                                    sourceInformation = $loc,
                                    values = mapList($.expressionInstanceParserPropertyAssignment, buildExpressionInstanceParserPropertyAssignment),
                                    multiplicity = multBounds(count($.expressionInstanceParserPropertyAssignment), count($.expressionInstanceParserPropertyAssignment)))),
                     listOf(buildExpressionInstanceNewHead($self))))
  }
}

# Builds the GenericType + Multiplicity holder that is the first param of a
# `new(...)` call. The multiplicity is always `[1]`. Type name is the
# qualifiedName when present (named `^Type(...)`) or the literal "Unknown"
# (anonymous `^(...)` form). Inner GenericType is produced by a sub-rule so
# its optional `typeArguments` / `multiplicityArguments` / `typeVariableValues`
# can stay declarative.
helper buildExpressionInstanceNewHead(ExpressionInstanceContext ctx) {
  return newImpl(UserDefinedGenericTypeAndMultiplicityHolder,
                 sourceInformation = $loc,
                 genericType = buildExpressionInstanceGenericType($self),
                 multiplicity = multBounds(1, 1))
}

helper buildExpressionInstanceGenericType(ExpressionInstanceContext ctx) {
  return newImpl(UserDefinedGenericType,
                 type = newImpl(Type_Pointer, value = ifPresent($.qualifiedName, $.qualifiedName.text, "Unknown")),
                 typeArguments = ifPresent($.typeArguments, mapList($.typeArguments.typeOrUndefined, buildTypeOrUndefined)),
                 multiplicityArguments = ifPresent($.multiplicityArguments, mapList($.multiplicityArguments.multiplicityArgument, parseMultiplicityArgument)),
                 typeVariableValues = ifPresent($.typeVariableValues, mapList($.typeVariableValues.instanceLiteral, buildInstanceLiteral)))
}

# Grammar: expressionInstanceParserPropertyAssignment: propertyName (DOT propertyName)*
#          (PLUS)? EQUAL expressionInstanceRightSide
# Each assignment becomes a `keyExpression(nameStr, rhs[, plusFlag])` invocation.
rule expressionInstanceParserPropertyAssignment {
  return newImpl(FunctionInvocation,
                 sourceInformation = $loc,
                 functionName = "keyExpression",
                 parametersValues = ifPresent($.PLUS,
                   listOf(
                     newImpl(AtomicValue, sourceInformation = $loc, genericType = primitiveType("String"), value = joinTextWith($.propertyName, ".")),
                     buildExpressionInstanceRightSide($.expressionInstanceRightSide),
                     newImpl(AtomicValue, sourceInformation = $loc, genericType = primitiveType("Boolean"), value = true)),
                   listOf(
                     newImpl(AtomicValue, sourceInformation = $loc, genericType = primitiveType("String"), value = joinTextWith($.propertyName, ".")),
                     buildExpressionInstanceRightSide($.expressionInstanceRightSide))))
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
  return newImpl(DotApplication,
                 sourceInformation = $loc,
                 functionName = $.propertyName.text,
                 parametersValues = ifPresent($.functionExpressionParameters,
                                              prepended(receiver, mapList($.functionExpressionParameters.combinedExpression, buildCombinedExpression)),
                                              listOf(receiver)))
}

rule lambdaParam {
  return newImpl(VariableExpression,
                 name = $.identifier.text,
                 sourceInformation = ifPresent($.lambdaParamType, $loc),
                 genericType = ifPresent($.lambdaParamType, buildGenericType($.lambdaParamType.type)),
                 multiplicity = ifPresent($.lambdaParamType, buildMultiplicity($.lambdaParamType.multiplicity)))
}

# Grammar: lambdaFunction: LBRACE (lambdaParam (COMMA lambdaParam)*)? lambdaPipe RBRACE
# Builds the LambdaFunction value directly (the wrapping AtomicValue is done by
# the caller in `anyLambda`).
rule lambdaFunction {
  return newImpl(LambdaFunction,
                 sourceInformation = $loc,
                 parameters = mapList($.lambdaParam, buildLambdaParam),
                 expressionSequence = buildCodeBlock($.lambdaPipe.codeBlock))
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
  return newImpl(FunctionInvocation,
                 sourceInformation = $loc,
                 functionName = "not",
                 parametersValues = listOf(buildSimpleExpression($.simpleExpression)))
}

# Grammar: nonArrowOrEqualExpression: atomicExpression | expressionsArray |
#          notExpression | signedExpression | sliceExpression | combinedExpression
# Each branch is a delegate to its sub-rule's build method.
rule nonArrowOrEqualExpression as ValueSpecification {
  alt when $.atomicExpression {
    return buildAtomicExpression($.atomicExpression)
  }
  alt when $.expressionsArray {
    return buildExpressionsArray($.expressionsArray)
  }
  alt when $.notExpression {
    return buildNotExpression($.notExpression)
  }
  alt when $.signedExpression {
    return buildSignedExpression($.signedExpression)
  }
  alt when $.sliceExpression {
    return buildSliceExpression($.sliceExpression)
  }
  alt when $.combinedExpression {
    return buildCombinedExpression($.combinedExpression)
  }
  else error("Unexpected nonArrowOrEqualExpression")
}

# Grammar: signedExpression: (MINUS | PLUS) simpleExpression
# When MINUS, wrap the inner in a `minus(...)` call. When PLUS, pass-through.
rule signedExpression as ValueSpecification {
  alt when $.MINUS {
    return newImpl(FunctionInvocation,
                   sourceInformation = $loc,
                   functionName = "minus",
                   parametersValues = listOf(buildSimpleExpression($.simpleExpression)))
  }
  alt else {
    return buildSimpleExpression($.simpleExpression)
  }
}

rule sliceExpression as ValueSpecification {
  return newImpl(FunctionInvocation,
                 sourceInformation = $loc,
                 functionName = "slice",
                 parametersValues = mapList($.expression, buildExpression))
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
  return newImpl(FunctionInvocation,
                 sourceInformation = $loc,
                 functionName = "letFunction",
                 parametersValues = listOf(
                   newImpl(AtomicValue,
                           sourceInformation = $loc($.identifier),
                           genericType = primitiveType("String"),
                           value = $.identifier.text),
                   buildCombinedExpression($.combinedExpression)))
}

# Top-level element rule: primitiveDefinition.
# Grammar: primitiveDefinition: PRIMITIVE stereotypes? taggedValues? qualifiedName
#                              typeVariableParameters? EXTENDS type constraints?
# Single extends type (wrapped in a 1-element list of generalizations).
rule primitiveDefinition {
  return register(newImpl(PrimitiveType,
    sourceInformation = $loc,
    name = simpleNameOf($.qualifiedName.text),
    package = ifPresent(hasPackagePrefix($.qualifiedName.text),
                        newImpl(Package_Pointer, value = packagePrefix($.qualifiedName.text))),
    typeVariables = ifPresent($.typeVariableParameters,
                              mapList($.typeVariableParameters.functionVariableExpression, buildFunctionVariableExpression)),
    generalizations = ifPresent($.type, listOf(buildClassGeneralization($.type))),
    constraints = ifPresent($.constraints, mapList($.constraints.constraint, buildConstraint)),
    stereotypes = ifPresent($.stereotypes, mapList($.stereotypes.stereotype, buildStereotype)),
    taggedValues = ifPresent($.taggedValues, mapList($.taggedValues.taggedValue, buildTaggedValue))))
}

# Top-level element rule: enumDefinition.
# Grammar: enumDefinition: ENUM stereotypes? taggedValues? qualifiedName
#                          { enumValue (COMMA enumValue)* }
# Each enumValue maps to a Property carrying its own annotations.
rule enumDefinition {
  return register(newImpl(Enumeration,
    sourceInformation = $loc,
    name = simpleNameOf($.qualifiedName.text),
    package = ifPresent(hasPackagePrefix($.qualifiedName.text),
                        newImpl(Package_Pointer, value = packagePrefix($.qualifiedName.text))),
    properties = mapList($.enumValue, buildEnumValue),
    stereotypes = ifPresent($.stereotypes, mapList($.stereotypes.stereotype, buildStereotype)),
    taggedValues = ifPresent($.taggedValues, mapList($.taggedValues.taggedValue, buildTaggedValue))))
}

# Grammar: enumValue: stereotypes? taggedValues? identifier
rule enumValue {
  return newImpl(Property,
                 name = $.identifier.text,
                 sourceInformation = $loc,
                 stereotypes = ifPresent($.stereotypes, mapList($.stereotypes.stereotype, buildStereotype)),
                 taggedValues = ifPresent($.taggedValues, mapList($.taggedValues.taggedValue, buildTaggedValue)))
}

# Grammar: typeParameter: identifier
rule typeParameter {
  return newImpl(TypeParameter, name = $.identifier.text)
}

# Grammar: typeParameterWithVariance: MINUS? identifier
rule typeParameterWithVariance {
  return newImpl(TypeParameter,
                 name = $.identifier.text,
                 contravariant = ifPresent($.MINUS, true))
}

# A single identifier from a multiplicity-parameters list becomes a
# UserDefinedMultiplicityParameter.
helper buildMultParamDef(IdentifierContext ctx) {
  return newImpl(UserDefinedMultiplicityParameter, name = $self.getText())
}

# Top-level element rule: classDefinition.
# Grammar: classDefinition: CLASS stereotypes? taggedValues? qualifiedName
#                          typeParametersWithVarianceAndMultiplicityParameters?
#                          typeVariableParameters? (EXTENDS type (COMMA type)*)?
#                          constraints? classBody?
rule classDefinition {
  return register(newImpl(Class,
    sourceInformation = $loc,
    name = simpleNameOf($.qualifiedName.text),
    package = ifPresent(hasPackagePrefix($.qualifiedName.text),
                        newImpl(Package_Pointer, value = packagePrefix($.qualifiedName.text))),
    typeParameters = ifPresent($.typeParametersWithVarianceAndMultiplicityParameters.typeParametersWithVariance,
                               mapList($.typeParametersWithVarianceAndMultiplicityParameters.typeParametersWithVariance.typeParameterWithVariance, buildTypeParameterWithVariance)),
    multiplicityParameters = ifPresent($.typeParametersWithVarianceAndMultiplicityParameters.multiplictyParameters,
                                        mapList($.typeParametersWithVarianceAndMultiplicityParameters.multiplictyParameters.identifier, buildMultParamDef)),
    typeVariables = ifPresent($.typeVariableParameters,
                              mapList($.typeVariableParameters.functionVariableExpression, buildFunctionVariableExpression)),
    generalizations = ifPresent(notEmpty($.type), mapList($.type, classGeneralization)),
    constraints = ifPresent($.constraints, mapList($.constraints.constraint, buildConstraint)),
    properties = ifPresent($.classBody.properties, mapList($.classBody.properties.property, buildProperty)),
    qualifiedProperties = ifPresent($.classBody.properties, mapList($.classBody.properties.qualifiedProperty, buildQualifiedProperty)),
    stereotypes = ifPresent($.stereotypes, mapList($.stereotypes.stereotype, buildStereotype)),
    taggedValues = ifPresent($.taggedValues, mapList($.taggedValues.taggedValue, buildTaggedValue))))
}

# Wrap a TypeContext into a Generalization (general type + source info).
helper buildClassGeneralization(TypeContext ctx) {
  return newImpl(Generalization,
                 general = buildGenericType($self),
                 sourceInformation = $loc)
}

# Top-level element rule: functionDefinition.
# Grammar: functionDefinition: FUNCTION stereotypes? taggedValues? qualifiedName
#                              typeAndMultiplicityParameters? functionTypeSignature
#                              constraints? codeBlock
# Uses a let + mutation form because `_name` must finally be derived from the
# fully-built function (buildId hashes the parameter/return signature) and so
# can't be computed inline as part of the newImpl literal.
rule functionDefinition {
  let UserDefinedFunctionImpl __r = newImpl(UserDefinedFunction,
    sourceInformation = $loc,
    package = ifPresent(hasPackagePrefix($.qualifiedName.text),
                        newImpl(Package_Pointer, value = packagePrefix($.qualifiedName.text))),
    functionName = simpleNameOf($.qualifiedName.text),
    typeParameters = ifPresent($.typeAndMultiplicityParameters.typeParameters,
                               mapList($.typeAndMultiplicityParameters.typeParameters.typeParameter, buildTypeParameter)),
    multiplicityParameters = ifPresent($.typeAndMultiplicityParameters.multiplictyParameters,
                                        mapList($.typeAndMultiplicityParameters.multiplictyParameters.identifier, buildMultParamDef)),
    parameters = mapList($.functionTypeSignature.functionVariableExpression, buildFunctionVariableExpression),
    returnGenericType = buildGenericType($.functionTypeSignature.type),
    returnMultiplicity = buildMultiplicity($.functionTypeSignature.multiplicity),
    preConstraints = ifPresent($.constraints, filterMapNot($.constraints.constraint, "$return", buildConstraint)),
    postConstraints = ifPresent($.constraints, filterMap($.constraints.constraint, "$return", buildConstraint)),
    expressionSequence = buildCodeBlock($.codeBlock),
    stereotypes = ifPresent($.stereotypes, mapList($.stereotypes.stereotype, buildStereotype)),
    taggedValues = ifPresent($.taggedValues, mapList($.taggedValues.taggedValue, buildTaggedValue)))
  post __r._name(_G_PackageableFunction.buildId(__r))
  return register(__r)
}

# Top-level element rule: nativeFunction.
# Grammar: nativeFunction: NATIVE FUNCTION stereotypes? taggedValues? qualifiedName
#                         typeAndMultiplicityParameters? functionTypeSignature
# Same shape as functionDefinition minus the body and constraints.
rule nativeFunction {
  let NativeFunctionImpl __r = newImpl(NativeFunction,
    sourceInformation = $loc,
    package = ifPresent(hasPackagePrefix($.qualifiedName.text),
                        newImpl(Package_Pointer, value = packagePrefix($.qualifiedName.text))),
    functionName = simpleNameOf($.qualifiedName.text),
    typeParameters = ifPresent($.typeAndMultiplicityParameters.typeParameters,
                               mapList($.typeAndMultiplicityParameters.typeParameters.typeParameter, buildTypeParameter)),
    multiplicityParameters = ifPresent($.typeAndMultiplicityParameters.multiplictyParameters,
                                        mapList($.typeAndMultiplicityParameters.multiplictyParameters.identifier, buildMultParamDef)),
    parameters = mapList($.functionTypeSignature.functionVariableExpression, buildFunctionVariableExpression),
    returnGenericType = buildGenericType($.functionTypeSignature.type),
    returnMultiplicity = buildMultiplicity($.functionTypeSignature.multiplicity),
    stereotypes = ifPresent($.stereotypes, mapList($.stereotypes.stereotype, buildStereotype)),
    taggedValues = ifPresent($.taggedValues, mapList($.taggedValues.taggedValue, buildTaggedValue)))
  post __r._name(_G_PackageableFunction.buildId(__r))
  return register(__r)
}

# Top-level element rule: association.
# Grammar: association: ASSOCIATION stereotypes? taggedValues? qualifiedName associationBody?
# associationBody contains properties() with property() and qualifiedProperty() lists.
rule association {
  return register(newImpl(Association,
    sourceInformation = $loc,
    name = simpleNameOf($.qualifiedName.text),
    package = ifPresent(hasPackagePrefix($.qualifiedName.text),
                        newImpl(Package_Pointer, value = packagePrefix($.qualifiedName.text))),
    properties = ifPresent($.associationBody.properties, mapList($.associationBody.properties.property, buildProperty)),
    qualifiedProperties = ifPresent($.associationBody.properties, mapList($.associationBody.properties.qualifiedProperty, buildQualifiedProperty)),
    stereotypes = ifPresent($.stereotypes, mapList($.stereotypes.stereotype, buildStereotype)),
    taggedValues = ifPresent($.taggedValues, mapList($.taggedValues.taggedValue, buildTaggedValue))))
}

# Top-level element rule: profile.
# Grammar: profile: PROFILE qualifiedName CURLY_BRACKET_OPEN stereotypeDefinitions?
#                   tagDefinitions? CURLY_BRACKET_CLOSE
rule profile {
  return register(newImpl(Profile,
    sourceInformation = $loc,
    name = simpleNameOf($.qualifiedName.text),
    package = ifPresent(hasPackagePrefix($.qualifiedName.text),
                        newImpl(Package_Pointer, value = packagePrefix($.qualifiedName.text))),
    p_stereotypes = ifPresent($.stereotypeDefinitions, mapList($.stereotypeDefinitions.identifier, profileStereotypeDef)),
    p_tags = ifPresent($.tagDefinitions, mapList($.tagDefinitions.identifier, profileTagDef))))
}

# Convert an identifier context to a stereotype / tag definition (name + srcInfo).
helper buildProfileStereotypeDef(IdentifierContext ctx) {
  return newImpl(Stereotype,
                 sourceInformation = $loc,
                 value = $self.getText())
}

helper buildProfileTagDef(IdentifierContext ctx) {
  return newImpl(Tag,
                 sourceInformation = $loc,
                 value = $self.getText())
}

# Grammar: stereotype: qualifiedName DOT identifier
# qualifiedName = profile path, identifier = stereotype name
rule stereotype {
  return newImpl(Stereotype_Pointer,
                 sourceInformation = $loc($.qualifiedName),
                 value = $.qualifiedName.text,
                 extraPointerValues = listOf(
                   newImpl(PointerValue,
                           sourceInformation = $loc($.identifier),
                           value = $.identifier.text)))
}

# Grammar: taggedValue: qualifiedName DOT identifier EQUAL STRING (PLUS STRING)*
# Multiple STRINGs are concatenated (after quote-stripping).
rule taggedValue {
  return newImpl(TaggedValue,
                 tag = newImpl(Tag_Pointer,
                               sourceInformation = $loc($.qualifiedName),
                               value = $.qualifiedName.text,
                               extraPointerValues = listOf(
                                   newImpl(PointerValue,
                                           sourceInformation = $loc($.identifier),
                                           value = $.identifier.text))),
                 value = joinStripped($.STRING))
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
  alt when $.qualifiedName {
    return newImpl(UserDefinedGenericType,
                   type = newImpl(Type_Pointer,
                                  sourceInformation = $loc($.qualifiedName),
                                  value = $.qualifiedName.text),
                   typeArguments = ifPresent($.typeArguments, mapList($.typeArguments.typeOrUndefined, buildTypeOrUndefined)),
                   multiplicityArguments = ifPresent($.multiplicityArguments, mapList($.multiplicityArguments.multiplicityArgument, parseMultiplicityArgument)),
                   typeVariableValues = ifPresent($.typeVariableValues, mapList($.typeVariableValues.instanceLiteral, buildInstanceLiteral)))
  }
  alt when $.CURLY_BRACKET_OPEN {
    return newImpl(UserDefinedGenericType,
                   type = newImpl(FunctionType,
                                  parameters = mapList($.functionTypePureType, buildFunctionTypePureType),
                                  returnType = buildGenericType($.type),
                                  returnMultiplicity = buildMultiplicity($.multiplicity)))
  }
  alt when $.GROUP_OPEN {
    return newImpl(UserDefinedGenericType,
                   type = newImpl(RelationType,
                                  columns = mapList($.columnType, buildColumnType)))
  }
}

rule columnType {
  return newImpl(Column,
                 sourceInformation = $loc,
                 name = ifPresent($.mayColumnName.columnName, stripIfQuoted($.mayColumnName.columnName.text)),
                 nameWildCard = ifPresent($.mayColumnName.QUESTION, true),
                 genericType = ifPresent($.mayColumnType.type, buildGenericType($.mayColumnType.type)),
                 multiplicity = ifPresent($.multiplicity, buildMultiplicity($.multiplicity)))
}

rule functionTypePureType {
  return newImpl(VariableExpression,
                 genericType = buildGenericType($.type),
                 multiplicity = buildMultiplicity($.multiplicity))
}

rule constraint {
  alt when $.simpleConstraint {
    return newImpl(Constraint,
                   sourceInformation = $loc,
                   name = ifPresent($.simpleConstraint.constraintId, $.simpleConstraint.constraintId.VALID_STRING.text),
                   functionDefinition = newImpl(LambdaFunction,
                                                sourceInformation = $loc($.simpleConstraint),
                                                expressionSequence = listOf(buildCombinedExpression($.simpleConstraint.combinedExpression))))
  }
  alt when $.complexConstraint {
    return newImpl(Constraint,
                   sourceInformation = $loc,
                   name = $.complexConstraint.VALID_STRING.text,
                   owner = ifPresent($.complexConstraint.constraintOwner, $.complexConstraint.constraintOwner.VALID_STRING.text),
                   externalId = ifPresent($.complexConstraint.constraintExternalId, stripQuotes($.complexConstraint.constraintExternalId.STRING.text)),
                   functionDefinition = newImpl(LambdaFunction,
                                                sourceInformation = $loc($.complexConstraint.constraintFunction),
                                                expressionSequence = listOf(buildCombinedExpression($.complexConstraint.constraintFunction.combinedExpression))),
                   enforcementLevel = ifPresent($.complexConstraint.constraintEnforcementLevel, $.complexConstraint.constraintEnforcementLevel.ENFORCEMENT_LEVEL.text),
                   messageFunction = ifPresent($.complexConstraint.constraintMessage,
                                                newImpl(LambdaFunction,
                                                        sourceInformation = $loc($.complexConstraint.constraintMessage),
                                                        expressionSequence = listOf(buildCombinedExpression($.complexConstraint.constraintMessage.combinedExpression)))))
  }
}

rule property {
  return newImpl(Property,
                 name = $.propertyName.text,
                 sourceInformation = $loc,
                 genericType = buildGenericType($.propertyReturnType.type),
                 multiplicity = buildMultiplicity($.propertyReturnType.multiplicity),
                 aggregation = ifPresent($.aggregation,
                                          enumPointer("meta::pure::metamodel::function::property::AggregationKind", capitalize(stripParens($.aggregation.text)))),
                 defaultValue = ifPresent($.defaultValue,
                                           newImpl(LambdaFunction,
                                                   sourceInformation = $loc($.defaultValue),
                                                   expressionSequence = listOf(buildCombinedExpression($.defaultValue.combinedExpression)))),
                 stereotypes = ifPresent($.stereotypes, mapList($.stereotypes.stereotype, buildStereotype)),
                 taggedValues = ifPresent($.taggedValues, mapList($.taggedValues.taggedValue, buildTaggedValue)))
}

rule qualifiedProperty {
  return newImpl(QualifiedProperty,
                 name = $.identifier.text,
                 sourceInformation = $loc,
                 parameters = mapList($.qualifiedPropertyBody.functionVariableExpression, buildFunctionVariableExpression),
                 expressionSequence = buildCodeBlock($.qualifiedPropertyBody.codeBlock),
                 genericType = buildGenericType($.propertyReturnType.type),
                 multiplicity = buildMultiplicity($.propertyReturnType.multiplicity),
                 stereotypes = ifPresent($.stereotypes, mapList($.stereotypes.stereotype, buildStereotype)),
                 taggedValues = ifPresent($.taggedValues, mapList($.taggedValues.taggedValue, buildTaggedValue)))
}

rule expressionsArray {
  return newImpl(Collection,
                 sourceInformation = $loc,
                 values = mapList($.combinedExpression, buildCombinedExpression),
                 multiplicity = multBounds(count($.combinedExpression), count($.combinedExpression)))
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
    return buildInstanceLiteralToken($.instanceLiteralToken)
  }
  alt when $.INTEGER {
    return newImpl(AtomicValue,
                   sourceInformation = $loc,
                   value = ifPresent($.MINUS, -parseLong($.INTEGER.text), parseLong($.INTEGER.text)),
                   genericType = primitiveType("Integer"))
  }
  alt when $.FLOAT {
    return newImpl(AtomicValue,
                   sourceInformation = $loc,
                   value = ifPresent($.MINUS, -parseDouble($.FLOAT.text), parseDouble($.FLOAT.text)),
                   genericType = primitiveType("Float"))
  }
  alt when $.DECIMAL {
    return newImpl(AtomicValue,
                   sourceInformation = $loc,
                   value = ifPresent($.MINUS, -parseDouble($.DECIMAL.text), parseDouble($.DECIMAL.text)),
                   genericType = primitiveType("Decimal"))
  }
  else error("Unsupported literal")
}

