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
# its own banner via the `step` helper so the run reads as a script of phases.
set quiet := true
# Bash needed for process substitution (filtering stderr without touching exit codes).
set shell := ["bash", "-c"]

root      := justfile_directory()
pure      := root / "pure"
spec      := pure / "specification"
boot      := root / "bootstrap" / "legend-pure-next-bootstrap"
bcore     := boot / "legend-pure-next-bootstrap-core"
compiler  := pure / "compiler-pure"
platforms := root / "platforms"
ts        := platforms / "typescript"
truffle   := platforms / "truffle" / "legend-pure-next-truffle"
out       := root / "build"
cli       := out / "cli" / "pure-cli.jar"

truffle_codegen := truffle / "legend-pure-next-truffle-codegen"
truffle_runtime := truffle / "legend-pure-next-truffle-runtime"

# Truffle JVM invocation. The polyglot engine falls back to an interpreter
# (and prints a `WARNING: The polyglot engine uses a fallback runtime…`)
# unless JVMCI is explicitly enabled and selected as the JIT. With GraalVM
# JDK on PATH that gives Graal real runtime compilation; on a non-Graal JDK
# the JVMCI flags become inert (the launcher silently keeps interpreting),
# so the same recipe works in either environment.
truffle_java := "java -XX:+UnlockExperimentalVMOptions -XX:+EnableJVMCI -XX:+UseJVMCICompiler"

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

# `banner` is a printf invocation prepended to each step. The blank-line +
# ═══ rule makes the phase boundary obvious in a long log full of mvn / java
# output, so a reader scrolling through CI can find "where am I in the build?"
# at a glance. Used inline at the top of each recipe:
#   {{banner}} '[bootstrap]' 'wipe build/ + mvn clean install'
banner := "printf '\\n══════════════════════════════════════════════════════════════════════════════════\\n  %-13s %s\\n══════════════════════════════════════════════════════════════════════════════════\\n'"

# Default: build and test everything
default: test

# Build the full pipeline: Java + PDBs
build: build-compiler-pdb

# --- Bootstrap: Maven handles generation, compilation, PDB build, and tests ---
# `mvn clean install` so every run is from-scratch and tests gate the install.
# Wipe build/ first so downstream copies into a known-empty staging area
# (otherwise stale .pdb/spec files from a previous run could be picked up).
build-bootstrap:
    @{{banner}} '[bootstrap]' 'wipe build/ + mvn clean install (Java + core.pdb + unit tests)'
    rm -rf {{out}}
    cd {{boot}} && mvn clean install {{mvnq}} {{err_filter}}

# --- Copy artifacts to build/ for downstream (CLI, truffle, etc.) ---
stage: build-bootstrap
    @{{banner}} '[stage]' 'copy CLI jar + core.pdb + generated specs into build/'
    mkdir -p {{out}}/cli {{out}}/specification/protocol
    cp {{boot}}/legend-pure-next-bootstrap-cli/target/pure-cli-*-fat.jar {{cli}}
    cp {{bcore}}/legend-pure-next-bootstrap-compiler/target/classes/core.pdb {{out}}/core.pdb
    cp -r {{bcore}}/legend-pure-next-bootstrap-generators/target/generated-specification/* {{out}}/specification/

# --- Copy pre-built artifacts to build/ (no rebuild) ---
copy:
    @{{banner}} '[stage]' 'copy pre-built artifacts into build/ (no rebuild)'
    mkdir -p {{out}}/cli {{out}}/specification/protocol
    cp {{boot}}/legend-pure-next-bootstrap-cli/target/pure-cli-*-fat.jar {{cli}}
    cp {{bcore}}/legend-pure-next-bootstrap-compiler/target/classes/core.pdb {{out}}/core.pdb
    cp -r {{bcore}}/legend-pure-next-bootstrap-generators/target/generated-specification/* {{out}}/specification/

# --- Compile compiler.pdb from compiler-pure sources ---
build-compiler-pdb: stage
    @{{banner}} '[compiler-pure]' 'compile compiler.pdb against core.pdb (bootstrap CLI)'
    java -jar {{cli}} compile \
        --base-pdb {{out}}/core.pdb \
        --source {{compiler}} \
        --output {{out}}/compiler.pdb {{err_filter}}

# --- Platforms ---
build-typescript:
    @{{banner}} '[typescript]' 'pnpm install + build'
    if [ -d "{{ts}}" ]; then cd {{ts}} && pnpm install && pnpm run build; else echo "  (platforms/typescript/ not present, skipping)"; fi

# --- Build codegen module and generate PDB classes ---
generate-pdb-classes: build-compiler-pdb
    @{{banner}} '[truffle]' 'generate truffle-namespaced PDB wrapper classes (codegen)'
    cd {{truffle}} && mvn clean install -N {{mvnq}} {{err_filter}}
    cd {{truffle_codegen}} && mvn clean install {{mvnq}} {{err_filter}}
    # Remove hand-written MapImpl (uses LinkedHashMap, lives in runtime src)
    rm -f {{truffle_runtime}}/target/generated-pdb-sources/org/finos/legend/pure/truffle/pdb/meta/pure/functions/collection/MapImpl.java

# Build Truffle runtime — `mvn clean install` runs the truffle Java unit tests
# during the surefire phase, so install fails if tests fail (no separate
# test-truffle pass needed). Skipping tests here is what hid 9 failures in a
# 674-test class for months — never re-introduce -DskipTests on this module.
build-truffle: generate-pdb-classes
    @{{banner}} '[truffle]' 'mvn clean install (truffle-runtime jar + fat jar + Java unit tests)'
    cd {{truffle_runtime}} && mvn clean install {{mvnq}} {{err_filter}}
    mkdir -p {{out}}/cli
    cp {{truffle_runtime}}/target/pure-compile-*-fat.jar {{out}}/cli/pure-compile.jar

# Run the Pure test suite via the Truffle interpreter (JVM mode).
test-pure-truffle: build-truffle build-compiler-pdb
    @{{banner}} '[pure→truffle]' 'runCompiledGraphTests over pure/specification/compiler'
    {{truffle_java}} -jar {{out}}/cli/pure-compile.jar execute \
        --pdb {{out}}/core.pdb \
        --pdb {{out}}/compiler.pdb \
        --function "meta::pure::test::runCompiledGraphTests_String_1__Boolean_1_" \
        --args "{{spec}}/compiler" {{err_filter}}

# Build the native-image binary for pure-compile.
build-truffle-native: build-bootstrap
    #!/usr/bin/env bash
    set -euo pipefail
    {{banner}} '[truffle]' 'native-image build (GraalVM)'
    if ! command -v native-image >/dev/null 2>&1; then
        echo "Error: 'native-image' not on PATH." >&2
        echo "GraalVM is required. Install with:" >&2
        echo "  brew install --cask graalvm-jdk@21" >&2
        echo "Then set JAVA_HOME to the GraalVM JDK Home." >&2
        exit 1
    fi
    cd {{truffle}} && mvn clean -Pnative package -DskipTests {{mvnq}}
    mkdir -p {{out}}/cli
    cp {{truffle}}/target/pure-compile {{out}}/cli/pure-compile-native
    echo "  native binary: {{out}}/cli/pure-compile-native"

# --- Tests ---
# Java tests are gated by `mvn clean install` in each build-* target:
#   - bootstrap unit tests run during build-bootstrap
#   - truffle unit tests run during build-truffle (the 804-test surefire pass)
# A failure in either fails the install, so the chain bails before any
# Pure-level spec tests run. Then we run Pure-level spec tests on both
# interpreters, plus two self-host tests (compile the compiler with each
# compiler, then run compiler tests against the freshly produced PDB).
test: build-truffle test-compiler-pure-truffle test-self-host-java test-self-host-truffle
    @{{banner}} '[pure→bootstrap]' 'runCompiledGraphTests over pure/specification/compiler'
    java -jar {{cli}} execute \
        --pdb {{out}}/core.pdb \
        --pdb {{out}}/compiler.pdb \
        --function "meta::pure::test::runCompiledGraphTests_String_1__Boolean_1_" \
        --args "{{spec}}/compiler" {{err_filter}}

# Run the compiler-pure tests via the Truffle interpreter.
test-compiler-pure-truffle: build-truffle build-compiler-pdb
    @{{banner}} '[pure→truffle]' 'runCompiledGraphTests (compiler-pure spec)'
    {{truffle_java}} -jar {{out}}/cli/pure-compile.jar execute \
        --pdb {{out}}/core.pdb \
        --pdb {{out}}/compiler.pdb \
        --function "meta::pure::test::runCompiledGraphTests_String_1__Boolean_1_" \
        --args "{{spec}}/compiler" {{err_filter}}

# Self-host (Java): run the Pure compiler ON the Java runtime to compile
# compiler-pure into a fresh compiler_java.pdb, then run the compiler spec
# tests against that PDB. Verifies the bootstrap Java executor can run
# compiler-pure end-to-end and produce a usable compiler — distinct from
# {{cli}} compile (which uses the Java compiler implementation, not
# compiler-pure). Needs the build-chain compiler.pdb as a base because
# that's where compile_PureFile_… is loaded from.
test-self-host-java: build-compiler-pdb
    @{{banner}} '[self-host-java]' 'compiler-pure → compiler_java.pdb (Java runtime + Pure compiler), then run compiler tests'
    java -jar {{cli}} compile-via-pure \
        --base-pdb {{out}}/core.pdb \
        --base-pdb {{out}}/compiler.pdb \
        --source {{compiler}} \
        --output {{out}}/compiler_java.pdb {{err_filter}}
    java -jar {{cli}} execute \
        --pdb {{out}}/core.pdb \
        --pdb {{out}}/compiler_java.pdb \
        --function "meta::pure::test::runCompiledGraphTests_String_1__Boolean_1_" \
        --args "{{spec}}/compiler" {{err_filter}}

# Self-host (Truffle): use the Truffle pure-compile to compile compiler-pure
# into a fresh compiler_truffle.pdb, then run the compiler spec tests against
# that PDB. Verifies that Truffle's compilation path is correct end-to-end —
# the output PDB has to be functionally equivalent to the bootstrap one.
# Truffle needs an existing compiler.pdb to bootstrap its own compile, so
# {{out}}/compiler.pdb (built by build-compiler-pdb) is passed as a base.
test-self-host-truffle: build-truffle build-compiler-pdb
    @{{banner}} '[self-host-truffle]' 'compiler-pure → compiler_truffle.pdb (truffle CLI), then run compiler tests'
    {{truffle_java}} -jar {{out}}/cli/pure-compile.jar compile \
        --base-pdb {{out}}/core.pdb \
        --base-pdb {{out}}/compiler.pdb \
        --source {{compiler}} \
        --output {{out}}/compiler_truffle.pdb {{err_filter}}
    {{truffle_java}} -jar {{out}}/cli/pure-compile.jar execute \
        --pdb {{out}}/core.pdb \
        --pdb {{out}}/compiler_truffle.pdb \
        --function "meta::pure::test::runCompiledGraphTests_String_1__Boolean_1_" \
        --args "{{spec}}/compiler" {{err_filter}}

test-pure: build-compiler-pdb
    @{{banner}} '[pure→bootstrap]' 'runCompiledGraphTests'
    java -jar {{cli}} execute \
        --pdb {{out}}/core.pdb \
        --pdb {{out}}/compiler.pdb \
        --function "meta::pure::test::runCompiledGraphTests_String_1__Boolean_1_" \
        --args "{{spec}}/compiler" {{err_filter}}

test-functions-pure: build-compiler-pdb
    @{{banner}} '[pure→bootstrap]' 'runFunctionTests'
    java -jar {{cli}} execute \
        --pdb {{out}}/core.pdb \
        --pdb {{out}}/compiler.pdb \
        --function "meta::pure::test::runFunctionTests_String_1__Boolean_1_" \
        --args "meta::pure::functions" {{err_filter}}

test-functions-truffle: build-truffle build-compiler-pdb
    @{{banner}} '[pure→truffle]' 'runFunctionTests'
    {{truffle_java}} -jar {{out}}/cli/pure-compile.jar execute \
        --pdb {{out}}/core.pdb \
        --pdb {{out}}/compiler.pdb \
        --function "meta::pure::test::runFunctionTests_String_1__Boolean_1_" \
        --args "meta::pure::functions" {{err_filter}}

test-functions-native: build-truffle-native build-compiler-pdb
    @{{banner}} '[pure→native]' 'runFunctionTests'
    {{out}}/cli/pure-compile-native execute \
        --pdb {{out}}/core.pdb \
        --pdb {{out}}/compiler.pdb \
        --function "meta::pure::test::runFunctionTests_String_1__Boolean_1_" \
        --args "meta::pure::functions" {{err_filter}}

test-pct-pure: build-compiler-pdb
    @{{banner}} '[pure→bootstrap]' 'runPCTTests'
    java -jar {{cli}} execute \
        --pdb {{out}}/core.pdb \
        --pdb {{out}}/compiler.pdb \
        --function "meta::pure::test::runPCTTests_String_1__Boolean_1_" \
        --args "meta::pure::functions" {{err_filter}}

test-pct-truffle: build-truffle build-compiler-pdb
    @{{banner}} '[pure→truffle]' 'runPCTTests'
    {{truffle_java}} -jar {{out}}/cli/pure-compile.jar execute \
        --pdb {{out}}/core.pdb \
        --pdb {{out}}/compiler.pdb \
        --function "meta::pure::test::runPCTTests_String_1__Boolean_1_" \
        --args "meta::pure::functions" {{err_filter}}

test-pct-native: build-truffle-native build-compiler-pdb
    @{{banner}} '[pure→native]' 'runPCTTests'
    {{out}}/cli/pure-compile-native execute \
        --pdb {{out}}/core.pdb \
        --pdb {{out}}/compiler.pdb \
        --function "meta::pure::test::runPCTTests_String_1__Boolean_1_" \
        --args "meta::pure::functions" {{err_filter}}

test-pure-native: build-truffle-native build-compiler-pdb
    @{{banner}} '[pure→native]' 'runCompiledGraphTests'
    {{out}}/cli/pure-compile-native execute \
        --pdb {{out}}/core.pdb \
        --pdb {{out}}/compiler.pdb \
        --function "meta::pure::test::runCompiledGraphTests_String_1__Boolean_1_" \
        --args "{{spec}}/compiler" {{err_filter}}

# --- Benchmarks ---
bench-truffle: build-truffle build-compiler-pdb
    @{{banner}} '[truffle]' 'PureEvaluatorBenchmark'
    cd {{truffle}} && mvn -Pbench test -Dtest=PureEvaluatorBenchmark

# --- Utilities ---
ide: build-compiler-pdb
    @{{banner}} '[ide]' 'launch IDE via bootstrap CLI'
    java -jar {{cli}} execute \
        --pdb {{out}}/core.pdb \
        --pdb {{out}}/compiler.pdb \
        --function "meta::pure::ide::start"

clean:
    @{{banner}} '[clean]' 'remove build/ and all maven targets'
    rm -rf {{out}}
    cd {{boot}} && mvn clean {{mvnq}}
    cd {{truffle}} && mvn clean {{mvnq}}
