# Parser-mappings module

Source of truth for the M3 parser visitor mappings, expressed as Pure data.
Replaced the bespoke `pure-language-mappings.dsl` text DSL; the .dsl source
and its Java codegen have been retired.

Two consumers today:
- **Bootstrap (`PureLanguageProtocolBuilder.java`)** — a hand-maintained
  reference parser kept in lockstep with `mappings.pure`. Validated by
  `MappingsInterpreterValidatorTest` on every test run. Will become a
  generated artifact once the Java platform's Pure→Java codegen lands.
- **Truffle (`TrufflePureParser`)** — interprets `mappings.pure` directly at
  runtime via `parser-mappings.pdb`. Graal JITs the hot paths.

## Layout

```
mappings-pure/
├── module.json                          ─ packagePattern + deps (core)
├── mappings_ir.pure                     ─ Rule + Alt (only IR; bodies are Pure lambdas)
├── mappings.pure                        ─ allRules() aggregator
├── interpreter/
│   ├── antlr.pure                       ─ AntlrContext + 13 native bridges (declarations)
│   └── dispatch.pure                    ─ parseElements + dispatchRule (~10 lines)
├── mapping_shared.pure                  ─ buildSourceInfo, simpleNameOf, packagePtrOf, stripQuotes,
│                                          stereotype + taggedValue application helpers
├── mapping_type.pure                    ─ buildGenericType (qualifiedName + RelationType + FunctionType
│                                          forms), buildMultiplicity, parseMultiplicityArgument,
│                                          buildClassGeneralization, buildTypeParameter[WithVariance],
│                                          buildMultParamDef, buildColumnType, buildFunctionTypePureType
├── mapping_property.pure                ─ buildProperty (+ defaultValue lambda), buildQualifiedProperty
│                                          (+ body parsing), collect[Qualified]Properties
├── mapping_constraint.pure              ─ buildConstraint (simple + complex)
├── mapping_value_spec.pure              ─ value-spec corpus: precedence ladder, literals,
│                                          variable + property/arrow chain, signed/not,
│                                          lambda, instanceReference (all variants),
│                                          expressionInstance (new + copy + copy_expr),
│                                          AT typeref, DSL, slice, parentReference step-chain,
│                                          codeBlock + let, expressionsArray, operator dispatch
│                                          (+/-/*///<<==/!= with binary-op source spans)
├── mapping_enumeration.pure             ─ enumDefinition + buildEnumValue
├── mapping_profile.pure                 ─ profile + buildProfile{Stereotype,Tag}
├── mapping_class.pure                   ─ classDefinition (+ typeParameters, multParams, constraints)
├── mapping_association.pure             ─ association
├── mapping_primitive.pure               ─ primitiveDefinition (+ typeVariableParameters, constraints)
└── mapping_function.pure                ─ functionDefinition + nativeFunction (+ functionId mangling,
                                           pre/post constraints split by $return)
```

## Build

```
just bootstrap::build-parser-mappings-pdb
```

Compiles to `shared/parser-mappings.pdb` (~120 elements, ~400 KB).

## Validation

The downstream `legend-pure-next-bootstrap-parser-validation` module runs
the existing `.dsl`-derived production parser AND the Pure interpreter
consuming `shared/parser-mappings.pdb` against the
`pure/specification/grammar/tests/*` corpus, and asserts the protocol
shapes match via `ProtocolPrinter` (a unified deep-printer that handles
both typed protocol POJOs and `DynamicInstance`).

Run the validator:

```
mvn -pl bootstrap/legend-pure-next-bootstrap/legend-pure-next-bootstrap-core/legend-pure-next-bootstrap-parser-validation \
    test -Dtest=MappingsInterpreterValidatorTest \
    -Dsurefire.failIfNoSpecifiedTests=false
```

**59 of 59 fixtures green (100%)** across the entire `pure/specification/grammar/tests/*` corpus.

## Open items (informational, not blocking)

- **Aggregation source-parsing** (`(composite)`, `(shared)`): currently
  always stubbed as `'None'`; the `ProtocolPrinter` skips this slot via
  `SKIPPED_SLOTS`. Not blocking any fixture; reachable by extending
  `mapping_property.pure` if aggregation needs to be validated.
- **Multi-section handling**: the validator splits on `###Pure` markers and
  feeds each section's body through both pipelines. Production's full
  `TopLevelParser` does more (parserName dispatch, section sourceInfo);
  the current splitter is sufficient for the `section` fixture's `###Pure`
  sections.

## Editing workflow

1. Edit any `mapping_*.pure` file.
2. Run `just bootstrap::build-parser-mappings-pdb` to recompile
   `shared/parser-mappings.pdb`.
3. Run the validator (above).
4. If a fixture diverges, the printer's deep diff highlights the slot
   that mismatches — usually a missing dispatch, a slot name typo, or a
   source-info offset.
5. Commit both `mappings-pure/**.pure` source AND the regenerated
   `shared/parser-mappings.pdb`.

## Native bridge

The ANTLR-side bridge lives in
`bootstrap/.../legend-pure-next-bootstrap-parser-validation/src/main/java/.../AntlrContextNativesExtension.java`.
It implements `NativeExtension` and registers ~13 natives (`getText`,
`grammarRuleName`, `getTopLevelChildren`, `getChild[ren]`, `getTokenText[s]`,
`hasChild`, `hasToken`, `getStart/StopLine/Column`, `getChildTextAt`) that
reflectively invoke ANTLR context accessors. Passed explicitly to
`PureExecution.builder().withNativeExtensions(...)` — no SPI/META-INF.
