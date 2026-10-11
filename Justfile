# Copyright 2026 Goldman Sachs
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

# Legend Pure Next — top-level orchestrator.
#
# Quickstart:
#   just                    # build + test everything
#   just build              # build everything (no tests)
#   just test               # run all tests, then render test-results/dashboard.html
#   just ide                # launch the IDE
#   just clean              # delete shared/ and per-subproject build artifacts
#
# Per-subproject recipes are addressable as `just bootstrap::<recipe>`,
# `just truffle::<recipe>`, `just javascript::<recipe>`, `just modules::<recipe>`.
# Use `just --list bootstrap` (etc.) to see what each subproject offers.

set quiet := true
# Bash needed for process substitution (filtering stderr without touching exit codes).
set shell := ["bash", "-c"]

mod bootstrap
mod truffle "platforms/truffle/Justfile"
mod javascript "platforms/javascript/Justfile"
mod java "platforms/java/Justfile"
mod modules "pure/modules/Justfile"
mod extensions "pure/extensions/Justfile"

root := justfile_directory()
out  := root / "shared"
build_step := "node " + root / "tools" / "test-dashboard" / "build-step.mjs"

header := "h(){ s=\"[root::$1]\"; ru=$(printf '█%.0s' $(seq 1 ${#s})); printf '\\n  %s\\n  %s\\n  %s\\n' \"$ru\" \"$s\" \"$ru\"; }; h"
substep := "p(){ ru=$(printf '─%.0s' $(seq 1 $((${#1}+2)))); printf '\\n  %s\\n  ↳ %s\\n  %s\\n' \"$ru\" \"$1\" \"$ru\"; }; p"

# Build and test everything.
default: test

# Build all subprojects (bootstrap → truffle → modules → javascript). The
# javascript platform's generate recipes run the TRANSLATED translator, which
# lives in the modules tree's pdbs (javascript.pdb, translation-shared.pdb,
# javascript-translation.pdb) — so modules must build first.
# `modules::protocol::build` comes BEFORE the extensions and the rest of modules after them, because the
# dependency interleaves: the diagram extension derives its protocol form from modules/protocol, and
# `diagram-analysis` (pure/modules/analysis/diagram) declares the diagram EXTENSION as a dependency. So
# protocol -> extensions -> modules, not modules -> extensions or the reverse. A clean checkout is what
# turns getting this wrong into a hard failure — "missing pure/modules/protocol/build/protocol.pdb".
build: bootstrap::build truffle::build modules::protocol::build extensions::build-tests modules::build javascript::build

# Top-level `build` already chains every subproject's `build` in dep order — alias for symmetry with sub-Justfiles' `build-all`.
build-all: build

# Run all tests across subprojects, then render the dashboard (see `test-all`).
test:
    #!/usr/bin/env bash
    status=0
    just --justfile "{{justfile()}}" _test || status=$?
    just --justfile "{{justfile()}}" dashboard
    exit $status

# The suites `test` runs. modules::build stages the translator pdbs
# javascript::test's generate step needs (see `build` ordering note).
_test: bootstrap::test truffle::test modules::protocol::build extensions::build-tests modules::build javascript::test java::test-all

# Delete every generated/ directory so the suites below cannot pass against
# stale output. These are all gitignored build artifacts, each with a recipe
# that rebuilds it — nothing here is recoverable only from a backup:
#   pure/specification/compiler/compiler-pure/pdb/writer/generated      <- bootstrap::generate-writer
#   platforms/javascript/generated                           <- javascript::generate-all + javascript::antlr-generate-parsers
#   platforms/javascript/generated                           <- javascript::generate-all
# Found by name rather than listed, so a new one is covered automatically; if it
# has no rebuild recipe, the suite that needs it fails loudly, which is the point.
clean-generated:
    @{{header}} 'clean-generated'
    @{{substep}} 'remove every generated/ directory (gitignored build output)'
    @find . -type d -name generated \
        -not -path './.git/*' -not -path '*/target/*' -not -path '*/node_modules/*' \
        -print -exec rm -rf {} + || true

# Includes Pure-on-Java/Truffle self-host parity and standalone runtime test
# suites on top of each subproject's `test`.
# Run `test-all` across subprojects.
#
# `clean-generated` runs first so every generated/ tree is rebuilt from source
# during this run. `javascript::antlr-bundle` then has to run early, because the
# delete above removes platforms/javascript/generated/grammar (the ANTLR parsers)
# and nothing else regenerates them before a host tries to parse.
#
# It no longer has anything to do with the truffle jar: that jar once baked the
# bundle in at process-resources, and this comment said so until the JS platform
# became standalone — `PureAntlr` is now referenced only from platforms/javascript.
# The other generated/ trees are rebuilt by the subproject test-alls themselves
# (bootstrap::generate-writer via build-compiler-pdb; javascript::generate-all via
# test-compiler).
#
# The dashboard is rendered at the end even when a suite fails, so the failures show
# up there; the recipe still exits with the suites' status.
test-all:
    #!/usr/bin/env bash
    status=0
    just --justfile "{{justfile()}}" _test-all || status=$?
    just --justfile "{{justfile()}}" dashboard
    exit $status

# `clean`, not `clean-generated`: a full run starts from nothing, so every artifact under test is
# built by this run (clean-generated left each module's target/ and the staged shared/ PDBs behind).
#
# Two stages. BUILD is serial: it runs every producer whose output more than one platform reads —
# bootstrap's jar and shared/*.pdb, the module PDBs, Truffle's jar — and its order is load-bearing:
#   - javascript::antlr-bundle first: `clean` deletes the generated ANTLR parsers, and nothing else
#     regenerates them before a host tries to parse.
#   - protocol -> extensions -> modules: the diagram extension derives its protocol form from
#     modules/protocol, and diagram-analysis depends on the extension.
#   - translation_java::build-tests BEFORE truffle::test-java: Truffle translates its PDB reader with
#     the Java translator, whose entry points are <<test.TestDependency>> (java-translation-tests.pdb).
#   - language_java::build-tests AFTER truffle::test-java: it rewrites java.pdb as a split lean+tests
#     pair, which the reader generation does not load the companion of — the order test-all always had.
# SUITES then run in parallel (`_test-all-suites`): every suite only reads what BUILD produced and
# writes its own outputs. `test-all-serial` keeps the one-stage-at-a-time chain, with live output.
#
# Each stage is timed into test-results/builds.json as `test-all/<stage>` (the dashboard's build time
# trend). `clean` is not: it empties test-results/, start stamps included.
_test-all: clean (_stage-start "build") _test-all-build (_stage-end "build") _test-all-suites

_test-all-build: javascript::antlr-bundle \
    bootstrap::test-java bootstrap::stage bootstrap::build-compiler-pdb \
    modules::protocol::build extensions::build-tests modules::build modules::translation_java::build-tests \
    truffle::test-java modules::language_java::build-tests

# The suites, five branches at once, each a separate `just` writing test-results/logs/<branch>.log;
# a branch prints one line when it finishes, and the last lines of its log if it failed.
#
# A branch must not rebuild anything another branch reads. Its platform-local builds (JS
# generate-all, the Java platform's compile, Truffle's native image) are its own; the shared ones
# were done in BUILD, and where a suite's recipe depends on one it runs with --no-deps:
#   bootstrap::spec-compiler-selfhost   would re-run generate-writer, rewriting compiler-pure's
#                                       generated writer while Truffle's self-host compiles it
#   truffle::usage-example              would rebuild Truffle's jar, which java and modules run on
#   modules::*::test-all                would rebuild the module PDBs every platform reads
# The JS translation module's tests regenerate platforms/javascript/generated, so they run in the
# javascript branch after the platform's own suites, never beside them. translation_java::test-all
# is not run separately: it is `java::test-translation`, which the java branch runs.
_test-all-suites:
    #!/usr/bin/env bash
    set -uo pipefail
    {{header}} 'test-all suites'
    logs="{{root}}/test-results/logs"
    mkdir -p "$logs"
    j() { just --justfile "{{justfile()}}" "$@"; }
    branch_bootstrap()  { j bootstrap::spec && j --no-deps bootstrap::spec-compiler-selfhost; }
    branch_truffle()    { j truffle::spec truffle::spec-compiler-selfhost truffle::native::test-all && j --no-deps truffle::usage-example; }
    branch_javascript() { j javascript::test-all modules::translation_javascript::test-all; }
    branch_modules()    { j --no-deps modules::language_java::test-all modules::language_javascript::test-all modules::translation_shared::test-all; }
    branch_java()       { j java::test-all; }
    branches="bootstrap truffle javascript modules java"
    run() {
        local name=$1 started=$SECONDS status
        {{build_step}} start "test-all/$name"
        "branch_$name" > "$logs/$name.log" 2>&1
        status=$?
        local took=$(( SECONDS - started ))
        if [ "$status" -eq 0 ]; then
            {{build_step}} end "test-all/$name"
            printf '  ✓ %-11s %dm%02ds\n' "$name" $(( took / 60 )) $(( took % 60 ))
        else
            printf '  ✗ %-11s %dm%02ds  exit %s — test-results/logs/%s.log\n' "$name" $(( took / 60 )) $(( took % 60 )) "$status" "$name"
        fi
        echo "$status" > "$logs/$name.status"
    }
    echo "  running in parallel: $branches  (logs in test-results/logs/)"
    for b in $branches; do run "$b" & done
    wait
    failed=""
    for b in $branches; do
        [ "$(cat "$logs/$b.status" 2>/dev/null)" = 0 ] || failed="$failed $b"
    done
    for b in $failed; do
        printf '\n  ── %s: last 40 lines of test-results/logs/%s.log ──\n' "$b" "$b"
        tr '\r' '\n' < "$logs/$b.log" | sed 's/\x1b\[[0-9;]*[A-Za-z]//g' | grep -v '^[[:space:]]*$' | tail -n 40
    done
    if [ -n "$failed" ]; then printf '\n  failed:%s\n' "$failed"; exit 1; fi

# The previous, serial test-all: one stage at a time with live output — for chasing a failure that
# the parallel run only shows as a log tail. Same build, same suites, same timing steps.
test-all-serial:
    #!/usr/bin/env bash
    status=0
    just --justfile "{{justfile()}}" _test-all-serial || status=$?
    just --justfile "{{justfile()}}" dashboard
    exit $status

_test-all-serial: clean \
    (_stage-start "bootstrap") javascript::antlr-bundle bootstrap::test-all (_stage-end "bootstrap") \
    (_stage-start "modules-build") modules::protocol::build extensions::build-tests modules::build modules::translation_java::build-tests (_stage-end "modules-build") \
    (_stage-start "truffle") truffle::test-all (_stage-end "truffle") \
    (_stage-start "javascript") javascript::test-all (_stage-end "javascript") \
    (_stage-start "modules-test") modules::test-all (_stage-end "modules-test") \
    (_stage-start "java") java::test-all (_stage-end "java")

# Stage timing for test-all (see above): a start stamp, then the duration recorded at the end.
_stage-start STAGE:
    @{{build_step}} start "test-all/{{STAGE}}"

_stage-end STAGE:
    @{{build_step}} end "test-all/{{STAGE}}"

# Render every translator gallery (java + javascript + truffle).
gallery: modules::gallery

# Render every test report under test-results/ into test-results/dashboard.html (tools/test-dashboard).
dashboard:
    @{{header}} 'dashboard'
    @{{substep}} 'render test-results/ -> test-results/dashboard.html'
    node {{root}}/tools/test-dashboard/build.mjs

# Remove shared/, per-subproject build artifacts, every generated/ tree and the test reports.
# The dashboard's history.json and builds.json are kept: they are the trend across runs, not
# one run's output. Every generated/ tree is rebuilt by the build that consumes it
# (bootstrap::generate-writer, truffle::generate-pdb-reader, diagram's generate-protocol,
# javascript::generate-all), so removing them is safe.
#
# The last step is a guard: anything git ignores under a build/, generated/ or target/
# directory (or a shade dependency-reduced-pom.xml) that survives is a clean recipe missing
# a path, and fails here rather than going stale silently — which is how a pre-rename
# diagram.pdb and pre-rename generated JS once outlived `clean`.
clean: clean-generated bootstrap::clean truffle::clean javascript::clean java::clean modules::clean extensions::clean
    @{{header}} 'clean'
    @{{substep}} 'remove shared/ and test-results/ reports'
    rm -rf {{out}}
    find {{root}}/test-results -mindepth 1 -maxdepth 1 ! -name history.json ! -name builds.json -exec rm -rf {} + 2>/dev/null || true
    @{{substep}} 'check that no build output is left'
    left=$(git -C {{root}} ls-files --others --ignored --exclude-standard --directory | grep -E '(^|/)(build|generated|target)/$|dependency-reduced-pom\.xml$|^shared/$' || true); if [ -n "$left" ]; then printf 'clean left build output behind:\n%s\n' "$left" >&2; exit 1; fi

# Launch the bootstrap-backed Pure IDE.
ide: bootstrap::ide
