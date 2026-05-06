# Bootstrap build pipeline

This is a map of the recipes in [`bootstrap/Justfile`](Justfile) and how they
chain together. All recipes are addressable as `just bootstrap::<recipe>` from
the repo root, or as `just <recipe>` from `bootstrap/`.

## Pieces

A single parameterized private recipe `_mvn mode` invokes Maven; `mode` is
either `"skip-tests"` or `"with-tests"`. Two public recipes use it:

- `build` calls `(_mvn "skip-tests")` — `mvn clean install -DskipTests`. Used as a fast build for downstream consumers.
- `test-java` calls `(_mvn "with-tests")` — `mvn clean install`, surefire runs all unit tests during the install lifecycle. The Pure `runTests` and `runPCTTests` suites are wrapped as Java tests, so they execute here too.

`_mvn` always wipes `bootstrap/build/` first, then `cd legend-pure-next-bootstrap && mvn clean install [...]`.

`stage` and `build-compiler-pdb` are public, zero-Maven-dep recipes. The
entry-point recipe (`build` / `test`) lists Maven + `stage` + `build-compiler-pdb`
explicitly so the chain works on a fresh tree.

- `stage` — copies into staging dirs:
  - `bootstrap/build/pure-bootstrap.jar` ← `legend-pure-next-bootstrap-cli/target/pure-bootstrap-*-fat.jar`
  - `<repo>/shared/core.pdb` ← `legend-pure-next-bootstrap-compiler/target/classes/core.pdb`
  - `<repo>/shared/specification/` ← `legend-pure-next-bootstrap-generators/target/generated-specification/`
- `build-compiler-pdb` — runs `pure-bootstrap compile` over `pure/compiler-pure/`, producing `<repo>/shared/compiler.pdb`. Truffle codegen reads this.

The `test-pure-testFunctions`, `test-pure-PCTs`, and `test-pure-compiler` recipes are zero-dep
"expert mode" entry points — they assume `just build` (or `just test`) has
already produced the artifacts. Use them standalone for tight Pure-side
iteration without re-invoking Maven.

## Public recipes — fully expanded

Each tree below shows every step that runs, end to end, when the recipe at
the root is invoked. Steps repeated across recipes are inlined so each tree
stands alone.

```
build
├── (_mvn "skip-tests")    rm -rf bootstrap/build && mvn clean install -DskipTests
├── stage                  cp pure-bootstrap.jar + core.pdb + specs/
└── build-compiler-pdb     pure-bootstrap compile compiler-pure → shared/compiler.pdb
```

```
test
├── test-java
│   └── (_mvn "with-tests")  rm -rf bootstrap/build && mvn clean install
│                             (Java unit tests + Pure runTests/PCT via surefire)
├── stage                    cp pure-bootstrap.jar + core.pdb + specs/
├── build-compiler-pdb       pure-bootstrap compile compiler-pure → shared/compiler.pdb
└── test-pure-compiler
    └── body: pure-bootstrap execute meta::pure::test::runCompiledGraphTests pure/specification/compiler
```

```
test-java
└── (_mvn "with-tests")    rm -rf bootstrap/build && mvn clean install (surefire runs all Java tests)
```

```
test-pure-testFunctions
└── body: pure-bootstrap execute meta::pure::test::runTests              meta::pure::functions
                                 (assumes `just build` was run — no Maven, no stage)
```

```
test-pure-PCTs
└── body: pure-bootstrap execute meta::pure::test::runPCTTests           meta::pure::functions
                                 (assumes `just build` was run — no Maven, no stage)
```

```
test-pure-compiler
└── body: pure-bootstrap execute meta::pure::test::runCompiledGraphTests pure/specification/compiler
                                 (assumes `just build` was run — no Maven, no stage)
```

```
test-pure-self-host
└── build-compiler-pdb     pure-bootstrap compile (assumes core.pdb staged)
    └── body
        ├── pure-bootstrap compile-via-pure compiler-pure → bootstrap/build/compiler_bootstrap.pdb
        └── pure-bootstrap execute meta::pure::test::runCompiledGraphTests pure/specification/compiler
                                    (using compiler_bootstrap.pdb instead of compiler.pdb)
```

```
ide
└── build-compiler-pdb
    └── body: pure-bootstrap execute meta::pure::ide::start
```

```
clean
└── body
    ├── rm -rf bootstrap/build
    └── cd legend-pure-next-bootstrap && mvn clean
```

## Why one Maven launch for `test`

Maven runs ~5–10 s of JVM + reactor startup per invocation, even when sources
are unchanged. The previous setup had `build-mvn` (`mvn install -DskipTests`)
followed by `test-java` doing a separate `mvn test`, paying that cost twice.
Routing `test` through `test-java` (which uses `(_mvn "with-tests")`)
collapses both into a single launch — surefire runs as part of the install
lifecycle. `test-pure-testFunctions`, `test-pure-PCTs`, and `test-pure-compiler` standalone skip
Maven entirely (assume staged), giving a tight inner-loop for Pure-side
iteration.

## Test runners return their own summary

`runTests`, `runPCTTests`, and `runCompiledGraphTests` all return `String[1]`
— the `OK: N / N tests passed in M ms` summary. The Pure CLI prints the
return value, so each suite produces exactly one line of summary output (no
inner `println` + redundant `true` from a trailing `Boolean[1]` return).

## Outputs

| Path | Produced by | Consumed by |
|---|---|---|
| `bootstrap/build/pure-bootstrap.jar` | `stage` | `pure-bootstrap` wrapper, all Pure CLI invocations |
| `<repo>/shared/core.pdb` | `stage` | `build-compiler-pdb`, truffle codegen, all Pure CLI invocations |
| `<repo>/shared/compiler.pdb` | `build-compiler-pdb` | truffle codegen, all Pure CLI invocations |
| `<repo>/shared/specification/` | `stage` | reference data for tests |
| `bootstrap/build/compiler_bootstrap.pdb` | `test-pure-self-host` | `test-pure-self-host`'s own `execute` step |
