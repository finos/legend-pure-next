#!/usr/bin/env bash
# ©2026 JP Morgan Chase & Co. All rights reserved.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#      http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

# What needs the pinned legend-pure and legend-engine sources: fetch them, generate from them, load them through the
# bridge, and run what was loaded and the use cases (bazel run //pure/extensions/legacy:<command>). They read and write
# the source tree, under build/ (ignored) and the committed generated sources, so they are not build actions.
#
#   legacy.sh <command> <tool>=<path below the runfiles>...
#
# code/m3/m3.pure is GENERATED (generate) from legend-pure's m3.pure (fetched from the pinned legend-pure-m3-core jar),
# next's shared/specification/m3.pure and tools/m3-mapping.json. It is committed, so building needs neither the jar nor
# this script. Hand-written legacy property bodies live in code/m3/overrides.pure.

set -euo pipefail

root="${BUILD_WORKSPACE_DIRECTORY:?run it with bazel run}"
command="$1"
shift
for tool in "$@"; do
    declare "${tool%%=*}=$PWD/${tool#*=}"
done
shared="$(dirname "${shared_core:-.}")"

legacy_root="$root/pure/extensions/legacy"
test_results="$root/test-results"
legacy_out="$legacy_root/build"
# The legend-engine release whose Pure the bridge compiles (its function modules, then its core), and the legend-pure
# release it builds on: legend.pure.version in the published pom of that release, which check_versions reads back so
# the two cannot drift apart, and the version of the runner in MODULE.bazel. Both are fetched as published jars, never
# vendored.
legend_engine_version="5.0.3"
legend_pure_version="5.110.0"
legend_pure_dir="$legacy_out/legend-pure-$legend_pure_version"
legend_engine_dir="$legacy_out/legend-engine-$legend_engine_version"
maven_central="https://repo1.maven.org/maven2/org/finos/legend"
# legend-pure's modules beyond its platform that legend-engine's need (their own jars).
legend_pure_modules="legend-pure-m2-dsl-tds-pure legend-pure-m3-precisePrimitives legend-pure-m2-dsl-store-pure legend-pure-m2-dsl-mapping-pure legend-pure-m2-dsl-path-pure legend-pure-m2-dsl-graph-pure legend-pure-m2-dsl-diagram-pure legend-pure-m2-store-relational-pure"
legend_engine_modules="legend-engine-pure-functions-json-pure legend-engine-pure-functions-unclassified-pure legend-engine-pure-functions-variant-pure legend-engine-pure-functions-relation-pure legend-engine-pure-functions-standard-pure legend-engine-pure-code-compiled-core"
# The legacy modules to load: legend-pure's platform and legend-engine's, by the names their definition files give them
# (with what they depend on).
load_modules="legacy_platform core_functions_unclassified core_functions_variant core_functions_relation core_functions_standard platform_dsl_store platform_dsl_mapping platform_dsl_path platform_dsl_graph platform_dsl_diagram platform_store_relational"
loaded_out="$legacy_out/loaded"
usecase_modules="legacy_usecases_reflection legacy_usecases_functionValues"
usecases_out="$legacy_out/usecases"
usecases_expected="$legacy_root/tests/usecases/expected"

cd "$legacy_root"

header() {
    local s="[extensions-legacy::$1]" ru
    ru=$(printf '█%.0s' $(seq 1 ${#s}))
    printf '\n  %s\n  %s\n  %s\n' "$ru" "$s" "$ru"
}

# Runs a CLI, without the noise of the JVM and its logging on its error output.
quiet() {
    "$@" 2> >(grep -E -v '^(SLF4J(\(W\))?:|WARNING: |Note: )' >&2)
}

# legend_pure_version is the legend-pure release legend_engine_version builds on, and the one the runner runs.
check_versions() {
    local pom="$legend_engine_dir/legend-engine-$legend_engine_version.pom" declared
    mkdir -p "$legend_engine_dir"
    test -f "$pom" || curl -sfL -o "$pom" "$maven_central/engine/legend-engine/$legend_engine_version/legend-engine-$legend_engine_version.pom"
    declared=$(sed -n 's:.*<legend.pure.version>\(.*\)</legend.pure.version>.*:\1:p' "$pom")
    if [[ "$declared" != "$legend_pure_version" ]]; then
        echo "ERROR: legend-engine $legend_engine_version builds on legend-pure $declared, but legend_pure_version is $legend_pure_version" >&2
        exit 1
    fi
    if ! grep -q "org.finos.legend.pure:legend-pure-m3-core:$legend_pure_version\"" "$root/MODULE.bazel"; then
        echo "ERROR: the runner in MODULE.bazel does not pin legend-pure $legend_pure_version" >&2
        exit 1
    fi
}

# Unpacks the Pure and definition files of a jar from Maven Central into <dir>.
fetch_module() {
    local url="$1" dir="$2" rc=0
    test -d "$dir" && return 0
    local jar="$dir.jar"
    mkdir -p "$(dirname "$dir")"
    curl -sfL -o "$jar" "$url"
    mkdir -p "$dir.part"
    # unzip exits 11 when a pattern matches nothing: a jar may hold no definition file
    unzip -q -o "$jar" '*.pure' '*.definition.json' -d "$dir.part" || rc=$?
    if [[ $rc -ne 0 && $rc -ne 11 ]]; then echo "ERROR: unzip $jar failed ($rc)" >&2; exit 1; fi
    mv "$dir.part" "$dir"
}

# legend-engine's Pure jars at legend_engine_version, each unpacked into build/legend-engine-<version>/<artifact>/.
fetch_legend_engine() {
    local artifact
    for artifact in $legend_engine_modules; do
        fetch_module "$maven_central/engine/$artifact/$legend_engine_version/$artifact-$legend_engine_version.jar" "$legend_engine_dir/$artifact"
    done
}

# legend-pure-m3-core at its version (its platform) and legend_pure_modules, unpacked into build/legend-pure-<version>/.
fetch_legend_pure() {
    local artifact jar="$legend_pure_dir/legend-pure-m3-core-$legend_pure_version.jar"
    mkdir -p "$legend_pure_dir"
    if [[ ! -f "$legend_pure_dir/platform/pure/essential/meta/reflect/deactivate.pure" ]]; then
        curl -sfL -o "$jar" "$maven_central/pure/legend-pure-m3-core/$legend_pure_version/legend-pure-m3-core-$legend_pure_version.jar"
        unzip -q -o "$jar" 'platform/pure/*' -d "$legend_pure_dir"
    fi
    for artifact in $legend_pure_modules; do
        fetch_module "$maven_central/pure/$artifact/$legend_pure_version/$artifact-$legend_pure_version.jar" "$legend_pure_dir/modules/$artifact"
    done
}

fetch() {
    check_versions
    fetch_legend_pure
    fetch_legend_engine
}

base_pdbs() {
    printf -- '--base-pdb\n%s\n' "$shared/core.pdb"
    if [[ "${1:-}" == "with-tests" ]]; then printf -- '--base-pdb\n%s\n' "$shared/core-tests.pdb"; fi
    printf -- '--base-pdb\n%s\n' "$shared/parser-mappings.pdb" "$shared/compiler.pdb" "$shared/pure_pdb_writer.pdb" "$tds_pdb" "$legacy_pdb"
}

run_pdbs() {
    printf -- '--pdb\n%s\n' "$shared/core.pdb" "$shared/core-tests.pdb" "$shared/parser-mappings.pdb" "$shared/compiler.pdb" "$shared/pure_pdb_writer.pdb" "$tds_pdb" "$legacy_pdb"
}

read_lines() {
    local line
    eval "$1=()"
    while IFS= read -r line; do eval "$1+=(\"\$line\")"; done
}

# The use cases (tests/usecases/): legacy Pure navigating the metamodel while it executes, staged after legacy_platform
# as legend-engine's modules are, and placed by the loader.
load_usecases() {
    fetch
    node "$legacy_root/tools/load.mjs" "$legend_pure_dir/platform/pure" "$legacy_root/tests" "$usecases_out" legacy_platform $usecase_modules
    quiet "$bootstrap" execute \
        --pdb "$shared/core.pdb" \
        --pdb "$loader_pdb" \
        --function "meta::pure::bridge::loader::load_String_1__String_1_" \
        --args "$usecases_out/plan.txt" > "$usecases_out/loader.log"
}

case "$command" in
    # Fetch the pinned legend-pure and legend-engine Pure sources into build/.
    fetch)
        fetch
        ;;

    # Regenerate code/m3/m3.pure (the legacy metamodel as wrappers over next's), from the m3.pure of the pinned
    # legend-pure-m3-core jar, and by the loader natives/legacy_platform.pure (legend-pure's natives next lacks, as
    # functions failing when called), natives/legacy_platform.adapters.pure (its functions taking legacy functions,
    # overloaded for next's) and natives/legacy_platform.nextAdapters.pure (next's functions taking metamodel objects,
    # overloaded for legacy's), all compiled with the loaded legacy_platform.
    generate)
        fetch
        header generate
        node "$legacy_root/tools/generate-m3.mjs" "$legend_pure_version" "$legend_pure_dir/platform/pure/grammar/m3.pure"
        node "$legacy_root/tools/load.mjs" "$legend_pure_dir/platform/pure" "$legend_engine_dir" "$loaded_out" legacy_platform
        quiet "$bootstrap" execute \
            --pdb "$shared/core.pdb" \
            --pdb "$loader_pdb" \
            --function "meta::pure::bridge::loader::generateStubs_String_1__String_1__String_1__String_1__String_1__String_1__String_1_" \
            --args "$loaded_out/plan.txt" legacy_platform "$legacy_root/natives/legacy_platform.pure" "$legacy_root/natives/legacy_platform.adapters.pure" "$legacy_root/natives/legacy_platform.nextAdapters.pure" "legend-pure $legend_pure_version platform"
        ;;

    # Regenerate loader/code/rewrite.pure (the copy of a parsed source with its references rewritten), from next's
    # protocol model.
    generate_loader)
        header generate-loader
        node "$legacy_root/tools/generate-protocol-rewrite.mjs" "$m3_protocol"
        ;;

    # Load legacy sources into build/loaded/<module>/: staged (tools/load.mjs), then parsed by next's parser and placed
    # on top of the bridge (the loader). Each is then compiled in order, on top of core.pdb, legacy.pdb and the modules
    # before it -> build/loaded/<module>.pdb.
    load)
        fetch
        header load
        node "$legacy_root/tools/load.mjs" "$legend_pure_dir/platform/pure" "$legend_engine_dir" "$loaded_out" $load_modules
        quiet "$bootstrap" execute \
            --pdb "$shared/core.pdb" \
            --pdb "$loader_pdb" \
            --function "meta::pure::bridge::loader::load_String_1__String_1_" \
            --args "$loaded_out/plan.txt"
        # A module with islands (#TDS … #) is compiled by compiler-pure on Truffle: they need next's TDS extension,
        # whose handler is Pure (tds.pdb, on compiler.pdb). The others by the bootstrap compiler, much faster.
        # Each with its tests split out (<module>-tests.pdb), on next's and its dependencies' tests: test_loaded runs them.
        read_lines bases < <(base_pdbs with-tests)
        while read -r module _; do
            if grep -rqE '#TDS' "$loaded_out/$module/code"; then compiler="$truffle"; else compiler="$bootstrap"; fi
            echo "  compiling $module ($(basename "$compiler"))"
            quiet "$compiler" compile "${bases[@]}" --source "$loaded_out/$module" --output-dir "$loaded_out" --tests split
            bases+=(--base-pdb "$loaded_out/$module.pdb" --base-pdb "$loaded_out/$module-tests.pdb")
        done < "$loaded_out/modules.txt"
        ;;

    # Run the loaded modules' own tests (load first): their <<test.Test>> and <<PCT.test>> functions, on Truffle (whose
    # runtime evaluates #TDS islands, as the TDS extension's tests do) -> test-results/spec/legacy/loaded/{tests,pct}.json.
    test_loaded)
        set +e
        header test-loaded
        mkdir -p "$test_results/spec/legacy/loaded"
        read_lines pdbs < <(run_pdbs)
        while read -r module _; do
            pdbs+=(--pdb "$loaded_out/$module.pdb" --pdb "$loaded_out/$module-tests.pdb")
        done < "$loaded_out/modules.txt"
        failed=0
        echo "  <<test.Test>>"
        quiet "$truffle" execute "${pdbs[@]}" --function "platform::pure::test::runTests_String_1__String_1__String_1_" \
            --args "meta" "$test_results/spec/legacy/loaded/tests.json" || failed=1
        echo "  <<PCT.test>>"
        quiet "$truffle" execute "${pdbs[@]}" --function "platform::pure::test::runPCTTests_String_1__String_1__String_1_" \
            --args "meta" "$test_results/spec/legacy/loaded/pct.json" || failed=1
        exit $failed
        ;;

    # Rewrite tests/usecases/expected/ from what the loader makes of the use cases now.
    update_usecases)
        load_usecases
        header update-usecases
        for module in $usecase_modules; do
            mkdir -p "$usecases_expected/$module"
            rsync -a --delete "$usecases_out/$module/code/" "$usecases_expected/$module/"
            echo "  $module: $(ls "$usecases_expected/$module" | wc -l | tr -d ' ') file(s)"
        done
        ;;

    # What the loader makes of each use case is checked against tests/usecases/expected/<module>/ (update_usecases
    # rewrites it); then each is compiled with its tests and run -> test-results/spec/legacy/usecases/<module>.json. A
    # module that does not compile is reported and the next run.
    test_usecases)
        load_usecases
        set +e
        header test-usecases
        failed=0
        for module in $usecase_modules; do
            if diff -ru "$usecases_expected/$module" "$usecases_out/$module/code"; then
                echo "  $module: placed as expected"
            else
                echo "  $module: placed differently from tests/usecases/expected/$module (bazel run //pure/extensions/legacy:update_usecases)"; failed=1
            fi
        done
        mkdir -p "$test_results/spec/legacy/usecases"
        read_lines bases < <(base_pdbs)
        read_lines pdbs < <(run_pdbs)
        while read -r module _; do
            echo "  compiling $module"
            if [[ " $usecase_modules " == *" $module "* ]]; then
                if ! quiet "$bootstrap" compile "${bases[@]}" --source "$usecases_out/$module" --output-dir "$usecases_out" --tests with; then
                    failed=1; continue
                fi
                pdb="$usecases_out/$module-with-tests.pdb"
                quiet "$bootstrap" execute "${pdbs[@]}" --pdb "$pdb" \
                    --function "platform::pure::test::runTests_String_1__String_1__String_1_" \
                    --args "usecases" "$test_results/spec/legacy/usecases/$module.json" || failed=1
                pdbs+=(--pdb "$pdb"); bases+=(--base-pdb "$pdb")
            else
                quiet "$bootstrap" compile "${bases[@]}" --source "$usecases_out/$module" --output-dir "$usecases_out" || exit 1
                pdbs+=(--pdb "$usecases_out/$module.pdb"); bases+=(--base-pdb "$usecases_out/$module.pdb")
            fi
        done < "$usecases_out/modules.txt"
        exit $failed
        ;;

    # The use cases on legend-pure itself (tools/legacy-pure-runner, over the interpreter of legend-pure, pinned): their
    # expected values are the answers of legacy, so they must pass there as they are.
    test_usecases_on_legacy)
        check_versions
        header test-usecases-on-legacy
        out="$legacy_out/legacy-pure-runner"
        rm -rf "$out/source" && mkdir -p "$out/source"
        find "$legacy_root/tests/usecases" -path '*/expected' -prune -o -name '*.pure' -print | xargs -I{} cp {} "$out/source/"
        ids=$(grep -ohE '^function <<test.Test>> [a-zA-Z_:]+' "$out"/source/*.pure | sed 's/.* //; s/$/__Boolean_1_/' | tr '\n' ' ')
        "$runner" "$out/source" usecases $ids | tee "$out/results.txt"
        ! grep -q ' !! \| => false' "$out/results.txt"
        ;;

    *)
        echo "unknown command '$command'" >&2
        exit 1
        ;;
esac
