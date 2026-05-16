# Truffle build pipeline

This is a map of the recipes in [`platforms/truffle/Justfile`](Justfile) and
how they chain together. All recipes are addressable as
`just truffle::<recipe>` from the repo root, or as `just <recipe>` from
`platforms/truffle/`.

Truffle reads `core.pdb` and `compiler.pdb` out of `<repo>/shared/`, which the
bootstrap pipeline produces. Every recipe that needs them depends on
`_check-pdbs`, which fails with a pointer to `just bootstrap::build-compiler-pdb` if
they're missing.

## Two Maven paths

Every public recipe routes through one of two private Maven invocations.
Each is a single `mvn install` against the truffle parent pom — Maven walks
the reactor (parent → codegen → runtime) in one launch, and the codegen
module's generated MapImpl coexists peacefully with the runtime's hand-written
one (different packages, no FQN conflict).

| Path | Recipe | Invokes | Used by |
|---|---|---|---|
| Skip tests (fast) | `_mvn-skip-tests` | `mvn clean install -DskipTests` on the truffle parent | `build`, `test-pure-testFunctions`, `test-pure-PCTs`, `test-pure-self-host`, `bench` |
| With tests | `_mvn-with-tests` | `mvn clean install` on the truffle parent (surefire runs inline) | `test`, `test-java` |

Both copy `runtime/target/pure-truffle-*-fat.jar` into `truffle/build/pure-truffle.jar` after the install completes.

The native-image build (`build-native`) is a separate Maven invocation
(`mvn -Pnative package -DskipTests`) keyed off the runtime module's `native`
profile.

## Public recipes — fully expanded

Each tree below shows every step that runs, end to end, when the recipe at
the root is invoked. Steps repeated across recipes are inlined so each tree
stands alone.

```
build
└── _mvn-skip-tests
    ├── _check-pdbs                                                verify shared/{core,compiler}.pdb exist
    ├── cd truffle && mvn clean install -DskipTests                walks parent → codegen → runtime
    └── cp runtime/target/pure-truffle-*-fat.jar → truffle/build/pure-truffle.jar
```

```
build-native
├── _check-pdbs                                                    verify shared/{core,compiler}.pdb exist
└── body
    ├── command -v native-image                                    fail with brew install hint if missing
    ├── cd truffle && mvn -Pnative package -DskipTests             GraalVM native-image build
    └── cp runtime/target/pure-truffle-native → truffle/build/pure-truffle-native
```

```
test
├── _mvn-with-tests
│   ├── _check-pdbs
│   ├── cd truffle && mvn clean install                            parent + codegen + runtime + 804 unit tests via surefire
│   └── cp runtime/target/pure-truffle-*-fat.jar → truffle/build/pure-truffle.jar
└── body
    └── pure-truffle execute meta::pure::test::runCompiledGraphTests pure/specification/compiler/tests
```

```
test-java
└── _mvn-with-tests
    ├── _check-pdbs
    ├── cd truffle && mvn clean install                            (804 unit tests run here via surefire)
    └── cp runtime/target/pure-truffle-*-fat.jar → truffle/build/pure-truffle.jar
```

```
test-pure-testFunctions
├── _mvn-skip-tests
│   ├── _check-pdbs
│   ├── cd truffle && mvn clean install -DskipTests
│   └── cp runtime/target/pure-truffle-*-fat.jar → truffle/build/pure-truffle.jar
└── body
    └── pure-truffle execute meta::pure::test::runTests              meta::pure::functions
```

```
test-pure-PCTs
├── _mvn-skip-tests
│   ├── _check-pdbs
│   ├── cd truffle && mvn clean install -DskipTests
│   └── cp runtime/target/pure-truffle-*-fat.jar → truffle/build/pure-truffle.jar
└── body
    └── pure-truffle execute meta::pure::test::runPCTTests           meta::pure::functions
```

```
test-pure-compiler-native
├── build-native
│   ├── _check-pdbs
│   └── body
│       ├── command -v native-image
│       ├── cd truffle && mvn -Pnative package -DskipTests
│       └── cp runtime/target/pure-truffle-native → truffle/build/pure-truffle-native
└── body
    └── pure-truffle-native execute meta::pure::test::runCompiledGraphTests pure/specification/compiler/tests
```

```
test-pure-testFunctions-native
├── build-native              (mvn -Pnative + cp binary; see test-pure-compiler-native for full chain)
└── body
    └── pure-truffle-native execute meta::pure::test::runTests              meta::pure::functions
```

```
test-pure-PCTs-native
├── build-native
└── body
    └── pure-truffle-native execute meta::pure::test::runPCTTests           meta::pure::functions
```

```
test-pure-self-host
├── _mvn-skip-tests
│   ├── _check-pdbs
│   ├── cd truffle && mvn clean install -DskipTests
│   └── cp runtime/target/pure-truffle-*-fat.jar → truffle/build/pure-truffle.jar
└── body
    ├── pure-truffle compile compiler-pure → truffle/build/compiler_truffle.pdb
    └── pure-truffle execute meta::pure::test::runCompiledGraphTests pure/specification/compiler/tests
                              (using compiler_truffle.pdb instead of shared/compiler.pdb)
```

```
bench
├── _mvn-skip-tests
│   ├── _check-pdbs
│   ├── cd truffle && mvn clean install -DskipTests
│   └── cp runtime/target/pure-truffle-*-fat.jar → truffle/build/pure-truffle.jar
└── body
    └── cd truffle && mvn -Pbench test -Dtest=PureEvaluatorBenchmark
```

```
clean
└── body
    ├── rm -rf platforms/truffle/build
    └── cd legend-pure-next-truffle && mvn clean
```

## Why two paths

Pre-refactor, the chain was: parent `mvn install -N -DskipTests` + codegen
`mvn install -DskipTests` + an inter-module `rm MapImpl` + runtime
`mvn install -DskipTests` + `mvn test` on codegen + `mvn test` on runtime.
Five Maven launches per `truffle::test`, plus a defensive `rm` aimed at a
file that didn't exist in `runtime/target/`.

The codegen pom already removes its generated `MapImpl` during its own
`compile` phase, and the hand-written `ast.natives.collection.MapImpl` lives
in a different package than codegen's `pdb.meta.pure.functions.collection`,
so there was never an FQN conflict to begin with. (Eventually the codegen
pom's removal can also go away — nothing references the generated FQN.)

Now `truffle::test` is one Maven launch, with surefire running as part of
the install lifecycle. The non-`test` Pure-level recipes (`test-pure-testFunctions`,
`test-pure-PCTs`, `test-pure-self-host`, `bench`) keep the fast skip-tests path because they
don't gate on Java unit tests.

## Outputs

| Path | Produced by | Consumed by |
|---|---|---|
| `platforms/truffle/build/pure-truffle.jar` | `_mvn-skip-tests` / `_mvn-with-tests` | `pure-truffle` wrapper, JVM-mode test recipes, `test-pure-self-host` |
| `platforms/truffle/build/pure-truffle-native` | `build-native` | native test recipes |
| `platforms/truffle/build/compiler_truffle.pdb` | `test-pure-self-host` | `test-pure-self-host`'s own `execute` step |

## Inputs (from bootstrap)

| Path | Produced by | Required for |
|---|---|---|
| `<repo>/shared/core.pdb` | `bootstrap::build-compiler-pdb` (via `_stage`) | every truffle recipe (codegen reads it during the parent install; runtime tests need it on the PDB classpath) |
| `<repo>/shared/compiler.pdb` | `bootstrap::build-compiler-pdb` | same |

If either is missing, `_check-pdbs` aborts with `Run 'just bootstrap::build-compiler-pdb' first (or 'just build' from the repo root).`
