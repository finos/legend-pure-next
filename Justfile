# Legend Pure Next — top-level build orchestrator
#
# Usage:
#   just           # build everything
#   just generate  # generate specification artifacts from m3.ttl
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
gen_spec  := out / "specification"
cli       := out / "cli" / "pure-cli.jar"
gen_jar   := boot / "legend-pure-next-generators" / "target" / "legend-pure-next-generators-0.0.1-SNAPSHOT.jar"
gen_deps  := boot / "legend-pure-next-generators" / "target" / "dependency"

# Default: build everything
default: build

# Build the full pipeline: Java + PDBs
build: build-compiler-pdb

# --- Phase 1: Build the generators JAR (no dependency on generated spec) ---
build-generators:
    cd {{boot}} && mvn install -pl legend-pure-next-generators -am -DskipTests -q
    cd {{boot}}/legend-pure-next-generators && mvn dependency:copy-dependencies -DoutputDirectory=target/dependency -q

# --- Phase 2: Generate specification artifacts from m3.ttl into build/specification/ ---
generate: build-generators
    mkdir -p {{gen_spec}}/protocol
    java -cp "{{gen_jar}}:{{gen_deps}}/*" \
        org.finos.legend.pure.specification.generation.RdfPureGenerator \
        {{spec}}/m3.ttl {{gen_spec}}/m3.pure
    java -cp "{{gen_jar}}:{{gen_deps}}/*" \
        org.finos.legend.pure.specification.generation.RdfFbsSchemaGenerator \
        {{spec}}/m3.ttl {{gen_spec}} {{spec}}/m3_fbs_addition.fbs
    java -cp "{{gen_jar}}:{{gen_deps}}/*" \
        org.finos.legend.pure.specification.generation.M3ProtocolGenerator \
        {{spec}}/m3.ttl {{gen_spec}}/m3_protocol.ttl {{spec}}/m3_protocol_addition.ttl
    java -cp "{{gen_jar}}:{{gen_deps}}/*" \
        org.finos.legend.pure.specification.generation.RdfPureGenerator \
        {{gen_spec}}/m3_protocol.ttl {{gen_spec}}/protocol/m3_protocol.pure

# --- Phase 3: Build the full Java bootstrap (parser, compiler, execution, cli, ide) ---
build-bootstrap: generate
    cd {{boot}} && mvn install -DskipTests -q

# --- Phase 4: Package the CLI fat JAR into build/ ---
build-cli: build-bootstrap
    mkdir -p {{out}}/cli
    cp {{boot}}/legend-pure-next-cli/target/pure-cli-*-fat.jar {{cli}}

# --- Phase 5: Compile core.pdb from specification Pure sources ---
build-core-pdb: build-cli
    mkdir -p {{out}}
    java -jar {{cli}} compile-spec \
        --m3-ttl {{spec}}/m3.ttl \
        {{spec}}/functions \
        {{gen_spec}}/protocol \
        {{out}}/core.pdb

# --- Phase 6: Compile compiler.pdb from compiler-pure sources ---
build-compiler-pdb: build-core-pdb
    java -jar {{cli}} compile \
        --base-pdb {{out}}/core.pdb \
        --source {{compiler}}/src \
        --output {{out}}/compiler.pdb

# --- Phase 7: Platforms ---
build-typescript:
    @if [ -d "{{ts}}" ]; then cd {{ts}} && pnpm install && pnpm run build; else echo "platforms/typescript/ not present, skipping"; fi

# --- Phase 7a: Generate Java classes from PDB (for Truffle runtime) ---
generate-pdb-classes: build-bootstrap build-core-pdb
    cd {{truffle}} && mvn compile -DskipTests -q
    cd {{truffle}} && mvn package -DskipTests -q
    java -cp "{{truffle}}/target/pure-compile-0.0.1-SNAPSHOT-fat.jar" \
        org.finos.legend.pure.truffle.codegen.PdbJavaGenerator \
        {{truffle}}/target/generated-pdb-sources \
        {{out}}/core.pdb
    # MapImpl is hand-written in src/main/java (uses LinkedHashMap), remove generated version
    rm -f {{truffle}}/target/generated-pdb-sources/meta/pure/functions/collection/MapImpl.java

# Truffle is Maven-based — builds its own JAR, depends on bootstrap artifacts.
# After generating PDB classes, recompile to include them.
build-truffle: generate-pdb-classes
    cd {{truffle}} && mvn install -DskipTests -q
    mkdir -p {{out}}/cli
    cp {{truffle}}/target/pure-compile-*-fat.jar {{out}}/cli/pure-compile.jar

test-truffle: build-truffle build-compiler-pdb
    cd {{truffle}} && mvn test

# Run the Pure test suite via the Truffle interpreter (JVM mode).
# Useful to compare against `just test-pure` (Java tree-walking).
test-pure-truffle: build-truffle build-compiler-pdb
    java -jar {{out}}/cli/pure-compile.jar execute \
        --pdb {{out}}/core.pdb \
        --pdb {{out}}/compiler.pdb \
        --function "meta::pure::test::runCompiledGraphTests_String_1__Boolean_1_" \
        --args "{{spec}}/compiler"

# Build the native-image binary for pure-compile.
# Requires a GraalVM 23.1.x JDK (for JDK 21) on PATH or set JAVA_HOME.
# On macOS: brew install --cask graalvm-jdk@21
# Then export JAVA_HOME to the GraalVM JDK Home (see /usr/libexec/java_home -V)
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

# Run the Pure test suite via the native-image binary (requires build-truffle-native).
test-pure-native: build-truffle-native build-compiler-pdb
    {{out}}/cli/pure-compile-native execute \
        --pdb {{out}}/core.pdb \
        --pdb {{out}}/compiler.pdb \
        --function "meta::pure::test::runCompiledGraphTests_String_1__Boolean_1_" \
        --args "{{spec}}/compiler"

# --- Tests ---
# Run both Java and Pure tests. Neither short-circuits on the other's failure
# so we always see full results from both suites; exit non-zero if either failed.
test: build-compiler-pdb
    #!/usr/bin/env bash
    set +e
    java_status=0
    pure_status=0
    echo "=== Java tests (mvn test) ==="
    (cd {{boot}} && mvn test -fae) || java_status=$?
    echo ""
    echo "=== Pure tests (in-Pure test runner) ==="
    java -jar {{cli}} execute \
        --pdb {{out}}/core.pdb \
        --pdb {{out}}/compiler.pdb \
        --function "meta::pure::test::runCompiledGraphTests_String_1__Boolean_1_" \
        --args "{{spec}}/compiler" || pure_status=$?
    echo ""
    if [ $java_status -ne 0 ] || [ $pure_status -ne 0 ]; then
        echo "FAIL: java_status=$java_status pure_status=$pure_status"
        exit 1
    fi
    echo "All tests passed"

test-java: build-compiler-pdb
    cd {{boot}} && mvn test -fae

test-pure: build-compiler-pdb
    java -jar {{cli}} execute \
        --pdb {{out}}/core.pdb \
        --pdb {{out}}/compiler.pdb \
        --function "meta::pure::test::runCompiledGraphTests_String_1__Boolean_1_" \
        --args "{{spec}}/compiler"

# Run the 172 <<test.Test>> stdlib runtime tests via the Java tree-walking evaluator
# (baseline / parity oracle for the full-Truffle rewrite).
test-functions-pure: build-compiler-pdb
    java -jar {{cli}} execute \
        --pdb {{out}}/core.pdb \
        --pdb {{out}}/compiler.pdb \
        --function "meta::pure::test::runFunctionTests_String_1__Boolean_1_" \
        --args "meta::pure::functions"

# Same 172 tests via the Truffle interpreter (JVM mode).
test-functions-truffle: build-truffle build-compiler-pdb
    java -jar {{out}}/cli/pure-compile.jar execute \
        --pdb {{out}}/core.pdb \
        --pdb {{out}}/compiler.pdb \
        --function "meta::pure::test::runFunctionTests_String_1__Boolean_1_" \
        --args "meta::pure::functions"

# Same 172 tests via the native-image binary.
test-functions-native: build-truffle-native build-compiler-pdb
    {{out}}/cli/pure-compile-native execute \
        --pdb {{out}}/core.pdb \
        --pdb {{out}}/compiler.pdb \
        --function "meta::pure::test::runFunctionTests_String_1__Boolean_1_" \
        --args "meta::pure::functions"

# Run the 418 <<PCT.test>> platform conformance tests via the Java tree-walking evaluator.
test-pct-pure: build-compiler-pdb
    java -jar {{cli}} execute \
        --pdb {{out}}/core.pdb \
        --pdb {{out}}/compiler.pdb \
        --function "meta::pure::test::runPCTTests_String_1__Boolean_1_" \
        --args "meta::pure::functions"

# Same 418 PCT tests via the Truffle interpreter (JVM mode).
test-pct-truffle: build-truffle build-compiler-pdb
    java -jar {{out}}/cli/pure-compile.jar execute \
        --pdb {{out}}/core.pdb \
        --pdb {{out}}/compiler.pdb \
        --function "meta::pure::test::runPCTTests_String_1__Boolean_1_" \
        --args "meta::pure::functions"

# Same 418 PCT tests via the native-image binary.
test-pct-native: build-truffle-native build-compiler-pdb
    {{out}}/cli/pure-compile-native execute \
        --pdb {{out}}/core.pdb \
        --pdb {{out}}/compiler.pdb \
        --function "meta::pure::test::runPCTTests_String_1__Boolean_1_" \
        --args "meta::pure::functions"

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
