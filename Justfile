# Legend Pure Next — top-level build orchestrator
#
# Usage:
#   just                     # build and test everything
#   just test                # run all tests (Java + Pure)
#   just ide                 # launch the IDE
#   just clean               # clean everything
#
# Verbosity:
#   By default, recipes print a single "▶ <step>" header per phase and
#   suppress underlying tool noise (mvn -q, no command echo). To see full
#   tool output, pass `verbose=1`:
#       just test verbose=1
#       just build-truffle verbose=1

# `set quiet := true` suppresses just's per-command echo. Each recipe prints
# its own '▶ ...' header so the run reads as a script of phases.
set quiet := true
# Bash needed for process substitution (filtering stderr without touching exit codes).
set shell := ["bash", "-c"]

root      := justfile_directory()
spec      := root / "specification"
boot      := root / "bootstrap"
compiler  := root / "compiler-pure"
platforms := root / "platforms"
ts        := platforms / "typescript"
truffle   := platforms / "truffle"
out       := root / "build"
cli       := out / "cli" / "pure-cli.jar"

truffle_codegen := truffle / "legend-pure-next-truffle-codegen"
truffle_runtime := truffle / "legend-pure-next-truffle-runtime"

# verbose='' (default) → mvn runs with -q, JVM noise filtered from stderr.
# verbose=anything-else → mvn runs without -q, nothing filtered.
verbose := ""
mvnq    := if verbose == "" { "-q" } else { "" }

# Drop common JVM/SLF4J/Maven warning noise from stderr unless verbose=1.
# Process substitution `2> >(grep ... >&2)` filters stderr only, leaves
# stdout untouched, and preserves the command's exit code (no pipeline,
# no pipefail headache). Patterns:
#   SLF4J(W): ...                 — slf4j 2.x no-provider warnings
#   SLF4J: ...                    — slf4j 1.x warnings
#   WARNING: ...                  — JDK deprecated-method warnings
#   Note: ... will be removed     — JDK terminal-deprecation tail
err_filter := if verbose == "" { "2> >(grep -E -v '^(SLF4J(\\(W\\))?:|WARNING: |Note: )' >&2)" } else { "" }

# Default: build and test everything
default: test

# Build the full pipeline: Java + PDBs
build: build-compiler-pdb

# --- Bootstrap: Maven handles generation, compilation, PDB build, and tests ---
build-bootstrap:
    @echo "▶ [bootstrap] mvn install (Java + core.pdb + bootstrap unit tests)"
    cd {{boot}} && mvn install {{mvnq}} {{err_filter}}

# --- Copy artifacts to build/ for downstream (CLI, truffle, etc.) ---
stage: build-bootstrap
    @echo "▶ [stage] copy CLI jar + core.pdb + generated specs to build/"
    mkdir -p {{out}}/cli {{out}}/specification/protocol
    cp {{boot}}/legend-pure-next-cli/target/pure-cli-*-fat.jar {{cli}}
    cp {{boot}}/legend-pure-next-compiler/target/classes/core.pdb {{out}}/core.pdb
    cp -r {{boot}}/legend-pure-next-generators/target/generated-specification/* {{out}}/specification/

# --- Copy pre-built artifacts to build/ (no rebuild) ---
copy:
    @echo "▶ [stage] copy pre-built artifacts (no rebuild)"
    mkdir -p {{out}}/cli {{out}}/specification/protocol
    cp {{boot}}/legend-pure-next-cli/target/pure-cli-*-fat.jar {{cli}}
    cp {{boot}}/legend-pure-next-compiler/target/classes/core.pdb {{out}}/core.pdb
    cp -r {{boot}}/legend-pure-next-generators/target/generated-specification/* {{out}}/specification/

# --- Compile compiler.pdb from compiler-pure sources ---
build-compiler-pdb: stage
    @echo "▶ [compiler-pure] build compiler.pdb against core.pdb"
    java -jar {{cli}} compile \
        --base-pdb {{out}}/core.pdb \
        --source {{compiler}} \
        --output {{out}}/compiler.pdb {{err_filter}}

# --- Platforms ---
build-typescript:
    @echo "▶ [typescript] pnpm install + build"
    if [ -d "{{ts}}" ]; then cd {{ts}} && pnpm install && pnpm run build; else echo "  (platforms/typescript/ not present, skipping)"; fi

# --- Build codegen module and generate PDB classes ---
generate-pdb-classes: build-compiler-pdb
    @echo "▶ [truffle] generate truffle-namespaced PDB wrapper classes"
    cd {{truffle}} && mvn install -N {{mvnq}} {{err_filter}}
    cd {{truffle_codegen}} && mvn install -DskipTests {{mvnq}} {{err_filter}}
    # Remove hand-written MapImpl (uses LinkedHashMap, lives in runtime src)
    rm -f {{truffle_runtime}}/target/generated-pdb-sources/org/finos/legend/pure/truffle/pdb/meta/pure/functions/collection/MapImpl.java

# Build Truffle runtime (includes generated PDB classes)
build-truffle: generate-pdb-classes
    @echo "▶ [truffle] mvn install -DskipTests (truffle-runtime jar + fat jar)"
    cd {{truffle_runtime}} && mvn install -DskipTests {{mvnq}} {{err_filter}}
    mkdir -p {{out}}/cli
    cp {{truffle_runtime}}/target/pure-compile-*-fat.jar {{out}}/cli/pure-compile.jar

# Truffle Java unit tests. Always shows test output regardless of verbose
# — surefire summaries are the point of this recipe.
test-truffle: build-truffle build-compiler-pdb
    @echo "▶ [truffle] mvn test (truffle-runtime Java unit tests)"
    cd {{truffle_runtime}} && mvn test {{err_filter}}

# Run the Pure test suite via the Truffle interpreter (JVM mode).
test-pure-truffle: build-truffle build-compiler-pdb
    @echo "▶ [pure→truffle] runCompiledGraphTests over specification/compiler"
    java -jar {{out}}/cli/pure-compile.jar execute \
        --pdb {{out}}/core.pdb \
        --pdb {{out}}/compiler.pdb \
        --function "meta::pure::test::runCompiledGraphTests_String_1__Boolean_1_" \
        --args "{{spec}}/compiler" {{err_filter}}

# Build the native-image binary for pure-compile.
build-truffle-native: build-bootstrap
    #!/usr/bin/env bash
    set -euo pipefail
    echo "▶ [truffle] native-image build (GraalVM)"
    if ! command -v native-image >/dev/null 2>&1; then
        echo "Error: 'native-image' not on PATH." >&2
        echo "GraalVM is required. Install with:" >&2
        echo "  brew install --cask graalvm-jdk@21" >&2
        echo "Then set JAVA_HOME to the GraalVM JDK Home." >&2
        exit 1
    fi
    cd {{truffle}} && mvn -Pnative package -DskipTests {{mvnq}}
    mkdir -p {{out}}/cli
    cp {{truffle}}/target/pure-compile {{out}}/cli/pure-compile-native
    echo "  native binary: {{out}}/cli/pure-compile-native"

# --- Tests ---
# Bootstrap Java tests run as part of `mvn install` in build-bootstrap
# (transitively pulled in by every other build-* target).
# Truffle Java tests run via test-truffle (otherwise build-truffle's
# -DskipTests would silently hide them — that's how a 674-test class with
# 9 failures stayed invisible for months).
# Then we run Pure-level spec tests on both interpreters.
test: test-truffle test-compiler-pure-truffle
    @echo "▶ [pure→bootstrap] runCompiledGraphTests over specification/compiler"
    java -jar {{cli}} execute \
        --pdb {{out}}/core.pdb \
        --pdb {{out}}/compiler.pdb \
        --function "meta::pure::test::runCompiledGraphTests_String_1__Boolean_1_" \
        --args "{{spec}}/compiler" {{err_filter}}

# Run the compiler-pure tests via the Truffle interpreter.
test-compiler-pure-truffle: build-truffle build-compiler-pdb
    @echo "▶ [pure→truffle] runCompiledGraphTests (compiler-pure spec)"
    java -jar {{out}}/cli/pure-compile.jar execute \
        --pdb {{out}}/core.pdb \
        --pdb {{out}}/compiler.pdb \
        --function "meta::pure::test::runCompiledGraphTests_String_1__Boolean_1_" \
        --args "{{spec}}/compiler" {{err_filter}}

test-pure: build-compiler-pdb
    @echo "▶ [pure→bootstrap] runCompiledGraphTests"
    java -jar {{cli}} execute \
        --pdb {{out}}/core.pdb \
        --pdb {{out}}/compiler.pdb \
        --function "meta::pure::test::runCompiledGraphTests_String_1__Boolean_1_" \
        --args "{{spec}}/compiler" {{err_filter}}

test-functions-pure: build-compiler-pdb
    @echo "▶ [pure→bootstrap] runFunctionTests"
    java -jar {{cli}} execute \
        --pdb {{out}}/core.pdb \
        --pdb {{out}}/compiler.pdb \
        --function "meta::pure::test::runFunctionTests_String_1__Boolean_1_" \
        --args "meta::pure::functions" {{err_filter}}

test-functions-truffle: build-truffle build-compiler-pdb
    @echo "▶ [pure→truffle] runFunctionTests"
    java -jar {{out}}/cli/pure-compile.jar execute \
        --pdb {{out}}/core.pdb \
        --pdb {{out}}/compiler.pdb \
        --function "meta::pure::test::runFunctionTests_String_1__Boolean_1_" \
        --args "meta::pure::functions" {{err_filter}}

test-functions-native: build-truffle-native build-compiler-pdb
    @echo "▶ [pure→native] runFunctionTests"
    {{out}}/cli/pure-compile-native execute \
        --pdb {{out}}/core.pdb \
        --pdb {{out}}/compiler.pdb \
        --function "meta::pure::test::runFunctionTests_String_1__Boolean_1_" \
        --args "meta::pure::functions" {{err_filter}}

test-pct-pure: build-compiler-pdb
    @echo "▶ [pure→bootstrap] runPCTTests"
    java -jar {{cli}} execute \
        --pdb {{out}}/core.pdb \
        --pdb {{out}}/compiler.pdb \
        --function "meta::pure::test::runPCTTests_String_1__Boolean_1_" \
        --args "meta::pure::functions" {{err_filter}}

test-pct-truffle: build-truffle build-compiler-pdb
    @echo "▶ [pure→truffle] runPCTTests"
    java -jar {{out}}/cli/pure-compile.jar execute \
        --pdb {{out}}/core.pdb \
        --pdb {{out}}/compiler.pdb \
        --function "meta::pure::test::runPCTTests_String_1__Boolean_1_" \
        --args "meta::pure::functions" {{err_filter}}

test-pct-native: build-truffle-native build-compiler-pdb
    @echo "▶ [pure→native] runPCTTests"
    {{out}}/cli/pure-compile-native execute \
        --pdb {{out}}/core.pdb \
        --pdb {{out}}/compiler.pdb \
        --function "meta::pure::test::runPCTTests_String_1__Boolean_1_" \
        --args "meta::pure::functions" {{err_filter}}

test-pure-native: build-truffle-native build-compiler-pdb
    @echo "▶ [pure→native] runCompiledGraphTests"
    {{out}}/cli/pure-compile-native execute \
        --pdb {{out}}/core.pdb \
        --pdb {{out}}/compiler.pdb \
        --function "meta::pure::test::runCompiledGraphTests_String_1__Boolean_1_" \
        --args "{{spec}}/compiler" {{err_filter}}

# --- Benchmarks ---
bench-truffle: build-truffle build-compiler-pdb
    @echo "▶ [truffle] PureEvaluatorBenchmark"
    cd {{truffle}} && mvn -Pbench test -Dtest=PureEvaluatorBenchmark

# --- Utilities ---
ide: build-compiler-pdb
    @echo "▶ [ide] launch IDE via bootstrap CLI"
    java -jar {{cli}} execute \
        --pdb {{out}}/core.pdb \
        --pdb {{out}}/compiler.pdb \
        --function "meta::pure::ide::start"

clean:
    @echo "▶ [clean] remove build/ and bootstrap target/"
    rm -rf {{out}}
    cd {{boot}} && mvn clean {{mvnq}}
