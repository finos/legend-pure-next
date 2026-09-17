# platforms/truffle/native.just — GraalVM native-image build + test recipes.
#
# Direct invocation:    just <recipe>                  (from platforms/truffle/, with `mod native "native.just"` at the parent)
# Via the root Justfile: just truffle::native::<recipe> (from repo root)
#
# All recipes here run the AOT-compiled `pure-truffle-native` binary instead
# of the JVM-mode fat jar — same Pure source, no JIT at runtime. The Graal
# tripwire in `PureCompileMain` is a no-op under native-image (no compilation
# events) so these recipes verify only end-to-end correctness, not JIT
# behaviour. Use the JVM-mode `just truffle::spec-*` recipes for that.
#
# Requires GraalVM 25 with `native-image` on PATH; `_depCheck_graalvm` (in the
# parent truffle Justfile) catches a non-GraalVM JDK before any native-image
# work starts.

set quiet := true
set shell := ["bash", "-c"]

native_root     := source_directory()
truffle_root    := native_root
repo_root       := truffle_root / ".." / ".."
# Records heavy build steps for the dashboard build time trend.
build_step     := "node " + repo_root / "tools" / "test-dashboard" / "build-step.mjs"
truffle         := truffle_root / "legend-pure-next-truffle"
truffle_cli_module := truffle / "legend-pure-next-truffle-interfaces" / "legend-pure-next-truffle-cli"
spec            := repo_root / "pure" / "specification"
compiler_src    := repo_root / "pure" / "specification" / "compiler" / "compiler-pure"
truffle_out     := truffle_root / "build"
shared_out      := repo_root / "shared"
test_results   := repo_root / "test-results"
truffle_native  := truffle_out / "pure-truffle-native"
pure_bootstrap  := repo_root / "bootstrap" / "bin" / "pure-bootstrap"

verbose := ""
mvnq    := if verbose == "" { "-q" } else { "" }
err_filter := if verbose == "" { "2> >(grep -E -v '^(SLF4J(\\(W\\))?:|WARNING: |Note: )' >&2)" } else { "" }

header := "h(){ s=\"[truffle::native::$1]\"; ru=$(printf '█%.0s' $(seq 1 ${#s})); printf '\\n  %s\\n  %s\\n  %s\\n' \"$ru\" \"$s\" \"$ru\"; }; h"
substep := "p(){ ru=$(printf '─%.0s' $(seq 1 $((${#1}+2)))); printf '\\n  %s\\n  ↳ %s\\n  %s\\n' \"$ru\" \"$1\" \"$ru\"; }; p"

# Default: build + run all native tests.
default: test

# Build the GraalVM native-image binary (requires `native-image` on PATH).
build: (_step-start "truffle/native-image") _depCheck_pdbs _depCheck_graalvm
    #!/usr/bin/env bash
    set -euo pipefail
    {{header}} 'build'
    {{substep}} 'native-image build (GraalVM)'
    if ! command -v native-image >/dev/null 2>&1; then
        echo "Error: 'native-image' not on PATH." >&2
        echo "Install with: brew install --cask graalvm-jdk@25" >&2
        echo "Then set JAVA_HOME to the GraalVM JDK Home." >&2
        exit 1
    fi
    # No `clean`: the native profile packages on top of the Truffle install that ran before it, and a clean
    # would delete the surefire reports that install's JUnit run left for the test dashboard.
    cd {{truffle}} && mvn -Pnative package -DskipTests {{mvnq}}
    mkdir -p {{truffle_out}}
    cp {{truffle_cli_module}}/target/pure-truffle-native {{truffle_native}}
    {{build_step}} end "truffle/native-image"

# `build` already file-checks bootstrap PDBs (via `_depCheck_pdbs` from parent truffle Justfile) — alias of `build` for symmetry.
# A self-sufficient build-from-clean is `just build-all` at the repo root, which builds bootstrap + truffle (jar) + native.
build-all: build

# Run the specification runs on the native binary (compiler corpus, runtime functions, PCT, PDB, printers).
test: spec

# `test` plus the native self-hosted compiler run.
test-all: test spec-compiler-selfhost

# Every specification run of the native host (self-host excluded: see `spec-compiler-selfhost`).
spec: spec-compiler spec-functions spec-pct spec-grammar spec-pdb spec-pdb-golden spec-printer-compiled spec-printer-protocol

# Run compiler-graph spec tests via the native binary.
spec-compiler: build
    @{{header}} 'spec-compiler'
    @{{substep}} 'runCompiledGraphTests'
    {{truffle_native}} execute \
        --pdb {{shared_out}}/core.pdb \
        --pdb {{shared_out}}/parser-mappings.pdb \
        --pdb {{shared_out}}/core-tests.pdb \
        --pdb {{shared_out}}/compiler.pdb \
        --pdb {{shared_out}}/compiler-tests.pdb \
        --function "meta::pure::compiler::test::runCompiledGraphTests_String_1__String_1__String_1_" \
        --args "{{spec}}/compiler/tests" "{{test_results}}/spec/compiler/pure@cli-truffle-native.json" {{err_filter}}

# Run Pure runTests over meta::pure::functions via the native binary.
spec-functions: build
    @{{header}} 'spec-functions'
    @{{substep}} 'runTests'
    {{truffle_native}} execute \
        --pdb {{shared_out}}/core.pdb \
        --pdb {{shared_out}}/parser-mappings.pdb \
        --pdb {{shared_out}}/core-tests.pdb \
        --pdb {{shared_out}}/compiler.pdb \
        --pdb {{shared_out}}/compiler-tests.pdb \
        --function "meta::pure::test::runTests_String_1__String_1__String_1_" \
        --args "meta::pure::functions" "{{test_results}}/spec/functions/pure@cli-truffle-native.json" {{err_filter}}

# Run Pure PCT tests over meta::pure::functions via the native binary.
spec-pct: build
    @{{header}} 'spec-pct'
    @{{substep}} 'runPCTTests'
    {{truffle_native}} execute \
        --pdb {{shared_out}}/core.pdb \
        --pdb {{shared_out}}/parser-mappings.pdb \
        --pdb {{shared_out}}/core-tests.pdb \
        --pdb {{shared_out}}/compiler.pdb \
        --pdb {{shared_out}}/compiler-tests.pdb \
        --function "meta::pure::test::runPCTTests_String_1__String_1__String_1_" \
        --args "meta::pure::functions" "{{test_results}}/spec/pct/pure@cli-truffle-native.json" {{err_filter}} || echo "  PCT failures do not fail the build: they are in the report and on the dashboard"

# Grammar corpus: parse each grammar.pure and compare the parse result with its protocol.json.
spec-grammar: build
    @{{header}} 'spec-grammar'
    @{{substep}} 'runGrammarCorpus over pure/specification/grammar/tests'
    {{truffle_native}} execute \
        --pdb {{shared_out}}/core.pdb \
        --pdb {{shared_out}}/parser-mappings.pdb \
        --pdb {{shared_out}}/core-tests.pdb \
        --pdb {{shared_out}}/compiler.pdb \
        --pdb {{shared_out}}/compiler-tests.pdb \
        --function "meta::pure::test::grammar::runGrammarCorpus_String_1__String_1__String_1_" \
        --args "{{spec}}/grammar/tests" "{{test_results}}/spec/grammar/pure@cli-truffle-native.json" {{err_filter}}

# Run Pure runTests over meta::pure::compiler::pdb (the self-hosted PDB reader/writer suite) via the native binary.
spec-pdb: build
    @{{header}} 'spec-pdb'
    @{{substep}} 'runTests over meta::pure::compiler::pdb'
    {{truffle_native}} execute \
        --pdb {{shared_out}}/core.pdb \
        --pdb {{shared_out}}/parser-mappings.pdb \
        --pdb {{shared_out}}/core-tests.pdb \
        --pdb {{shared_out}}/compiler.pdb \
        --pdb {{shared_out}}/compiler-tests.pdb \
        --function "meta::pure::test::runTests_String_1__String_1__String_1_" \
        --args "meta::pure::compiler::pdb" "{{test_results}}/spec/pdb-format/pure@cli-truffle-native.json" {{err_filter}}

# PDB wire contract: compile each compiler fixture, write it with the Pure writer and compare the archive
# with the Java golden in pure/specification/compiler/tests-pdb-serialization (compiler-pure test/pdbGoldenRunner.pure).
spec-pdb-golden: build
    @{{header}} 'spec-pdb-golden'
    @{{substep}} 'runPdbGoldenTests: the Pure compiler and writer against the Java golden archives'
    {{truffle_native}} execute \
        --pdb {{shared_out}}/core.pdb \
        --pdb {{shared_out}}/parser-mappings.pdb \
        --pdb {{shared_out}}/core-tests.pdb \
        --pdb {{shared_out}}/compiler.pdb \
        --pdb {{shared_out}}/compiler-tests.pdb \
        --function "meta::pure::compiler::test::pdb::runPdbGoldenTests_String_1__String_1__String_1__String_1__String_1_" \
        --args "{{spec}}/compiler/tests" "{{spec}}/compiler/tests-pdb-serialization" "{{shared_out}}/specification/m3.fbs" "{{test_results}}/spec/pdb/pure@cli-truffle-native.json" {{err_filter}}

# Compiled-printer corpus: compile each compiler-corpus file, print the compiled elements, compare with the source.
spec-printer-compiled: build
    @{{header}} 'spec-printer-compiled'
    @{{substep}} 'runCompiledPrinterCorpus over pure/specification/compiler/tests'
    {{truffle_native}} execute \
        --pdb {{shared_out}}/core.pdb \
        --pdb {{shared_out}}/parser-mappings.pdb \
        --pdb {{shared_out}}/core-tests.pdb \
        --pdb {{shared_out}}/compiler.pdb \
        --pdb {{shared_out}}/compiler-tests.pdb \
        --function "meta::pure::compiler::test::printer::runCompiledPrinterCorpus_String_1__String_1__String_1_" \
        --args "{{spec}}/compiler/tests" "{{test_results}}/pure/printer-compiled/pure@cli-truffle-native.json" {{err_filter}}

# Protocol-printer corpus: parse each grammar-corpus file, print the protocol, compare with the source.
spec-printer-protocol: build
    @{{header}} 'spec-printer-protocol'
    @{{substep}} 'runProtocolPrinterCorpus over pure/specification/grammar/tests'
    {{truffle_native}} execute \
        --pdb {{shared_out}}/core.pdb \
        --pdb {{shared_out}}/parser-mappings.pdb \
        --pdb {{shared_out}}/core-tests.pdb \
        --pdb {{shared_out}}/compiler.pdb \
        --pdb {{shared_out}}/compiler-tests.pdb \
        --function "meta::pure::functions::printer::protocol::tests::runProtocolPrinterCorpus_String_1__String_1__String_1_" \
        --args "{{spec}}/grammar/tests" "{{test_results}}/pure/printer-protocol/pure@cli-truffle-native.json" {{err_filter}}

# Output goes to {{truffle_out}}/native/ so it doesn't collide with the JVM-truffle self-host
# output in {{truffle_out}}/ when both run (e.g. under `truffle::test-all`).
# Self-host: compile compiler-pure on the native binary, run compiler tests, then PDB-diff vs Java direct.
spec-compiler-selfhost: build
    @{{header}} 'spec-compiler-selfhost'
    @{{substep}} 'compiler-pure → {{truffle_out}}/native/compiler.pdb (native), then run compiler tests'
    {{truffle_native}} compile \
        --base-pdb {{shared_out}}/core.pdb \
        --base-pdb {{shared_out}}/parser-mappings.pdb \
        --base-pdb {{shared_out}}/core-tests.pdb \
        --base-pdb {{shared_out}}/compiler.pdb \
        --source {{compiler_src}} \
        --output-dir {{truffle_out}}/native \
        --tests split {{err_filter}}
    {{truffle_native}} execute \
        --pdb {{shared_out}}/core.pdb \
        --pdb {{shared_out}}/parser-mappings.pdb \
        --pdb {{shared_out}}/core-tests.pdb \
        --pdb {{truffle_out}}/native/compiler.pdb \
        --pdb {{truffle_out}}/native/compiler-tests.pdb \
        --function "meta::pure::compiler::test::runCompiledGraphTests_String_1__String_1__String_1_" \
        --args "{{spec}}/compiler/tests" "{{test_results}}/spec/compiler/pure@cli-truffle-native+selfhost.json" {{err_filter}}
    @{{substep}} 'diff shared/compiler.pdb (Java compiler) ↔ {{truffle_out}}/native/compiler.pdb (native compiler)'
    {{pure_bootstrap}} diff-pdb --deep --core {{shared_out}}/core.pdb \
        {{shared_out}}/compiler.pdb \
        {{truffle_out}}/native/compiler.pdb {{err_filter}}

# Internal: fail loudly if shared/core.pdb or shared/compiler.pdb is missing.
_depCheck_pdbs:
    #!/usr/bin/env bash
    if [[ ! -f "{{shared_out}}/core.pdb" || ! -f "{{shared_out}}/compiler.pdb" ]]; then
        echo "Error: <repo>/shared/core.pdb or compiler.pdb missing." >&2
        echo "Run 'just bootstrap::build-compiler-pdb' first (or 'just build' from the repo root)." >&2
        exit 1
    fi
    if [[ ! -f "{{repo_root}}/pure/specification/compiler/compiler-pure/pdb/writer/generated/generatedWriter.pure" ]]; then
        echo "Error: <repo>/pure/specification/compiler/compiler-pure/pdb/writer/generated/generatedWriter.pure missing." >&2
        echo "It is generated (gitignored) and compiler-pure does not compile correctly without it:" >&2
        echo "every meta::pure::compiler::pdb::gen::write* is simply absent, so the self-host" >&2
        echo "compile quietly produces 193 fewer elements instead of failing." >&2
        echo "Run 'just bootstrap::build-compiler-pdb' first (or 'just build' from the repo root)." >&2
        exit 1
    fi

# Internal: enforce GraalVM JDK at $JAVA_HOME (native-image needs it).
_depCheck_graalvm:
    #!/usr/bin/env bash
    set -euo pipefail
    if [[ -z "${JAVA_HOME:-}" ]]; then
        echo "Error: JAVA_HOME is not set. native-image requires JAVA_HOME to point at a GraalVM 25 JDK." >&2
        echo "" >&2
        echo "On macOS:" >&2
        echo "  brew install --cask graalvm-jdk@25       # if not already installed" >&2
        echo "  export JAVA_HOME=\$(/usr/libexec/java_home -v 25)" >&2
        echo "  export PATH=\"\$JAVA_HOME/bin:\$PATH\"" >&2
        exit 1
    fi
    JAVA="$JAVA_HOME/bin/java"
    if [[ ! -x "$JAVA" ]]; then
        echo "Error: \$JAVA_HOME ($JAVA_HOME) does not contain bin/java." >&2
        exit 1
    fi
    if ! "$JAVA" -version 2>&1 | grep -qiE 'graalvm'; then
        echo "Error: \$JAVA_HOME at '$JAVA_HOME' is not a GraalVM JDK." >&2
        "$JAVA" -version >&2 || true
        echo "" >&2
        echo "native-image needs GraalVM. Install GraalVM 25 and set JAVA_HOME, e.g.:" >&2
        echo "  export JAVA_HOME=\$(/usr/libexec/java_home -v 25)" >&2
        echo "  export PATH=\"\$JAVA_HOME/bin:\$PATH\"" >&2
        exit 1
    fi

# Build time trend (tools/test-dashboard/build-step.mjs): the first dependency of a heavy recipe, whose last line runs `{{build_step}} end`.
_step-start STEP:
    {{build_step}} start "{{STEP}}"
