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
#     alt when $ctx.A { return newImpl(T1, …) }                    #   each alt returns
#     alt when $ctx.B { return newImpl(T2, …) }                    #   its own value;
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
#   $ctx                          →  ctx
#   $ctx.<rule_or_token>          →  ctx.<rule_or_token>()
#   $ctx.<rule_or_token>.<sub>    →  ctx.<rule_or_token>().<sub>()
#   $ctx.<rule_or_token>.text     →  ctx.<rule_or_token>().getText()
#   buildSourceInfo($ctx)         →  buildSourceInfo(ctx)            (regular method call)
#   buildSourceInfo($ctx.X)       →  buildSourceInfo(ctx.X())        (regular method call)
#
# === Conditional fields ===
# Inside `newImpl(...)`, a property whose value is `ifPresent(p, e)` (2-arg)
# is skipped when `p` is false (the setter is not called). The 3-arg form
# `ifPresent(p, e1, e2)` evaluates to `e1` or `e2` as usual.
#
# === Alts and predicates ===
# `alt when <pred> { return … }` introduces a guarded branch. `$ctx.X` reads
# `ctx.X() != null`. Multiple clauses are combined with `&&`. Alternatives
# are tested in order. `alt else { return … }` and `else error("…")` provide
# fallbacks.
#
# === Primitives (selection) ===
#   newImpl(T, k=v, …)        →  new TImpl()._k(v) — value can be ifPresent(p,e) (skip)
#   register(expr)             →  flags rule as topLevel; visit wrapper adds to `elements`
#   listOf(a, b, …)            →  Lists.mutable.with(a, b, …)
#   mapList($ctx.X, fn)           →  ListAdapter.adapt(ctx.X()).collect(this::fn)
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
# The expression-precedence ladder uses `left_fold over $ctx.X { … }` for left-
# associative binary operators. Chain-fold and grow-list have similar shapes.
# These keep a small block syntax; everything else is the unified form above.

rule variable {
  return newImpl(VariableExpression,
                 name = $ctx.identifier.text,
                 sourceInformation = buildSourceInfo($ctx))
}

rule instanceLiteralToken {
  alt when $ctx.INTEGER {
    return newImpl(AtomicValue,
                   sourceInformation = buildSourceInfo($ctx),
                   value = parseLong($ctx.INTEGER.text),
                   genericType = primitiveType("Integer"))
  }
  alt when $ctx.STRING {
    return newImpl(AtomicValue,
                   sourceInformation = buildSourceInfo($ctx),
                   value = stripQuotes($ctx.STRING.text),
                   genericType = primitiveType("String"))
  }
  # Triple-quoted multi-line string literal — `'''body'''`. Same AST shape as
  # STRING (an AtomicValue of String type), the body is the lexed text with
  # the 3-char `'''` opening + closing delimiters stripped, newlines preserved.
  alt when $ctx.STRING_TRIPLE {
    return newImpl(AtomicValue,
                   sourceInformation = buildSourceInfo($ctx),
                   value = stripTripleQuotes($ctx.STRING_TRIPLE.text),
                   genericType = primitiveType("String"))
  }
  alt when $ctx.FLOAT {
    return newImpl(AtomicValue,
                   sourceInformation = buildSourceInfo($ctx),
                   value = parseDouble($ctx.FLOAT.text),
                   genericType = primitiveType("Float"))
  }
  alt when $ctx.DECIMAL {
    # DECIMAL literals are Pure `Decimal` (arbitrary-precision). parseDecimal
    # emits `new java.math.BigDecimal(text)`; parseDouble would route through
    # IEEE 754 and lose precision (e.g. 19.905d → 19.904999999999998d).
    return newImpl(AtomicValue,
                   sourceInformation = buildSourceInfo($ctx),
                   value = parseDecimal($ctx.DECIMAL.text),
                   genericType = primitiveType("Decimal"))
  }
  alt when $ctx.BOOLEAN {
    return newImpl(AtomicValue,
                   sourceInformation = buildSourceInfo($ctx),
                   value = parseBoolean($ctx.BOOLEAN.text),
                   genericType = primitiveType("Boolean"))
  }
  alt when $ctx.DATE {
    return newImpl(AtomicValue,
                   sourceInformation = buildSourceInfo($ctx),
                   value = stripPercent($ctx.DATE.text),
                   genericType = dateLiteralType($ctx.DATE.text))
  }
  alt when $ctx.STRICTTIME {
    return newImpl(AtomicValue,
                   sourceInformation = buildSourceInfo($ctx),
                   value = stripPercent($ctx.STRICTTIME.text),
                   genericType = primitiveType("StrictTime"))
  }
  else error("Unsupported literal token")
}

# -----------------------------------------------------------------------
# Operator-precedence ladder (or > and > equality > relational > additive
# > multiplicative). Each rule left-folds over its child rule, emitting
# binary FunctionInvocation calls per operator token between operands.
#
# `left_fold over $ctx.X { ... }` generates:
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
  left_fold over $ctx.andExpression {
    step newImpl(FunctionInvocation,
                 sourceInformation = buildOpSourceInfo($tok, acc, $rhsCtx),
                 functionName = "or",
                 parametersValues = listOf(acc, rhs))
  }
}

rule andExpression {
  left_fold over $ctx.equalityExpression {
    step newImpl(FunctionInvocation,
                 sourceInformation = buildOpSourceInfo($tok, acc, $rhsCtx),
                 functionName = "and",
                 parametersValues = listOf(acc, rhs))
  }
}

# `==` is `equal(lhs, rhs)`; `!=` wraps the same call in `not(...)`.
rule equalityExpression {
  left_fold over $ctx.relationalExpression {
    let ValueSpecification eq = newImpl(FunctionInvocation,
                                         sourceInformation = buildOpSourceInfo($tok, acc, $rhsCtx),
                                         functionName = "equal",
                                         parametersValues = listOf(acc, rhs))
    alt when $tok = TEST_NOT_EQUAL {
      step newImpl(FunctionInvocation,
                   sourceInformation = buildOpSourceInfo($tok, acc, $rhsCtx),
                   functionName = "not",
                   parametersValues = listOf(eq))
    }
    alt else {
      step eq
    }
  }
}

rule relationalExpression {
  left_fold over $ctx.additiveExpression {
    step newImpl(FunctionInvocation,
                 sourceInformation = buildOpSourceInfo($tok, acc, $rhsCtx),
                 functionName = match($tok,
                                       LESSTHAN, "lessThan",
                                       LESSTHANEQUAL, "lessThanEqual",
                                       GREATERTHAN, "greaterThan",
                                       GREATERTHANEQUAL, "greaterThanEqual"),
                 parametersValues = listOf(acc, rhs))
  }
}

rule additiveExpression {
  left_fold over $ctx.multiplicativeExpression {
    step newImpl(FunctionInvocation,
                 sourceInformation = buildOpSourceInfo($tok, acc, $rhsCtx),
                 functionName = match($tok, PLUS, "plus", MINUS, "minus"),
                 parametersValues = listOf(acc, rhs))
  }
}

rule multiplicativeExpression {
  left_fold over $ctx.expression {
    step newImpl(FunctionInvocation,
                 sourceInformation = buildOpSourceInfo($tok, acc, $rhsCtx),
                 functionName = match($tok, STAR, "times", DIVIDE, "divide"),
                 parametersValues = listOf(acc, rhs))
  }
}

# -----------------------------------------------------------------------
# Simple sub-builders. These exercise:
#   - sub-rule helper calls (buildGenericType($ctx.type), parseMultiplicity(...))
#   - delegate rules (combinedExpression passes through to orExpression)
#   - specific-child source-info (buildSourceInfo($ctx.X))
# -----------------------------------------------------------------------

rule combinedExpression as ValueSpecification {
  return buildOrExpression($ctx.orExpression)
}

# Grammar: multiplicity: BRACKET_OPEN multiplicityArgument BRACKET_CLOSE
# Pass-through: the structured multiplicity-argument context is what carries the
# actual value (identifier, `?`, or bounds). Avoids re-parsing the bracketed
# text representation back into a structure.
rule multiplicity as Multiplicity_Protocol {
  return parseMultiplicityArgument($ctx.multiplicityArgument)
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
  alt when $ctx.QUESTION {
    return newImpl(UndefinedMultiplicity)
  }
  alt when $ctx.identifier {
    return newImpl(UserDefinedMultiplicityParameter, name = $ctx.identifier.text)
  }
  alt else {
    return newImpl(UserDefinedAdHocMultiplicity,
                   lowerBound = newImpl(MultiplicityValue,
                                        value = ifPresent($ctx.fromMultiplicity,
                                                          parseLong($ctx.fromMultiplicity.text),
                                                          ifPresent($ctx.toMultiplicity.STAR,
                                                                    parseLong("0"),
                                                                    parseLong($ctx.toMultiplicity.text)))),
                   upperBound = ifPresent($ctx.toMultiplicity.INTEGER, newImpl(MultiplicityValue, value = parseLong($ctx.toMultiplicity.text))))
  }
}

rule functionVariableExpression {
  return newImpl(VariableExpression,
                 name = $ctx.identifier.text,
                 sourceInformation = buildSourceInfo($ctx),
                 genericType = buildGenericType($ctx.type),
                 multiplicity = buildMultiplicity($ctx.multiplicity))
}

# -----------------------------------------------------------------------
# Per-alt return/delegate forms:
#   - `as ReturnType` on the rule declares the method's return type.
#   - inside an alt: `return EXPR` returns the value of EXPR.
#   - inside an alt: `delegate $ctx.X` returns buildX(ctx.X()).
#   - inside an alt: `emit T` overrides the rule-level emit type for that alt.
# -----------------------------------------------------------------------

# Grammar: atomicExpression: variable | instanceLiteralToken | anyLambda | instanceReference
#                          | expressionInstance | dsl | columnBuilders | parentReference | AT ...
# A multi-way dispatcher: simple delegates for most sub-rules, an inline DSL block
# alternative, and AT (TypeRef) and columnBuilders dispatched to their own rules.
rule atomicExpression as ValueSpecification {
  alt when $ctx.variable {
    return buildVariable($ctx.variable)
  }
  alt when $ctx.instanceLiteralToken {
    return buildInstanceLiteralToken($ctx.instanceLiteralToken)
  }
  alt when $ctx.anyLambda {
    return buildAnyLambda($ctx.anyLambda)
  }
  alt when $ctx.instanceReference {
    return buildInstanceReference($ctx.instanceReference)
  }
  alt when $ctx.expressionInstance {
    return buildExpressionInstance($ctx.expressionInstance)
  }
  alt when $ctx.dsl {
    return newImpl(AtomicValue,
                   sourceInformation = buildSourceInfo($ctx.dsl),
                   genericType = primitiveType("String"),
                   value = $ctx.dsl.DSL_TEXT.text)
  }
  alt when $ctx.columnBuilders {
    return buildColumnBuilders($ctx.columnBuilders)
  }
  alt when $ctx.parentReference {
    return buildParentReference($ctx.parentReference)
  }
  alt when $ctx.AT {
    return buildAtomicTypeRef($ctx)
  }
  else error("Unsupported atomicExpression")
}

# Build a VariableExpression-style AST for `~`, `~.~`, `~.foo`, `~.~.foo.bar`, etc.
# The tilde-only prefix becomes a VariableExpression whose name is the literal
# tilde sequence ("~", "~.~", …) — the compiler binds these names in the
# enclosing `^X(...)` scope so they carry the correct static type.
# Each propertyName after the tildes is wrapped as a DotApplication around the
# growing receiver, mirroring how `$x.foo.bar` parses.
rule parentReference as ValueSpecification {
  chain_fold from newImpl(VariableExpression,
                          sourceInformation=buildSourceInfo($ctx),
                          name=joinTextWith($ctx.TILDE, "."))
                over $ctx.propertyName {
    else step newImpl(DotApplication,
                       sourceInformation=buildSourceInfo($ctx),
                       functionName=$it.text,
                       parametersValues=listOf(acc))
  }
}

# Grammar: `@Type[mul]` / `@Type|mul` / `@Type` / `@|mul` / `@[mul]` — a "TypeHolder"
# value-spec that names a type and/or a multiplicity. Missing halves default to
# the Undefined variants. Built as a separate rule so the conditional defaults
# stay declarative.
helper buildAtomicTypeRef(AtomicExpressionContext ctx) {
  return newImpl(UserDefinedGenericTypeAndMultiplicityHolder,
                 sourceInformation = buildSourceInfo($ctx),
                 genericType = ifPresent($ctx.type, buildGenericType($ctx.type), newImpl(UndefinedGenericType)),
                 multiplicity = ifPresent($ctx.multiplicityArgument,
                                          parseMultiplicityArgument($ctx.multiplicityArgument),
                                          ifPresent($ctx.multiplicity,
                                                    buildMultiplicity($ctx.multiplicity),
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
  alt when $ctx.BRACKET_OPEN && anyHas($ctx.oneColSpec, extraFunction) {
    return newImpl(FunctionInvocation,
                   sourceInformation=buildSourceInfo($ctx),
                   functionName="aggColSpecArray",
                   parametersValues=listOf(
                     newImpl(Collection,
                             values=mapList($ctx.oneColSpec, buildOneColSpec),
                             multiplicity=multBounds(count($ctx.oneColSpec), count($ctx.oneColSpec))),
                     newImpl(CompilerGenericTypeAndMultiplicityHolder)))
  }
  alt when $ctx.BRACKET_OPEN && anyHas($ctx.oneColSpec, anyLambda) {
    return newImpl(FunctionInvocation,
                   sourceInformation=buildSourceInfo($ctx),
                   functionName="funcColSpecArray",
                   parametersValues=listOf(
                     newImpl(Collection,
                             values=mapList($ctx.oneColSpec, buildOneColSpec),
                             multiplicity=multBounds(count($ctx.oneColSpec), count($ctx.oneColSpec))),
                     newImpl(CompilerGenericTypeAndMultiplicityHolder)))
  }
  alt when $ctx.BRACKET_OPEN {
    return newImpl(FunctionInvocation,
                   sourceInformation=buildSourceInfo($ctx),
                   functionName="colSpecArray",
                   parametersValues=listOf(
                     newImpl(Collection,
                             values=mapList($ctx.oneColSpec, buildColumnNameAtomic),
                             multiplicity=multBounds(count($ctx.oneColSpec), count($ctx.oneColSpec))),
                     buildColSpecArrayHolder($ctx)))
  }
  alt else {
    return buildOneColSpec(firstOf($ctx.oneColSpec))
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
                                                sourceInformation = buildSourceInfo($ctx.columnName),
                                                genericType = primitiveType("String"),
                                                value = $ctx.columnName.text)
  let ValueSpecification typeHolder = ifPresent(hasAny($ctx, type, multiplicity),
                                                  newImpl(UserDefinedGenericTypeAndMultiplicityHolder,
                                                          genericType = newImpl(UserDefinedGenericType,
                                                                                type = newImpl(RelationType,
                                                                                                columns = listOf(buildOneColSpecColumn($ctx))))),
                                                  newImpl(CompilerGenericTypeAndMultiplicityHolder))
  return newImpl(FunctionInvocation,
                 sourceInformation = buildSourceInfo($ctx),
                 functionName = ifPresent($ctx.anyLambda,
                                          ifPresent($ctx.extraFunction, "aggColSpec", "funcColSpec"),
                                          "colSpec"),
                 parametersValues = ifPresent($ctx.anyLambda,
                                              ifPresent($ctx.extraFunction,
                                                        listOf(buildAnyLambda($ctx.anyLambda),
                                                               buildAnyLambda($ctx.extraFunction.anyLambda),
                                                               nameAtomic,
                                                               typeHolder),
                                                        listOf(buildAnyLambda($ctx.anyLambda),
                                                               nameAtomic,
                                                               typeHolder)),
                                              listOf(nameAtomic, typeHolder)))
}

# A single Column for a RelationType, derived from a oneColSpec context.
# Used both for the per-column type-holder and for the combined colSpecArray holder.
helper buildOneColSpecColumn(OneColSpecContext ctx) {
  return newImpl(Column,
                 name = $ctx.columnName.text,
                 genericType = ifPresent($ctx.type, buildGenericType($ctx.type)),
                 multiplicity = ifPresent($ctx.multiplicity, buildMultiplicity($ctx.multiplicity)))
}

# The String AtomicValue carrying a column name, used in colSpecArray.
helper buildColumnNameAtomic(OneColSpecContext ctx) as ValueSpecification {
  return newImpl(AtomicValue,
                 sourceInformation = buildSourceInfo($ctx.columnName),
                 genericType = primitiveType("String"),
                 value = $ctx.columnName.text)
}

# The type holder for the colSpecArray case (no lambdas anywhere). Either a
# UserDefinedGenericTypeAndMultiplicityHolder wrapping a RelationType built from
# all typed/multiplied colSpecs, or a CompilerHolder when none have explicit types.
helper buildColSpecArrayHolder(ColumnBuildersContext ctx) as ValueSpecification {
  alt when anyHasAny($ctx.oneColSpec, type, multiplicity) {
    return newImpl(UserDefinedGenericTypeAndMultiplicityHolder,
                   genericType = newImpl(UserDefinedGenericType,
                                         type = newImpl(RelationType,
                                                        columns = selectMapHasAny($ctx.oneColSpec, type, multiplicity, oneColSpecColumn))))
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
  let GenericType result = buildGenericType($ctx.type)
  set result = ifPresent($ctx.equalType,
                          newImpl(GenericTypeOperation,
                                  operationType=enumPointer("meta::pure::metamodel::relation::GenericTypeOperationType", "Equal"),
                                  left=result,
                                  right=buildGenericType($ctx.equalType.type)),
                          result)
  set result = ListAdapter.adapt($ctx.typeAddSubOperation).injectInto(result, this::buildWrapAddSubOp)
  set result = ifPresent($ctx.subsetType,
                          newImpl(GenericTypeOperation,
                                  operationType=enumPointer("meta::pure::metamodel::relation::GenericTypeOperationType", "Subset"),
                                  left=result,
                                  right=buildGenericType($ctx.subsetType.type)),
                          result)
  return result
}

# One Union/Difference wrap step for a single typeAddSubOperation context.
# Used as the lambda body of the injectInto fold in typeWithOperation.
helper buildWrapAddSubOp(GenericType base, TypeAddSubOperationContext ctx) as GenericType {
  alt when $ctx.addType {
    return newImpl(GenericTypeOperation,
                   operationType = enumPointer("meta::pure::metamodel::relation::GenericTypeOperationType", "Union"),
                   left = base,
                   right = buildGenericType($ctx.addType.type))
  }
  alt else {
    return newImpl(GenericTypeOperation,
                   operationType = enumPointer("meta::pure::metamodel::relation::GenericTypeOperationType", "Difference"),
                   left = base,
                   right = buildGenericType($ctx.subType.type))
  }
}

rule typeOrUndefined as GenericType {
  alt when $ctx.QUESTION {
    return newImpl(UndefinedGenericType)
  }
  alt else {
    return buildTypeWithOperation($ctx.typeWithOperation)
  }
  else error("Unexpected typeOrUndefined")
}

rule buildMilestoningVariableExpression as ValueSpecification {
  alt when $ctx.variable {
    return buildVariable($ctx.variable)
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
  alt when $ctx.allOrFunction.functionExpressionParameters {
    return newImpl(FunctionInvocation,
                   sourceInformation=buildSourceInfo($ctx),
                   functionName=ifPresent($ctx.qualifiedName, $ctx.qualifiedName.text, $ctx.getText()),
                   parametersValues=mapList($ctx.allOrFunction.functionExpressionParameters.combinedExpression, buildCombinedExpression))
  }
  alt when $ctx.allOrFunction.allFunction {
    return newImpl(FunctionInvocation,
                   sourceInformation=buildSourceInfo($ctx),
                   functionName="getAll",
                   parametersValues=listOf(
                     newImpl(AtomicValue,
                             sourceInformation=buildSourceInfo($ctx),
                             value=newImpl(Package_Pointer,
                                           value=ifPresent($ctx.qualifiedName, $ctx.qualifiedName.text, beforeFirstDot($ctx.getText()))))))
  }
  alt when $ctx.allOrFunction.allVersionsFunction {
    return newImpl(FunctionInvocation,
                   sourceInformation=buildSourceInfo($ctx),
                   functionName="getAllVersions",
                   parametersValues=listOf(
                     newImpl(AtomicValue,
                             sourceInformation=buildSourceInfo($ctx),
                             value=newImpl(Package_Pointer,
                                           value=ifPresent($ctx.qualifiedName, $ctx.qualifiedName.text, beforeFirstDot($ctx.getText()))))))
  }
  alt when $ctx.allOrFunction.allVersionsInRangeFunction {
    return newImpl(FunctionInvocation,
                   sourceInformation=buildSourceInfo($ctx),
                   functionName="getAllVersionsInRange",
                   parametersValues=prepended(
                     newImpl(AtomicValue,
                             sourceInformation=buildSourceInfo($ctx),
                             value=newImpl(Package_Pointer,
                                           value=ifPresent($ctx.qualifiedName, $ctx.qualifiedName.text, beforeFirstDot($ctx.getText())))),
                     mapList($ctx.allOrFunction.allVersionsInRangeFunction.buildMilestoningVariableExpression, buildMilestoningVariableExpression)))
  }
  alt when $ctx.allOrFunction.allFunctionWithMilestoning {
    return newImpl(FunctionInvocation,
                   sourceInformation=buildSourceInfo($ctx),
                   functionName="getAll",
                   parametersValues=prepended(
                     newImpl(AtomicValue,
                             sourceInformation=buildSourceInfo($ctx),
                             value=newImpl(Package_Pointer,
                                           value=ifPresent($ctx.qualifiedName, $ctx.qualifiedName.text, beforeFirstDot($ctx.getText())))),
                     mapList($ctx.allOrFunction.allFunctionWithMilestoning.buildMilestoningVariableExpression, buildMilestoningVariableExpression)))
  }
  alt else {
    return newImpl(AtomicValue,
                   sourceInformation=buildSourceInfo($ctx),
                   value=newImpl(Package_Pointer, value=$ctx.getText()))
  }
}

# Grammar: lambdaParam: identifier lambdaParamType?
# A lambda parameter slot: either bare (`x`) or typed (`x:T[m]`). The three
# optional fields share one predicate ($ctx.lambdaParamType) — set together.
# Grammar: propertyExpression: DOT propertyName functionExpressionParameters?
# Property access on a `receiver`: `receiver.propName` or `receiver.propName(args)`.
# Takes the receiver value-spec as an extra method parameter.
# Grammar: expressionInstance: NEW (variable | qualifiedName | combinedExpression) typeArguments? multiplicityArguments?
#                              typeVariableValues? GROUP_OPEN expressionInstanceParserPropertyAssignment*
#                              GROUP_CLOSE
# Three alts:
#   - `^$var(...)` (copy variable) — head is `variable`
#   - `^Type(...)` (new) — head is `qualifiedName`
#   - `^expr(...)` (copy expression result) — head is `combinedExpression`, used for
#     `^buildOne()(name='a')`, `^$x.next(name='b')`, `^~.foo(name='c')` etc.
# All three forms produce a `copy` or `new` FunctionInvocation whose first param
# is the receiver (variable, type holder, or arbitrary value spec). Property
# assignments are wrapped in a single Collection appended when non-empty.
rule expressionInstance as ValueSpecification {
  alt when $ctx.variable {
    return newImpl(FunctionInvocation,
                   sourceInformation = buildSourceInfo($ctx),
                   functionName = "copy",
                   parametersValues = ifPresent(notEmpty($ctx.expressionInstanceParserPropertyAssignment),
                     listOf(newImpl(VariableExpression, name = $ctx.variable.identifier.text, sourceInformation = buildSourceInfo($ctx.variable)),
                            newImpl(Collection,
                                    sourceInformation = buildSourceInfo($ctx),
                                    values = mapList($ctx.expressionInstanceParserPropertyAssignment, buildExpressionInstanceParserPropertyAssignment),
                                    multiplicity = multBounds(count($ctx.expressionInstanceParserPropertyAssignment), count($ctx.expressionInstanceParserPropertyAssignment)))),
                     listOf(newImpl(VariableExpression, name = $ctx.variable.identifier.text, sourceInformation = buildSourceInfo($ctx.variable)))))
  }
  alt when $ctx.qualifiedName {
    return newImpl(FunctionInvocation,
                   sourceInformation = buildSourceInfo($ctx),
                   functionName = "new",
                   parametersValues = ifPresent(notEmpty($ctx.expressionInstanceParserPropertyAssignment),
                     listOf(buildExpressionInstanceNewHead($ctx),
                            newImpl(Collection,
                                    sourceInformation = buildSourceInfo($ctx),
                                    values = mapList($ctx.expressionInstanceParserPropertyAssignment, buildExpressionInstanceParserPropertyAssignment),
                                    multiplicity = multBounds(count($ctx.expressionInstanceParserPropertyAssignment), count($ctx.expressionInstanceParserPropertyAssignment)))),
                     listOf(buildExpressionInstanceNewHead($ctx))))
  }
  alt else {
    return newImpl(FunctionInvocation,
                   sourceInformation = buildSourceInfo($ctx),
                   functionName = "copy",
                   parametersValues = ifPresent(notEmpty($ctx.expressionInstanceParserPropertyAssignment),
                     listOf(buildCombinedExpression($ctx.combinedExpression),
                            newImpl(Collection,
                                    sourceInformation = buildSourceInfo($ctx),
                                    values = mapList($ctx.expressionInstanceParserPropertyAssignment, buildExpressionInstanceParserPropertyAssignment),
                                    multiplicity = multBounds(count($ctx.expressionInstanceParserPropertyAssignment), count($ctx.expressionInstanceParserPropertyAssignment)))),
                     listOf(buildCombinedExpression($ctx.combinedExpression))))
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
                 sourceInformation = buildSourceInfo($ctx),
                 genericType = buildExpressionInstanceGenericType($ctx),
                 multiplicity = multBounds(1, 1))
}

helper buildExpressionInstanceGenericType(ExpressionInstanceContext ctx) {
  return newImpl(UserDefinedGenericType,
                 type = newImpl(Type_Pointer, value = ifPresent($ctx.qualifiedName, $ctx.qualifiedName.text, "Unknown")),
                 typeArguments = ifPresent($ctx.typeArguments, mapList($ctx.typeArguments.typeOrUndefined, buildTypeOrUndefined)),
                 multiplicityArguments = ifPresent($ctx.multiplicityArguments, mapList($ctx.multiplicityArguments.multiplicityArgument, parseMultiplicityArgument)),
                 typeVariableValues = ifPresent($ctx.typeVariableValues, mapList($ctx.typeVariableValues.instanceLiteral, buildInstanceLiteral)))
}

# Grammar: expressionInstanceParserPropertyAssignment: propertyName (PLUS)? EQUAL expressionInstanceRightSide
# Each assignment becomes a `keyExpression(nameStr, rhs[, plusFlag])` invocation.
# Deep-path keys are not supported by the grammar (see M3Parser.g4 comment).
rule expressionInstanceParserPropertyAssignment {
  return newImpl(FunctionInvocation,
                 sourceInformation = buildSourceInfo($ctx),
                 functionName = "keyExpression",
                 parametersValues = ifPresent($ctx.PLUS,
                   listOf(
                     newImpl(AtomicValue, sourceInformation = buildSourceInfo($ctx), genericType = primitiveType("String"), value = $ctx.propertyName.text),
                     buildExpressionInstanceRightSide($ctx.expressionInstanceRightSide),
                     newImpl(AtomicValue, sourceInformation = buildSourceInfo($ctx), genericType = primitiveType("Boolean"), value = true)),
                   listOf(
                     newImpl(AtomicValue, sourceInformation = buildSourceInfo($ctx), genericType = primitiveType("String"), value = $ctx.propertyName.text),
                     buildExpressionInstanceRightSide($ctx.expressionInstanceRightSide))))
}

# Grammar: expressionInstanceRightSide: expressionInstanceAtomicRightSide
# expressionInstanceAtomicRightSide:
#     combinedExpression | expressionInstance | qualifiedName
# parentReference (`~`, `~.~`, `~.foo`, `~.foo->arrow(...)`) is now an
# `atomicExpression` alternative, so the combinedExpression branch covers it
# — including chained arrow invocations after the parent-reference target.
rule expressionInstanceRightSide as ValueSpecification {
  alt when $ctx.expressionInstanceAtomicRightSide.combinedExpression {
    return buildCombinedExpression($ctx.expressionInstanceAtomicRightSide.combinedExpression)
  }
  alt when $ctx.expressionInstanceAtomicRightSide.expressionInstance {
    return buildExpressionInstance($ctx.expressionInstanceAtomicRightSide.expressionInstance)
  }
  alt when $ctx.expressionInstanceAtomicRightSide.qualifiedName {
    return newImpl(VariableExpression,
                   sourceInformation=buildSourceInfo($ctx.expressionInstanceAtomicRightSide),
                   name=$ctx.expressionInstanceAtomicRightSide.qualifiedName.text)
  }
  else error("Unsupported expressionInstanceRightSide")
}

# Grammar: functionExpression: arrowStep+
#          arrowStep: ARROW qualifiedName functionExpressionParameters
# Left-fold over the arrow steps, each producing an ArrowInvocation that takes
# the running result (acc) as its first parameter and the per-step args as the rest.
rule functionExpression as ValueSpecification {
  param ValueSpecification receiver
  chain_fold from receiver over $ctx.arrowStep {
    else step newImpl(ArrowInvocation,
                       sourceInformation=buildSourceInfo($ctx),
                       functionName=$it.qualifiedName.text,
                       parametersValues=prepended(acc, mapList($it.functionExpressionParameters.combinedExpression, buildCombinedExpression)))
  }
}

rule propertyExpression as ValueSpecification {
  param ValueSpecification receiver
  return newImpl(DotApplication,
                 sourceInformation = buildSourceInfo($ctx),
                 functionName = $ctx.propertyName.text,
                 parametersValues = ifPresent($ctx.functionExpressionParameters,
                                              prepended(receiver, mapList($ctx.functionExpressionParameters.combinedExpression, buildCombinedExpression)),
                                              listOf(receiver)))
}

rule lambdaParam {
  return newImpl(VariableExpression,
                 name = $ctx.identifier.text,
                 sourceInformation = ifPresent($ctx.lambdaParamType, buildSourceInfo($ctx)),
                 genericType = ifPresent($ctx.lambdaParamType, buildGenericType($ctx.lambdaParamType.type)),
                 multiplicity = ifPresent($ctx.lambdaParamType, buildMultiplicity($ctx.lambdaParamType.multiplicity)))
}

# Grammar: lambdaFunction: LBRACE (lambdaParam (COMMA lambdaParam)*)? lambdaPipe RBRACE
# Builds the LambdaFunction value directly (the wrapping AtomicValue is done by
# the caller in `anyLambda`).
rule lambdaFunction {
  return newImpl(LambdaFunction,
                 sourceInformation = buildSourceInfo($ctx),
                 parameters = mapList($ctx.lambdaParam, buildLambdaParam),
                 expressionSequence = buildCodeBlock($ctx.lambdaPipe.codeBlock))
}

# Grammar: anyLambda: lambdaFunction | (lambdaParam? lambdaPipe)
# Three alternatives; each wraps the resulting LambdaFunction in an AtomicValue
# whose source info matches the lambda. Predicate `$ctx.X && $ctx.Y` combines.
rule anyLambda as ValueSpecification {
  alt when $ctx.lambdaFunction {
    return newImpl(AtomicValue,
                   sourceInformation=buildSourceInfo($ctx.lambdaFunction),
                   value=buildLambdaFunction($ctx.lambdaFunction))
  }
  alt when $ctx.lambdaPipe && $ctx.lambdaParam {
    return newImpl(AtomicValue,
                   sourceInformation=buildSourceInfo($ctx),
                   value=newImpl(LambdaFunction,
                                  sourceInformation=buildSourceInfo($ctx),
                                  parameters=listOf(buildLambdaParam($ctx.lambdaParam)),
                                  expressionSequence=buildCodeBlock($ctx.lambdaPipe.codeBlock)))
  }
  alt when $ctx.lambdaPipe {
    return newImpl(AtomicValue,
                   sourceInformation=buildSourceInfo($ctx),
                   value=newImpl(LambdaFunction,
                                  sourceInformation=buildSourceInfo($ctx),
                                  parameters=listOf(),
                                  expressionSequence=buildCodeBlock($ctx.lambdaPipe.codeBlock)))
  }
  else error("Unsupported anyLambda")
}

rule notExpression as ValueSpecification {
  return newImpl(FunctionInvocation,
                 sourceInformation = buildSourceInfo($ctx),
                 functionName = "not",
                 parametersValues = listOf(buildSimpleExpression($ctx.simpleExpression)))
}

# Grammar: nonArrowOrEqualExpression: atomicExpression | expressionsArray |
#          notExpression | signedExpression | sliceExpression | combinedExpression
# Each branch is a delegate to its sub-rule's build method.
rule nonArrowOrEqualExpression as ValueSpecification {
  alt when $ctx.atomicExpression {
    return buildAtomicExpression($ctx.atomicExpression)
  }
  alt when $ctx.expressionsArray {
    return buildExpressionsArray($ctx.expressionsArray)
  }
  alt when $ctx.notExpression {
    return buildNotExpression($ctx.notExpression)
  }
  alt when $ctx.signedExpression {
    return buildSignedExpression($ctx.signedExpression)
  }
  alt when $ctx.sliceExpression {
    return buildSliceExpression($ctx.sliceExpression)
  }
  alt when $ctx.combinedExpression {
    return buildCombinedExpression($ctx.combinedExpression)
  }
  else error("Unexpected nonArrowOrEqualExpression")
}

# Grammar: signedExpression: (MINUS | PLUS) simpleExpression
# When MINUS, wrap the inner in a `minus(...)` call. When PLUS, pass-through.
rule signedExpression as ValueSpecification {
  alt when $ctx.MINUS {
    return newImpl(FunctionInvocation,
                   sourceInformation = buildSourceInfo($ctx),
                   functionName = "minus",
                   parametersValues = listOf(buildSimpleExpression($ctx.simpleExpression)))
  }
  alt else {
    return buildSimpleExpression($ctx.simpleExpression)
  }
}

rule sliceExpression as ValueSpecification {
  return newImpl(FunctionInvocation,
                 sourceInformation = buildSourceInfo($ctx),
                 functionName = "slice",
                 parametersValues = mapList($ctx.expression, buildExpression))
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
                 sourceInformation = buildSourceInfo($ctx),
                 functionName = "letFunction",
                 parametersValues = listOf(
                   newImpl(AtomicValue,
                           sourceInformation = buildSourceInfo($ctx.identifier),
                           genericType = primitiveType("String"),
                           value = $ctx.identifier.text),
                   buildCombinedExpression($ctx.combinedExpression)))
}

# Top-level element rule: primitiveDefinition.
# Grammar: primitiveDefinition: PRIMITIVE stereotypes? taggedValues? qualifiedName
#                              typeVariableParameters? EXTENDS type constraints?
# Single extends type (wrapped in a 1-element list of generalizations).
rule primitiveDefinition {
  return register(newImpl(PrimitiveType,
    sourceInformation = buildSourceInfo($ctx),
    name = simpleNameOf($ctx.qualifiedName.text),
    package = ifPresent(hasPackagePrefix($ctx.qualifiedName.text),
                        newImpl(Package_Pointer, value = packagePrefix($ctx.qualifiedName.text))),
    typeVariables = ifPresent($ctx.typeVariableParameters,
                              mapList($ctx.typeVariableParameters.functionVariableExpression, buildFunctionVariableExpression)),
    generalizations = ifPresent($ctx.type, listOf(buildClassGeneralization($ctx.type))),
    constraints = ifPresent($ctx.constraints, mapList($ctx.constraints.constraint, buildConstraint)),
    stereotypes = ifPresent($ctx.stereotypes, mapList($ctx.stereotypes.stereotype, buildStereotype)),
    taggedValues = ifPresent($ctx.taggedValues, mapList($ctx.taggedValues.taggedValue, buildTaggedValue))))
}

# Top-level element rule: enumDefinition.
# Grammar: enumDefinition: ENUM stereotypes? taggedValues? qualifiedName
#                          { enumValue (COMMA enumValue)* }
# Each enumValue maps to a Property carrying its own annotations.
rule enumDefinition {
  return register(newImpl(Enumeration,
    sourceInformation = buildSourceInfo($ctx),
    name = simpleNameOf($ctx.qualifiedName.text),
    package = ifPresent(hasPackagePrefix($ctx.qualifiedName.text),
                        newImpl(Package_Pointer, value = packagePrefix($ctx.qualifiedName.text))),
    properties = mapList($ctx.enumValue, buildEnumValue),
    stereotypes = ifPresent($ctx.stereotypes, mapList($ctx.stereotypes.stereotype, buildStereotype)),
    taggedValues = ifPresent($ctx.taggedValues, mapList($ctx.taggedValues.taggedValue, buildTaggedValue))))
}

# Grammar: enumValue: stereotypes? taggedValues? identifier
rule enumValue {
  return newImpl(Property,
                 name = $ctx.identifier.text,
                 sourceInformation = buildSourceInfo($ctx),
                 stereotypes = ifPresent($ctx.stereotypes, mapList($ctx.stereotypes.stereotype, buildStereotype)),
                 taggedValues = ifPresent($ctx.taggedValues, mapList($ctx.taggedValues.taggedValue, buildTaggedValue)))
}

# Grammar: typeParameter: identifier
rule typeParameter {
  return newImpl(TypeParameter, name = $ctx.identifier.text)
}

# Grammar: typeParameterWithVariance: MINUS? identifier
rule typeParameterWithVariance {
  return newImpl(TypeParameter,
                 name = $ctx.identifier.text,
                 contravariant = ifPresent($ctx.MINUS, true))
}

# A single identifier from a multiplicity-parameters list becomes a
# UserDefinedMultiplicityParameter.
helper buildMultParamDef(IdentifierContext ctx) {
  return newImpl(UserDefinedMultiplicityParameter, name = $ctx.getText())
}

# Top-level element rule: classDefinition.
# Grammar: classDefinition: CLASS stereotypes? taggedValues? qualifiedName
#                          typeParametersWithVarianceAndMultiplicityParameters?
#                          typeVariableParameters? (EXTENDS type (COMMA type)*)?
#                          constraints? classBody?
rule classDefinition {
  return register(newImpl(Class,
    sourceInformation = buildSourceInfo($ctx),
    name = simpleNameOf($ctx.qualifiedName.text),
    package = ifPresent(hasPackagePrefix($ctx.qualifiedName.text),
                        newImpl(Package_Pointer, value = packagePrefix($ctx.qualifiedName.text))),
    typeParameters = ifPresent($ctx.typeParametersWithVarianceAndMultiplicityParameters.typeParametersWithVariance,
                               mapList($ctx.typeParametersWithVarianceAndMultiplicityParameters.typeParametersWithVariance.typeParameterWithVariance, buildTypeParameterWithVariance)),
    multiplicityParameters = ifPresent($ctx.typeParametersWithVarianceAndMultiplicityParameters.multiplictyParameters,
                                        mapList($ctx.typeParametersWithVarianceAndMultiplicityParameters.multiplictyParameters.identifier, buildMultParamDef)),
    typeVariables = ifPresent($ctx.typeVariableParameters,
                              mapList($ctx.typeVariableParameters.functionVariableExpression, buildFunctionVariableExpression)),
    generalizations = ifPresent(notEmpty($ctx.type), mapList($ctx.type, classGeneralization)),
    constraints = ifPresent($ctx.constraints, mapList($ctx.constraints.constraint, buildConstraint)),
    properties = ifPresent($ctx.classBody.properties, mapList($ctx.classBody.properties.property, buildProperty)),
    qualifiedProperties = ifPresent($ctx.classBody.properties, mapList($ctx.classBody.properties.qualifiedProperty, buildQualifiedProperty)),
    stereotypes = ifPresent($ctx.stereotypes, mapList($ctx.stereotypes.stereotype, buildStereotype)),
    taggedValues = ifPresent($ctx.taggedValues, mapList($ctx.taggedValues.taggedValue, buildTaggedValue))))
}

# Wrap a TypeContext into a Generalization (general type + source info).
helper buildClassGeneralization(TypeContext ctx) {
  return newImpl(Generalization,
                 general = buildGenericType($ctx),
                 sourceInformation = buildSourceInfo($ctx))
}

# Top-level element rule: functionDefinition.
# Grammar: functionDefinition: FUNCTION stereotypes? taggedValues? qualifiedName
#                              typeAndMultiplicityParameters? functionTypeSignature
#                              constraints? codeBlock
# `_name` is the canonical Pure signature (`fnName_ParamT_Mult__…__ReturnT_Mult_`)
# computed by the `functionId` helper below — derived from the parse-tree context,
# so the whole rule fits in a single `newImpl` literal.
rule functionDefinition {
  return register(newImpl(UserDefinedFunction,
    sourceInformation = buildSourceInfo($ctx),
    name = functionId($ctx),
    package = ifPresent(hasPackagePrefix($ctx.qualifiedName.text),
                        newImpl(Package_Pointer, value = packagePrefix($ctx.qualifiedName.text))),
    functionName = simpleNameOf($ctx.qualifiedName.text),
    typeParameters = ifPresent($ctx.typeAndMultiplicityParameters.typeParameters,
                               mapList($ctx.typeAndMultiplicityParameters.typeParameters.typeParameter, buildTypeParameter)),
    multiplicityParameters = ifPresent($ctx.typeAndMultiplicityParameters.multiplictyParameters,
                                        mapList($ctx.typeAndMultiplicityParameters.multiplictyParameters.identifier, buildMultParamDef)),
    parameters = mapList($ctx.functionTypeSignature.functionVariableExpression, buildFunctionVariableExpression),
    returnGenericType = buildGenericType($ctx.functionTypeSignature.type),
    returnMultiplicity = buildMultiplicity($ctx.functionTypeSignature.multiplicity),
    preConstraints = ifPresent($ctx.constraints, filterMapNot($ctx.constraints.constraint, "$return", buildConstraint)),
    postConstraints = ifPresent($ctx.constraints, filterMap($ctx.constraints.constraint, "$return", buildConstraint)),
    expressionSequence = buildCodeBlock($ctx.codeBlock),
    stereotypes = ifPresent($ctx.stereotypes, mapList($ctx.stereotypes.stereotype, buildStereotype)),
    taggedValues = ifPresent($ctx.taggedValues, mapList($ctx.taggedValues.taggedValue, buildTaggedValue))))
}

# Top-level element rule: nativeFunction.
# Grammar: nativeFunction: NATIVE FUNCTION stereotypes? taggedValues? qualifiedName
#                         typeAndMultiplicityParameters? functionTypeSignature
# Same shape as functionDefinition minus the body and constraints.
rule nativeFunction {
  return register(newImpl(NativeFunction,
    sourceInformation = buildSourceInfo($ctx),
    name = nativeFunctionId($ctx),
    package = ifPresent(hasPackagePrefix($ctx.qualifiedName.text),
                        newImpl(Package_Pointer, value = packagePrefix($ctx.qualifiedName.text))),
    functionName = simpleNameOf($ctx.qualifiedName.text),
    typeParameters = ifPresent($ctx.typeAndMultiplicityParameters.typeParameters,
                               mapList($ctx.typeAndMultiplicityParameters.typeParameters.typeParameter, buildTypeParameter)),
    multiplicityParameters = ifPresent($ctx.typeAndMultiplicityParameters.multiplictyParameters,
                                        mapList($ctx.typeAndMultiplicityParameters.multiplictyParameters.identifier, buildMultParamDef)),
    parameters = mapList($ctx.functionTypeSignature.functionVariableExpression, buildFunctionVariableExpression),
    returnGenericType = buildGenericType($ctx.functionTypeSignature.type),
    returnMultiplicity = buildMultiplicity($ctx.functionTypeSignature.multiplicity),
    stereotypes = ifPresent($ctx.stereotypes, mapList($ctx.stereotypes.stereotype, buildStereotype)),
    taggedValues = ifPresent($ctx.taggedValues, mapList($ctx.taggedValues.taggedValue, buildTaggedValue))))
}

# === Function-ID helpers ===
# Compute the canonical Pure signature `fnName_ParamT_Mult__…__ReturnT_Mult_` from
# the parse-tree context. Shape:
#     simpleName + (params? "_" + buildParamSig1 + "__" + … + "__" + buildParamSigN  : "")
#                + "__" + returnTypeSig + "_" + returnMultSig + "_"

helper functionId(FunctionDefinitionContext ctx) as String {
  return simpleNameOf($ctx.qualifiedName.text)
       + buildParamListSig($ctx.functionTypeSignature)
       + "__" + buildTypeSig($ctx.functionTypeSignature.type)
       + "_" + buildMultSig($ctx.functionTypeSignature.multiplicity) + "_"
}

helper nativeFunctionId(NativeFunctionContext ctx) as String {
  return simpleNameOf($ctx.qualifiedName.text)
       + buildParamListSig($ctx.functionTypeSignature)
       + "__" + buildTypeSig($ctx.functionTypeSignature.type)
       + "_" + buildMultSig($ctx.functionTypeSignature.multiplicity) + "_"
}

helper buildParamListSig(FunctionTypeSignatureContext ctx) as String {
  alt when notEmpty($ctx.functionVariableExpression) {
    return "_" + joinStringsWith(mapList($ctx.functionVariableExpression, buildParamSig), "__")
  }
  alt else {
    return ""
  }
}

helper buildParamSig(FunctionVariableExpressionContext ctx) as String {
  return buildTypeSig($ctx.type) + "_" + buildMultSig($ctx.multiplicity)
}

helper buildTypeSig(TypeContext ctx) as String {
  alt when $ctx.qualifiedName {
    return simpleNameOf($ctx.qualifiedName.text)
  }
  alt else {
    return "UNKNOWN"
  }
}

helper buildMultSig(MultiplicityContext ctx) as String {
  alt when $ctx.multiplicityArgument.QUESTION {
    return "UNDEFINED"
  }
  alt when $ctx.multiplicityArgument.identifier {
    return $ctx.multiplicityArgument.identifier.text
  }
  alt when $ctx.multiplicityArgument.fromMultiplicity && $ctx.multiplicityArgument.toMultiplicity.STAR {
    return "$" + $ctx.multiplicityArgument.fromMultiplicity.text + "_MANY$"
  }
  alt when $ctx.multiplicityArgument.fromMultiplicity {
    return "$" + $ctx.multiplicityArgument.fromMultiplicity.text + "_" + $ctx.multiplicityArgument.toMultiplicity.text + "$"
  }
  alt when $ctx.multiplicityArgument.toMultiplicity.STAR {
    return "MANY"
  }
  alt else {
    return $ctx.multiplicityArgument.toMultiplicity.text
  }
}

# Top-level element rule: association.
# Grammar: association: ASSOCIATION stereotypes? taggedValues? qualifiedName associationBody?
# associationBody contains properties() with property() and qualifiedProperty() lists.
rule association {
  return register(newImpl(Association,
    sourceInformation = buildSourceInfo($ctx),
    name = simpleNameOf($ctx.qualifiedName.text),
    package = ifPresent(hasPackagePrefix($ctx.qualifiedName.text),
                        newImpl(Package_Pointer, value = packagePrefix($ctx.qualifiedName.text))),
    properties = ifPresent($ctx.associationBody.properties, mapList($ctx.associationBody.properties.property, buildProperty)),
    qualifiedProperties = ifPresent($ctx.associationBody.properties, mapList($ctx.associationBody.properties.qualifiedProperty, buildQualifiedProperty)),
    stereotypes = ifPresent($ctx.stereotypes, mapList($ctx.stereotypes.stereotype, buildStereotype)),
    taggedValues = ifPresent($ctx.taggedValues, mapList($ctx.taggedValues.taggedValue, buildTaggedValue))))
}

# Top-level element rule: profile.
# Grammar: profile: PROFILE qualifiedName CURLY_BRACKET_OPEN stereotypeDefinitions?
#                   tagDefinitions? CURLY_BRACKET_CLOSE
rule profile {
  return register(newImpl(Profile,
    sourceInformation = buildSourceInfo($ctx),
    name = simpleNameOf($ctx.qualifiedName.text),
    package = ifPresent(hasPackagePrefix($ctx.qualifiedName.text),
                        newImpl(Package_Pointer, value = packagePrefix($ctx.qualifiedName.text))),
    p_stereotypes = ifPresent($ctx.stereotypeDefinitions, mapList($ctx.stereotypeDefinitions.identifier, profileStereotypeDef)),
    p_tags = ifPresent($ctx.tagDefinitions, mapList($ctx.tagDefinitions.identifier, profileTagDef))))
}

# Convert an identifier context to a stereotype / tag definition (name + srcInfo).
helper buildProfileStereotypeDef(IdentifierContext ctx) {
  return newImpl(Stereotype,
                 sourceInformation = buildSourceInfo($ctx),
                 value = $ctx.getText())
}

helper buildProfileTagDef(IdentifierContext ctx) {
  return newImpl(Tag,
                 sourceInformation = buildSourceInfo($ctx),
                 value = $ctx.getText())
}

# Grammar: stereotype: qualifiedName DOT identifier
# qualifiedName = profile path, identifier = stereotype name
rule stereotype {
  return newImpl(Stereotype_Pointer,
                 sourceInformation = buildSourceInfo($ctx.qualifiedName),
                 value = $ctx.qualifiedName.text,
                 extraPointerValues = listOf(
                   newImpl(PointerValue,
                           sourceInformation = buildSourceInfo($ctx.identifier),
                           value = $ctx.identifier.text)))
}

# Grammar: taggedValue: qualifiedName DOT identifier EQUAL STRING (PLUS STRING)*
# Multiple STRINGs are concatenated (after quote-stripping).
rule taggedValue {
  return newImpl(TaggedValue,
                 tag = newImpl(Tag_Pointer,
                               sourceInformation = buildSourceInfo($ctx.qualifiedName),
                               value = $ctx.qualifiedName.text,
                               extraPointerValues = listOf(
                                   newImpl(PointerValue,
                                           sourceInformation = buildSourceInfo($ctx.identifier),
                                           value = $ctx.identifier.text))),
                 value = joinStripped($ctx.STRING))
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
  chain_fold from buildNonArrowOrEqualExpression($ctx.nonArrowOrEqualExpression) over $ctx.propertyOrFunctionExpression {
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
  alt when $ctx.qualifiedName {
    return newImpl(UserDefinedGenericType,
                   type = newImpl(Type_Pointer,
                                  sourceInformation = buildSourceInfo($ctx.qualifiedName),
                                  value = $ctx.qualifiedName.text),
                   typeArguments = ifPresent($ctx.typeArguments, mapList($ctx.typeArguments.typeOrUndefined, buildTypeOrUndefined)),
                   multiplicityArguments = ifPresent($ctx.multiplicityArguments, mapList($ctx.multiplicityArguments.multiplicityArgument, parseMultiplicityArgument)),
                   typeVariableValues = ifPresent($ctx.typeVariableValues, mapList($ctx.typeVariableValues.instanceLiteral, buildInstanceLiteral)))
  }
  alt when $ctx.CURLY_BRACKET_OPEN {
    return newImpl(UserDefinedGenericType,
                   type = newImpl(FunctionType,
                                  parameters = mapList($ctx.functionTypePureType, buildFunctionTypePureType),
                                  returnType = buildGenericType($ctx.type),
                                  returnMultiplicity = buildMultiplicity($ctx.multiplicity)))
  }
  alt when $ctx.GROUP_OPEN {
    return newImpl(UserDefinedGenericType,
                   type = newImpl(RelationType,
                                  columns = mapList($ctx.columnType, buildColumnType)))
  }
}

rule columnType {
  return newImpl(Column,
                 sourceInformation = buildSourceInfo($ctx),
                 name = ifPresent($ctx.mayColumnName.columnName, stripIfQuoted($ctx.mayColumnName.columnName.text)),
                 nameWildCard = ifPresent($ctx.mayColumnName.QUESTION, true),
                 genericType = ifPresent($ctx.mayColumnType.type, buildGenericType($ctx.mayColumnType.type)),
                 multiplicity = ifPresent($ctx.multiplicity, buildMultiplicity($ctx.multiplicity)))
}

rule functionTypePureType {
  return newImpl(VariableExpression,
                 genericType = buildGenericType($ctx.type),
                 multiplicity = buildMultiplicity($ctx.multiplicity))
}

rule constraint {
  alt when $ctx.simpleConstraint {
    return newImpl(Constraint,
                   sourceInformation = buildSourceInfo($ctx),
                   name = ifPresent($ctx.simpleConstraint.constraintId, $ctx.simpleConstraint.constraintId.VALID_STRING.text),
                   functionDefinition = newImpl(LambdaFunction,
                                                sourceInformation = buildSourceInfo($ctx.simpleConstraint),
                                                expressionSequence = listOf(buildCombinedExpression($ctx.simpleConstraint.combinedExpression))))
  }
  alt when $ctx.complexConstraint {
    return newImpl(Constraint,
                   sourceInformation = buildSourceInfo($ctx),
                   name = $ctx.complexConstraint.VALID_STRING.text,
                   owner = ifPresent($ctx.complexConstraint.constraintOwner, $ctx.complexConstraint.constraintOwner.VALID_STRING.text),
                   externalId = ifPresent($ctx.complexConstraint.constraintExternalId, stripQuotes($ctx.complexConstraint.constraintExternalId.STRING.text)),
                   functionDefinition = newImpl(LambdaFunction,
                                                sourceInformation = buildSourceInfo($ctx.complexConstraint.constraintFunction),
                                                expressionSequence = listOf(buildCombinedExpression($ctx.complexConstraint.constraintFunction.combinedExpression))),
                   enforcementLevel = ifPresent($ctx.complexConstraint.constraintEnforcementLevel, $ctx.complexConstraint.constraintEnforcementLevel.ENFORCEMENT_LEVEL.text),
                   messageFunction = ifPresent($ctx.complexConstraint.constraintMessage,
                                                newImpl(LambdaFunction,
                                                        sourceInformation = buildSourceInfo($ctx.complexConstraint.constraintMessage),
                                                        expressionSequence = listOf(buildCombinedExpression($ctx.complexConstraint.constraintMessage.combinedExpression)))))
  }
}

rule property {
  return newImpl(Property,
                 name = $ctx.propertyName.text,
                 sourceInformation = buildSourceInfo($ctx),
                 genericType = buildGenericType($ctx.propertyReturnType.type),
                 multiplicity = buildMultiplicity($ctx.propertyReturnType.multiplicity),
                 aggregation = ifPresent($ctx.aggregation,
                                          enumPointer("meta::pure::metamodel::function::property::AggregationKind", capitalize(stripParens($ctx.aggregation.text)))),
                 defaultValue = ifPresent($ctx.defaultValue,
                                           newImpl(LambdaFunction,
                                                   sourceInformation = buildSourceInfo($ctx.defaultValue),
                                                   expressionSequence = listOf(buildCombinedExpression($ctx.defaultValue.combinedExpression)))),
                 stereotypes = ifPresent($ctx.stereotypes, mapList($ctx.stereotypes.stereotype, buildStereotype)),
                 taggedValues = ifPresent($ctx.taggedValues, mapList($ctx.taggedValues.taggedValue, buildTaggedValue)))
}

rule qualifiedProperty {
  return newImpl(QualifiedProperty,
                 name = $ctx.identifier.text,
                 sourceInformation = buildSourceInfo($ctx),
                 parameters = mapList($ctx.qualifiedPropertyBody.functionVariableExpression, buildFunctionVariableExpression),
                 expressionSequence = buildCodeBlock($ctx.qualifiedPropertyBody.codeBlock),
                 genericType = buildGenericType($ctx.propertyReturnType.type),
                 multiplicity = buildMultiplicity($ctx.propertyReturnType.multiplicity),
                 stereotypes = ifPresent($ctx.stereotypes, mapList($ctx.stereotypes.stereotype, buildStereotype)),
                 taggedValues = ifPresent($ctx.taggedValues, mapList($ctx.taggedValues.taggedValue, buildTaggedValue)))
}

rule expressionsArray {
  return newImpl(Collection,
                 sourceInformation = buildSourceInfo($ctx),
                 values = mapList($ctx.combinedExpression, buildCombinedExpression),
                 multiplicity = multBounds(count($ctx.combinedExpression), count($ctx.combinedExpression)))
}

rule codeBlock as MutableList<ValueSpecification> {
  grow_list over $ctx.programLine {
    alt when $it.combinedExpression {
      yield buildCombinedExpression($it.combinedExpression)
    }
    alt when $it.letExpression {
      yield buildLetExpression($it.letExpression)
    }
  }
}

rule expression as ValueSpecification {
  chain_fold from buildNonArrowOrEqualExpression($ctx.nonArrowOrEqualExpression) over $ctx.propertyOrFunctionExpression {
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
  alt when $ctx.instanceLiteralToken {
    return buildInstanceLiteralToken($ctx.instanceLiteralToken)
  }
  alt when $ctx.INTEGER {
    return newImpl(AtomicValue,
                   sourceInformation = buildSourceInfo($ctx),
                   value = ifPresent($ctx.MINUS, -parseLong($ctx.INTEGER.text), parseLong($ctx.INTEGER.text)),
                   genericType = primitiveType("Integer"))
  }
  alt when $ctx.FLOAT {
    return newImpl(AtomicValue,
                   sourceInformation = buildSourceInfo($ctx),
                   value = ifPresent($ctx.MINUS, -parseDouble($ctx.FLOAT.text), parseDouble($ctx.FLOAT.text)),
                   genericType = primitiveType("Float"))
  }
  alt when $ctx.DECIMAL {
    # Signed Decimal literal — parseDecimal(MINUS, text) handles the sign
    # via string-prefix (BigDecimal has no unary minus operator in Java).
    return newImpl(AtomicValue,
                   sourceInformation = buildSourceInfo($ctx),
                   value = parseDecimal($ctx.MINUS, $ctx.DECIMAL.text),
                   genericType = primitiveType("Decimal"))
  }
  else error("Unsupported literal")
}



# === Source-information helpers ===
# `lineOffset` (the per-instance long field) is referenced as a bare identifier;
# it resolves to the field on the generated builder class.

helper buildSourceInfo(ParserRuleContext ctx) as SourceInformationImpl {
  return newImpl(SourceInformation,
                 startLine   = (long)($ctx.getStart().getLine()) + lineOffset,
                 startColumn = (long)($ctx.getStart().getCharPositionInLine()) + 1,
                 endLine     = (long)($ctx.getStop().getLine()) + lineOffset,
                 endColumn   = (long)($ctx.getStop().getCharPositionInLine() + $ctx.getStop().getText().length()))
}

# Span of a binary-call: from LHS start to RHS end. Falls back to the operator
# token's position when the LHS has no source info (e.g. a primitive literal
# constructed inline without sourceInformation set).
helper buildOpSourceInfo(Token opTok, ValueSpecification left, ParserRuleContext ctx) as SourceInformationImpl {
  let SourceInformation leftSrc = left._p_sourceInformation()
  return newImpl(SourceInformation,
                 startLine   = ifPresent(nonNull(leftSrc) && nonNull(leftSrc._startLine()),
                                         leftSrc._startLine(),
                                         (long)(opTok.getLine()) + lineOffset),
                 startColumn = ifPresent(nonNull(leftSrc) && nonNull(leftSrc._startColumn()),
                                         leftSrc._startColumn(),
                                         (long)(opTok.getCharPositionInLine()) + 1),
                 endLine     = (long)($ctx.getStop().getLine()) + lineOffset,
                 endColumn   = (long)($ctx.getStop().getCharPositionInLine() + $ctx.getStop().getText().length()))
}
