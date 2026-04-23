# Legend Pure Next — top-level build orchestrator
#
# Usage:
#   just           # build everything
#   just test      # run all tests (Java + Pure)
#   just ide       # launch the IDE
#   just clean     # clean everything

root      := justfile_directory()
spec      := root / "specification"
boot      := root / "bootstrap"
compiler  := root / "compiler-pure"
platforms := root / "platforms"
ts        := platforms / "typescript"
truffle   := platforms / "truffle"
out       := root / "build"
cli       := out / "cli" / "pure-cli.jar"

# Default: build and test everything
default: test

# Build the full pipeline: Java + PDBs
build: build-compiler-pdb

# --- Bootstrap: Maven handles generation, compilation, PDB build, and tests ---
build-bootstrap:
    cd {{boot}} && mvn install

# --- Copy artifacts to build/ for downstream (CLI, truffle, etc.) ---
stage: build-bootstrap
    mkdir -p {{out}}/cli {{out}}/specification/protocol
    cp {{boot}}/legend-pure-next-cli/target/pure-cli-*-fat.jar {{cli}}
    cp {{boot}}/legend-pure-next-compiler/target/classes/core.pdb {{out}}/core.pdb
    cp -r {{boot}}/legend-pure-next-generators/target/generated-specification/* {{out}}/specification/

# --- Copy pre-built artifacts to build/ (no rebuild) ---
copy:
    mkdir -p {{out}}/cli {{out}}/specification/protocol
    cp {{boot}}/legend-pure-next-cli/target/pure-cli-*-fat.jar {{cli}}
    cp {{boot}}/legend-pure-next-compiler/target/classes/core.pdb {{out}}/core.pdb
    cp -r {{boot}}/legend-pure-next-generators/target/generated-specification/* {{out}}/specification/

# --- Compile compiler.pdb from compiler-pure sources ---
build-compiler-pdb: stage
    java -jar {{cli}} compile \
        --base-pdb {{out}}/core.pdb \
        --source {{compiler}} \
        --output {{out}}/compiler.pdb

# --- Platforms ---
build-typescript:
    @if [ -d "{{ts}}" ]; then cd {{ts}} && pnpm install && pnpm run build; else echo "platforms/typescript/ not present, skipping"; fi

truffle_codegen := truffle / "legend-pure-next-truffle-codegen"
truffle_runtime := truffle / "legend-pure-next-truffle-runtime"

# --- Build codegen module and generate PDB classes ---
generate-pdb-classes: stage
    cd {{truffle_codegen}} && mvn package -DskipTests -q
    # Remove hand-written MapImpl (uses LinkedHashMap, lives in runtime src)
    rm -f {{truffle_runtime}}/target/generated-pdb-sources/org/finos/legend/pure/truffle/pdb/meta/pure/functions/collection/MapImpl.java

# Build Truffle runtime (includes generated PDB classes)
build-truffle: generate-pdb-classes
    cd {{truffle_runtime}} && mvn install -DskipTests -q
    mkdir -p {{out}}/cli
    cp {{truffle_runtime}}/target/pure-compile-*-fat.jar {{out}}/cli/pure-compile.jar

test-truffle: build-truffle build-compiler-pdb
    cd {{truffle_runtime}} && mvn test

# Run the Pure test suite via the Truffle interpreter (JVM mode).
test-pure-truffle: build-truffle build-compiler-pdb
    java -jar {{out}}/cli/pure-compile.jar execute \
        --pdb {{out}}/core.pdb \
        --pdb {{out}}/compiler.pdb \
        --function "meta::pure::test::runCompiledGraphTests_String_1__Boolean_1_" \
        --args "{{spec}}/compiler"

# Build the native-image binary for pure-compile.
build-truffle-native: build-bootstrap
    #!/usr/bin/env bash
    set -euo pipefail
    if ! command -v native-image >/dev/null 2>&1; then
        echo "Error: 'native-image' not on PATH." >&2
        echo "GraalVM is required. Install with:" >&2
        echo "  brew install --cask graalvm-jdk@21" >&2
        echo "Then set JAVA_HOME to the GraalVM JDK Home." >&2
        exit 1
    fi
    cd {{truffle}} && mvn -Pnative package -DskipTests -q
    mkdir -p {{out}}/cli
    cp {{truffle}}/target/pure-compile {{out}}/cli/pure-compile-native
    echo "Native binary: {{out}}/cli/pure-compile-native"

# --- Tests ---
# Bootstrap Java tests run as part of `mvn install` in build-bootstrap.
# This target runs Pure-level tests on both bootstrap and Truffle interpreters.
test: test-compiler-pure-truffle
    java -jar {{cli}} execute \
        --pdb {{out}}/core.pdb \
        --pdb {{out}}/compiler.pdb \
        --function "meta::pure::test::runCompiledGraphTests_String_1__Boolean_1_" \
        --args "{{spec}}/compiler"

# Run the compiler-pure tests via the Truffle interpreter.
test-compiler-pure-truffle: build-truffle build-compiler-pdb
    java -jar {{out}}/cli/pure-compile.jar execute \
        --pdb {{out}}/core.pdb \
        --pdb {{out}}/compiler.pdb \
        --function "meta::pure::test::runCompiledGraphTests_String_1__Boolean_1_" \
        --args "{{spec}}/compiler"

test-pure: build-compiler-pdb
    java -jar {{cli}} execute \
        --pdb {{out}}/core.pdb \
        --pdb {{out}}/compiler.pdb \
        --function "meta::pure::test::runCompiledGraphTests_String_1__Boolean_1_" \
        --args "{{spec}}/compiler"

test-functions-pure: build-compiler-pdb
    java -jar {{cli}} execute \
        --pdb {{out}}/core.pdb \
        --pdb {{out}}/compiler.pdb \
        --function "meta::pure::test::runFunctionTests_String_1__Boolean_1_" \
        --args "meta::pure::functions"

test-functions-truffle: build-truffle build-compiler-pdb
    java -jar {{out}}/cli/pure-compile.jar execute \
        --pdb {{out}}/core.pdb \
        --pdb {{out}}/compiler.pdb \
        --function "meta::pure::test::runFunctionTests_String_1__Boolean_1_" \
        --args "meta::pure::functions"

test-functions-native: build-truffle-native build-compiler-pdb
    {{out}}/cli/pure-compile-native execute \
        --pdb {{out}}/core.pdb \
        --pdb {{out}}/compiler.pdb \
        --function "meta::pure::test::runFunctionTests_String_1__Boolean_1_" \
        --args "meta::pure::functions"

test-pct-pure: build-compiler-pdb
    java -jar {{cli}} execute \
        --pdb {{out}}/core.pdb \
        --pdb {{out}}/compiler.pdb \
        --function "meta::pure::test::runPCTTests_String_1__Boolean_1_" \
        --args "meta::pure::functions"

test-pct-truffle: build-truffle build-compiler-pdb
    java -jar {{out}}/cli/pure-compile.jar execute \
        --pdb {{out}}/core.pdb \
        --pdb {{out}}/compiler.pdb \
        --function "meta::pure::test::runPCTTests_String_1__Boolean_1_" \
        --args "meta::pure::functions"

test-pct-native: build-truffle-native build-compiler-pdb
    {{out}}/cli/pure-compile-native execute \
        --pdb {{out}}/core.pdb \
        --pdb {{out}}/compiler.pdb \
        --function "meta::pure::test::runPCTTests_String_1__Boolean_1_" \
        --args "meta::pure::functions"

test-pure-native: build-truffle-native build-compiler-pdb
    {{out}}/cli/pure-compile-native execute \
        --pdb {{out}}/core.pdb \
        --pdb {{out}}/compiler.pdb \
        --function "meta::pure::test::runCompiledGraphTests_String_1__Boolean_1_" \
        --args "{{spec}}/compiler"

# --- Benchmarks ---
bench-truffle: build-truffle build-compiler-pdb
    cd {{truffle}} && mvn -Pbench test -Dtest=PureEvaluatorBenchmark

# --- Utilities ---
ide: build-compiler-pdb
    java -jar {{cli}} execute \
        --pdb {{out}}/core.pdb \
        --pdb {{out}}/compiler.pdb \
        --function "meta::pure::ide::start"

clean:
    rm -rf {{out}}
    cd {{boot}} && mvn clean -q
