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

# --- Phase 2: Generate specification artifacts from m3.ttl ---
generate: build-generators
    mkdir -p {{spec}}/generated
    java -cp "{{gen_jar}}:{{gen_deps}}/*" \
        org.finos.legend.pure.specification.generation.RdfPureGenerator \
        {{spec}}/m3.ttl {{spec}}/generated/m3.pure
    java -cp "{{gen_jar}}:{{gen_deps}}/*" \
        org.finos.legend.pure.specification.generation.RdfFbsSchemaGenerator \
        {{spec}}/m3.ttl {{spec}}/generated {{spec}}/m3_fbs_addition.fbs
    java -cp "{{gen_jar}}:{{gen_deps}}/*" \
        org.finos.legend.pure.specification.generation.M3ProtocolGenerator \
        {{spec}}/m3.ttl {{spec}}/generated/m3_protocol.ttl {{spec}}/m3_protocol_addition.ttl
    mkdir -p {{spec}}/generated/protocol
    java -cp "{{gen_jar}}:{{gen_deps}}/*" \
        org.finos.legend.pure.specification.generation.RdfPureGenerator \
        {{spec}}/generated/m3_protocol.ttl {{spec}}/generated/protocol/m3_protocol.pure

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
        {{spec}}/generated/protocol \
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

# Truffle is Maven-based — builds its own JAR, depends on bootstrap artifacts
build-truffle: build-bootstrap
    cd {{truffle}} && mvn install -DskipTests -q

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

# --- Utilities ---
ide: build-compiler-pdb
    java -jar {{cli}} execute \
        --pdb {{out}}/core.pdb \
        --pdb {{out}}/compiler.pdb \
        --function "meta::pure::ide::start"

clean:
    rm -rf {{out}} {{spec}}/generated
    cd {{boot}} && mvn clean -q
