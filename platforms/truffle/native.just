# platforms/truffle/native.just — GraalVM native-image build + test recipes.
#
# Direct invocation:    just <recipe>                  (from platforms/truffle/, with `mod native "native.just"` at the parent)
# Via the root Justfile: just truffle::native::<recipe> (from repo root)
#
# All recipes here run the AOT-compiled `pure-truffle-native` binary instead
# of the JVM-mode fat jar — same Pure source, no JIT at runtime. The Graal
# tripwire in `PureCompileMain` is a no-op under native-image (no compilation
# events) so these recipes verify only end-to-end correctness, not JIT
# behaviour. Use the JVM-mode `just truffle::test-pure-*` recipes for that.
#
# Requires GraalVM 25 with `native-image` on PATH; `_check-graalvm` (in the
# parent truffle Justfile) catches a non-GraalVM JDK before any native-image
# work starts.

set quiet := true
set shell := ["bash", "-c"]

native_root     := source_directory()
truffle_root    := native_root
repo_root       := truffle_root / ".." / ".."
truffle         := truffle_root / "legend-pure-next-truffle"
truffle_runtime := truffle / "legend-pure-next-truffle-runtime"
spec            := repo_root / "pure" / "specification"
compiler_src    := repo_root / "pure" / "compiler-pure"
truffle_out     := truffle_root / "build"
shared_out      := repo_root / "shared"
truffle_native  := truffle_out / "pure-truffle-native"
pure_bootstrap  := repo_root / "bootstrap" / "bin" / "pure-bootstrap"

verbose := ""
mvnq    := if verbose == "" { "-q" } else { "" }
err_filter := if verbose == "" { "2> >(grep -E -v '^(SLF4J(\\(W\\))?:|WARNING: |Note: )' >&2)" } else { "" }

banner := "printf '\\n══════════════════════════════════════════════════════════════════════════════════\\n  %-13s %s\\n══════════════════════════════════════════════════════════════════════════════════\\n'"

# Default: build + run all native tests.
default: test

# Build the GraalVM native-image binary (requires `native-image` on PATH).
build: _check-pdbs _check-graalvm
    #!/usr/bin/env bash
    set -euo pipefail
    {{banner}} '[truffle]' 'native-image build (GraalVM)'
    if ! command -v native-image >/dev/null 2>&1; then
        echo "Error: 'native-image' not on PATH." >&2
        echo "Install with: brew install --cask graalvm-jdk@25" >&2
        echo "Then set JAVA_HOME to the GraalVM JDK Home." >&2
        exit 1
    fi
    cd {{truffle}} && mvn clean -Pnative package -DskipTests {{mvnq}}
    mkdir -p {{truffle_out}}
    cp {{truffle_runtime}}/target/pure-truffle-native {{truffle_native}}

# Run all native tests (compiler-graph spec + runTests + PCT).
test: test-pure-compiler test-pure-testFunctions test-pure-PCTs

# Run compiler-graph spec tests via the native binary.
test-pure-compiler: build
    @{{banner}} '[pure→native]' 'runCompiledGraphTests'
    {{truffle_native}} execute \
        --pdb {{shared_out}}/core.pdb \
        --pdb {{shared_out}}/compiler.pdb \
        --function "meta::pure::test::runCompiledGraphTests_String_1__String_1_" \
        --args "{{spec}}/compiler" {{err_filter}}

# Run Pure runTests over meta::pure::functions via the native binary.
test-pure-testFunctions: build
    @{{banner}} '[pure→native]' 'runTests'
    {{truffle_native}} execute \
        --pdb {{shared_out}}/core.pdb \
        --pdb {{shared_out}}/compiler.pdb \
        --function "meta::pure::test::runTests_String_1__String_1_" \
        --args "meta::pure::functions" {{err_filter}}

# Run Pure PCT tests over meta::pure::functions via the native binary.
test-pure-PCTs: build
    @{{banner}} '[pure→native]' 'runPCTTests'
    {{truffle_native}} execute \
        --pdb {{shared_out}}/core.pdb \
        --pdb {{shared_out}}/compiler.pdb \
        --function "meta::pure::test::runPCTTests_String_1__String_1_" \
        --args "meta::pure::functions" {{err_filter}}

# Self-host: compile compiler-pure on the native binary, run compiler tests, then PDB-diff vs Java direct.
test-pure-self-host: build
    @{{banner}} '[self-host-native]' 'compiler-pure → compiler_truffle_native.pdb (native), then run compiler tests'
    {{truffle_native}} compile \
        --base-pdb {{shared_out}}/core.pdb \
        --base-pdb {{shared_out}}/compiler.pdb \
        --source {{compiler_src}} \
        --output {{truffle_out}}/compiler_truffle_native.pdb {{err_filter}}
    {{truffle_native}} execute \
        --pdb {{shared_out}}/core.pdb \
        --pdb {{truffle_out}}/compiler_truffle_native.pdb \
        --function "meta::pure::test::runCompiledGraphTests_String_1__String_1_" \
        --args "{{spec}}/compiler" {{err_filter}}
    @{{banner}} '[self-host-native]' 'diff compiler.pdb (Java compiler) ↔ compiler_truffle_native.pdb (native compiler)'
    {{pure_bootstrap}} diff-pdb --deep --core {{shared_out}}/core.pdb \
        {{shared_out}}/compiler.pdb \
        {{truffle_out}}/compiler_truffle_native.pdb {{err_filter}}

# Internal: fail loudly if shared/core.pdb or shared/compiler.pdb is missing.
_check-pdbs:
    #!/usr/bin/env bash
    if [[ ! -f "{{shared_out}}/core.pdb" || ! -f "{{shared_out}}/compiler.pdb" ]]; then
        echo "Error: <repo>/shared/core.pdb or compiler.pdb missing." >&2
        echo "Run 'just bootstrap::build-compiler-pdb' first (or 'just build' from the repo root)." >&2
        exit 1
    fi

# Internal: enforce GraalVM JDK at $JAVA_HOME (native-image needs it).
_check-graalvm:
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
