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

# Build all subprojects (bootstrap → modules → truffle → javascript). The
# javascript platform's generate recipes run the TRANSLATED translator, which
# lives in the modules tree's pdbs (javascript.pdb, translation-shared.pdb,
# javascript-translation.pdb) — so modules must build first.
# `modules::protocol::build` comes BEFORE the extensions and the rest of modules after them, because the
# dependency interleaves: the diagram extension derives its protocol form from modules/protocol, and
# `diagram-analysis` (pure/modules/analysis/diagram) declares the diagram EXTENSION as a dependency. So
# protocol -> extensions -> modules, not modules -> extensions or the reverse. A clean checkout is what
# turns getting this wrong into a hard failure — "missing pure/modules/protocol/build/protocol.pdb".
# All of it comes BEFORE truffle::build (it runs on bootstrap's CLI), as in `_test-all-build`:
#   - Truffle's diagram jar stages the generated pure/extensions/diagram/generated/diagram.fbs, so packaging
#     it first ships a jar without the schema ("languages/diagram.fbs is not on the class path");
#   - Truffle's generate-pdb-reader runs on java.pdb, translation-shared.pdb and java-translation{,-tests}.pdb
#     ("NoSuchFileException: .../java.pdb" on a clean checkout).
build: bootstrap::build modules::protocol::build extensions::build-tests modules::build modules::translation_java::build-tests truffle::build extensions::pure::relation::build-tests javascript::build

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
# javascript::test's generate step needs (see `build` ordering note, which is also why protocol, the
# extensions and the modules come before truffle::test).
_test: bootstrap::test modules::protocol::build extensions::build-tests modules::build modules::translation_java::build-tests truffle::test extensions::pure::relation::test-all javascript::test java::test-all

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
    truffle::test-java extensions::pure::relation::build-tests modules::language_java::build-tests

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
#   extensions::pure::tds::test-all     would rebuild tds.pdb, which java and javascript read
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
    branch_truffle()    { j truffle::spec truffle::spec-compiler-selfhost truffle::native::test-all && j --no-deps truffle::usage-example && j --no-deps extensions::pure::tds::test-all && j --no-deps extensions::pure::relation::test-all; }
    branch_javascript() { j javascript::test-all modules::translation_javascript::test-all; }
    branch_modules()    { j --no-deps modules::language_java::test-all modules::language_javascript::test-all modules::translation_shared::test-all; }
    branch_java()       { j java::test-all; }
    branches="bootstrap truffle javascript modules java"
    rm -f "$logs"/*.status "$logs"/*.result "$logs"/*.steps "$logs/.done"
    # On a terminal, a live block redrawn every second: one line per lane, with the step it is on and a
    # bar for that step's (n/total) counter. Each step a lane finishes is printed above the block, with
    # its time. Otherwise (CI, redirected output) the finished steps and each lane's ✓/✗ as they happen,
    # and a status line every 30s.
    live=false; [ -t 1 ] && live=true
    started_all=$SECONDS
    run() {
        local name=$1 started=$SECONDS status line
        {{build_step}} start "test-all/$name"
        "branch_$name" > "$logs/$name.log" 2>&1
        status=$?
        local took=$(( SECONDS - started ))
        if [ "$status" -eq 0 ]; then
            {{build_step}} end "test-all/$name"
            line=$(printf '✓ %dm%02ds' $(( took / 60 )) $(( took % 60 )))
        else
            line=$(printf '✗ %dm%02ds  exit %s — test-results/logs/%s.log' $(( took / 60 )) $(( took % 60 )) "$status" "$name")
        fi
        echo "$status" > "$logs/$name.status"
        echo "$line" > "$logs/$name.result"
    }
    # A lane's log, scanned: every step header ([module::recipe]) in order, one per line, then a last line
    # "#<TAB>n<TAB>total" with the latest progress counter ("37%  (59/155)") after the last header.
    lane_scan() {
        # A lane that has not created its log yet is starting: no steps, no counter.
        [ -f "$logs/$1.log" ] || { printf '#\t\t\n'; return; }
        LC_ALL=C tr '\r' '\n' < "$logs/$1.log" | LC_ALL=C awk '
            match($0, /\[[A-Za-z0-9_-]+(::[A-Za-z0-9_-]+)+\]/) { print substr($0, RSTART + 1, RLENGTH - 2); n = ""; m = "" }
            match($0, /[0-9]+%  \([0-9]+\/[0-9]+\)/) { s = substr($0, RSTART, RLENGTH); sub(/.*\(/, "", s); sub(/\)/, "", s); split(s, a, "/"); n = a[1]; m = a[2] }
            END { printf "#\t%s\t%s\n", n, m }'
    }
    # The previous passing run's steps per lane, "lane<TAB>index<TAB>step<TAB>seconds": the reference for
    # step counts and time estimates. Kept by `clean`, like builds.json.
    steps_ref="$(dirname "$logs")/lane-steps.tsv"
    lane_ref() { [ -f "$steps_ref" ] && awk -F'\t' -v l="$1" '$1 == l { print $2 "\t" $3 "\t" $4 }' "$steps_ref"; }
    # "total|usual|left" for a lane on its k-th step (named `step`, running for `took` seconds), from its
    # reference (`ref`, "index<TAB>step<TAB>seconds" lines); all three empty without one.
    lane_estimate() {
        printf '%s\n' "$1" | awk -F'\t' -v k="$2" -v step="$3" -v took="$4" '
            NF == 3 { n++; name[$1] = $2; secs[$1] = $3; if (!($2 in byname)) byname[$2] = $3 }
            END {
                if (n == 0) { print "||"; exit }
                usual = (name[k] == step) ? secs[k] : ((step in byname) ? byname[step] : "")
                left = (usual == "" || usual + 0 < took + 0) ? 0 : usual - took
                for (i = k + 1; i <= n; i++) left += secs[i]
                printf "%s|%s|%s\n", n, usual, left
            }'
    }
    # A running lane's line in the live block: its step number, its step and how long that step has run
    # against its usual time, a bar for the step's counter, and the time the lane has left.
    lane_line() {
        local name=$1 tick=$2 step=$3 n=$4 m=$5 took=$6 k=$7 total=$8 usual=$9 left=${10} width=20 filled bar pos time tail
        pos="$k${total:+/$total}"
        time="$(duration "$took")${usual:+/~$(duration "$usual")}"
        tail="${left:+  ~$(duration "$left") left}"
        if [ -n "$m" ] && [ "$m" -gt 0 ]; then
            filled=$(( n * width / m ))
            bar=$(printf '%*s' "$filled" '' | sed 's/ /█/g')$(printf '%*s' $(( width - filled )) '' | sed 's/ /░/g')
            printf '  %-11s %-6s %-26.26s %-13s [%s] %5s/%-5s%s' "$name" "$pos" "$step" "$time" "$bar" "$n" "$m" "$tail"
        else
            printf '  %-11s %-6s %-26.26s %-13s %s%s' "$name" "$pos" "${step:-starting}" "$time" "$(echo '|/-\' | cut -c$(( tick % 4 + 1 )))" "$tail"
        fi
    }
    duration() { printf '%dm%02ds' $(( $1 / 60 )) $(( $1 % 60 )); }
    # "  ── title ─────…", 78 columns wide: the live view's section rules.
    rule() { local r; r=$(printf '%*s' $(( 74 - ${#1} )) '' | sed 's/ /─/g'); printf '  ── %s %s' "$1" "$r"; }
    elapsed() { duration $(( SECONDS - started_all )); }
    # One finished step: a line above the live block ("  ✓ lane  step  1m02s"), and a line of this run's
    # steps for the next run's reference.
    finish_step() {
        done_lines="$done_lines$(printf '  %s %-11s %-28s %s' "$1" "$2" "$4" "$(duration "$5")")"$'\n'
        printf '%s\t%s\t%s\t%s\n' "$2" "$3" "$4" "$5" >> "$logs/$2.steps"
    }
    progress() {
        local tick=0 lines=0 b out done_lines scan steps count k t name step n m mark total usual left most
        for b in $branches; do printf -v "ref_$b" '%s' "$(lane_ref "$b")"; done
        while :; do
            # The run's script is gone (killed outright): stop drawing rather than run on as an orphan.
            kill -0 "$main_pid" 2>/dev/null || break
            local finished=false; [ -f "$logs/.done" ] && finished=true
            out=""; done_lines=""; most=""
            for b in $branches; do
                scan=$(lane_scan "$b")
                steps=$(printf '%s\n' "$scan" | sed '$d')
                count=$(printf '%s' "$steps" | grep -c '^')
                IFS=$'\t' read -r _ n m <<< "$(printf '%s\n' "$scan" | tail -n 1)"
                # k_<lane>: steps seen so far (the k-th is the one running); t_<lane>: when it started.
                name="k_$b"; k=${!name:-0}; name="t_$b"; t=${!name:-$SECONDS}
                # Every step before the newest has finished: the running one since t, any seen only now in 0s.
                while [ "$k" -lt "$count" ]; do
                    if [ "$k" -gt 0 ]; then
                        step=$(printf '%s\n' "$steps" | sed -n "${k}p")
                        finish_step ✓ "$b" "$k" "${step#"$b"::}" $(( SECONDS - t ))
                        t=$SECONDS
                    fi
                    k=$(( k + 1 ))
                done
                printf -v "t_$b" '%s' "$t"
                printf -v "k_$b" '%s' "$k"
                # The lane has ended: its last step gets the lane's mark.
                name="closed_$b"
                if [ -f "$logs/$b.result" ] && [ -z "${!name:-}" ]; then
                    printf -v "closed_$b" '%s' 1
                    if [ "$k" -gt 0 ]; then
                        mark='✓'; [ "$(cat "$logs/$b.status" 2>/dev/null)" = 0 ] || mark='✗'
                        step=$(printf '%s\n' "$steps" | sed -n "${k}p")
                        finish_step "$mark" "$b" "$k" "${step#"$b"::}" $(( SECONDS - t ))
                    fi
                    $live || done_lines="$done_lines$(printf '  %-11s %s' "$b" "$(cat "$logs/$b.result")")"$'\n'
                fi
                [ -f "$logs/$b.result" ] && { $live && out="$out$(printf '  %-11s %s' "$b" "$(cat "$logs/$b.result")")"$'\n'; continue; }
                step=$(printf '%s\n' "$steps" | tail -n 1); step=${step#"$b"::}
                name="ref_$b"
                IFS='|' read -r total usual left <<< "$(lane_estimate "${!name}" "$k" "$step" $(( SECONDS - t )))"
                [ -n "$left" ] && { [ -z "$most" ] || [ "$left" -gt "$most" ]; } && most=$left
                if $live; then
                    out="$out$(lane_line "$b" "$tick" "$step" "$n" "$m" $(( SECONDS - t )) "$k" "$total" "$usual" "$left")"$'\n'
                else
                    out="$out · $b $k${total:+/$total} $step${m:+ $n/$m} ($(duration $(( SECONDS - t )))${left:+, ~$(duration "$left") left})"
                fi
            done
            if $live; then
                [ "$lines" -gt 0 ] && printf '\033[%dA' "$lines"
                printf '%s\n%s\n%s' "$done_lines" "$(rule "lanes · $(elapsed)${most:+ · ~$(duration "$most") left}")" "$out" | while IFS= read -r l; do printf '\033[2K%s\n' "$l"; done
                lines=$(( $(echo $branches | wc -w) + 2 ))
            else
                printf '%s' "$done_lines"
                if [ $(( tick % 30 )) -eq 0 ] && [ "$tick" -gt 0 ] && [ -n "$out" ]; then
                    printf '  [%s]%s %s\n' "$(elapsed)" "${most:+ ~$(duration "$most") left ·}" "${out# · }"
                fi
            fi
            $finished && break
            sleep 1; tick=$(( tick + 1 ))
        done
    }
    # Ctrl-C (or a kill) stops the whole run. Background jobs of a script ignore SIGINT, so without this the
    # lanes, their just/java processes and the display would carry on after the script is gone.
    main_pid=$$
    pids=""
    kill_tree() { local c; for c in $(pgrep -P "$1"); do kill_tree "$c"; done; kill "$1" 2>/dev/null; }
    stop_all() {
        trap - INT TERM
        local p
        for p in $pids ${progress_pid:-}; do kill_tree "$p"; done
        printf '\n  interrupted\n'
        exit 130
    }
    trap stop_all INT TERM
    echo "  running in parallel: $branches  (logs in test-results/logs/)"
    for b in $branches; do run "$b" & pids="$pids $!"; done
    $live && printf '\n%s\n' "$(rule 'finished steps')"
    progress & progress_pid=$!
    wait $pids
    touch "$logs/.done"
    wait "$progress_pid"
    # This run's steps become the next run's reference for the lanes that passed. A failed lane's list stops
    # at the failure, so it keeps its previous reference.
    for b in $branches; do
        if [ "$(cat "$logs/$b.status" 2>/dev/null)" = 0 ] && [ -f "$logs/$b.steps" ]; then
            cat "$logs/$b.steps"
        else
            lane_ref "$b" | while IFS= read -r l; do printf '%s\t%s\n' "$b" "$l"; done
        fi
    done > "$steps_ref.tmp" && mv "$steps_ref.tmp" "$steps_ref"
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
    (_stage-start "truffle") truffle::build extensions::pure::relation::build-tests truffle::test-all extensions::pure::relation::test-all extensions::pure::tds::test-all (_stage-end "truffle") \
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

# Draw the Justfiles: every recipe, its dependencies and the CLI it runs Pure with, as a page you open
# (tools/just-graph/build/just-graph.html). Regenerate it after changing a Justfile.
just-graph:
    @{{header}} 'just-graph'
    @{{substep}} 'just --dump -> tools/just-graph/build/just-graph.html'
    node {{root}}/tools/just-graph/generate.mjs

# Remove shared/, per-subproject build artifacts, every generated/ tree and the test reports.
# The dashboard's history.json and builds.json are kept: they are the trend across runs, not
# one run's output. Every generated/ tree is rebuilt by the build that consumes it
# (bootstrap::generate-writer, truffle::generate-pdb-reader, diagram's generate,
# javascript::generate-all), so removing them is safe.
#
# The last step is a guard: anything git ignores under a build/, generated/ or target/
# directory (or a shade dependency-reduced-pom.xml) that survives is a clean recipe missing
# a path, and fails here rather than going stale silently — which is how a pre-rename
# diagram.pdb and pre-rename generated JS once outlived `clean`.
clean: clean-generated bootstrap::clean truffle::clean javascript::clean java::clean modules::clean extensions::clean
    @{{header}} 'clean'
    @{{substep}} 'remove shared/, test-results/ reports and the Justfile graph page'
    rm -rf {{out}}
    rm -rf {{root}}/tools/just-graph/build
    find {{root}}/test-results -mindepth 1 -maxdepth 1 ! -name history.json ! -name builds.json ! -name lane-steps.tsv -exec rm -rf {} + 2>/dev/null || true
    @{{substep}} 'check that no build output is left'
    left=$(git -C {{root}} ls-files --others --ignored --exclude-standard --directory | grep -E '(^|/)(build|generated|target)/$|dependency-reduced-pom\.xml$|^shared/$' || true); if [ -n "$left" ]; then printf 'clean left build output behind:\n%s\n' "$left" >&2; exit 1; fi

# Launch the bootstrap-backed Pure IDE.
ide: bootstrap::ide
