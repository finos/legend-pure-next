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
# Exactly this flatc: bootstrap-compiler's `flatc --java` output calls Constants.FLATBUFFERS_<version>(),
# which only the matching flatbuffers-java runtime (pinned in its pom) has. Same pin as CI.
flatc_version := "25.2.10"
build_step := "node " + root / "tools" / "test-dashboard" / "build-step.mjs"

header := "h(){ s=\"[root::$1]\"; ru=$(printf '█%.0s' $(seq 1 ${#s})); printf '\\n  %s\\n  %s\\n  %s\\n' \"$ru\" \"$s\" \"$ru\"; }; h"
substep := "p(){ ru=$(printf '─%.0s' $(seq 1 $((${#1}+2)))); printf '\\n  %s\\n  ↳ %s\\n  %s\\n' \"$ru\" \"$1\" \"$ru\"; }; p"

# Build and test everything.
default: test

# Check the toolchain and explain what is missing. `build`, `test` and `test-all` run it first, so a
# fresh machine fails here with an install hint instead of a "command not found" deep in a recipe.
# Pass "test-all" to also require native-image, which only Truffle's native suites use.
# Versions mirror .github/workflows: GraalVM 25.0.2, Node 22, pnpm 9.
doctor scope="build":
    #!/usr/bin/env bash
    missing=0
    ok()  { printf '  ✓ %s\n' "$1"; }
    bad() { printf '  ✗ %s\n' "$1" >&2; shift; for l in "$@"; do printf '      %s\n' "$l" >&2; done; missing=1; }
    major() { "$1" -version 2>&1 | sed -nE '1s/.*version "([0-9]+).*/\1/p'; }
    graal_hint=("just setup   (installs GraalVM 25 and sets JAVA_HOME + PATH in your shell init file)")
    {{header}} 'doctor'
    # Maven and Truffle use $JAVA_HOME; bin/pure-bootstrap and the ANTLR step use `java` on PATH.
    if [[ -z "${JAVA_HOME:-}" ]]; then
        bad "JAVA_HOME is not set (a GraalVM 25 JDK is required)" "${graal_hint[@]}"
    elif [[ ! -x "$JAVA_HOME/bin/java" ]]; then
        bad "JAVA_HOME ($JAVA_HOME) has no bin/java" "${graal_hint[@]}"
    elif (( $(major "$JAVA_HOME/bin/java" || echo 0) < 25 )); then
        bad "JAVA_HOME is Java $(major "$JAVA_HOME/bin/java"), every pom targets release 25" "${graal_hint[@]}"
    elif ! "$JAVA_HOME/bin/java" -version 2>&1 | grep -qi graalvm; then
        bad "JAVA_HOME ($JAVA_HOME) is not GraalVM; Truffle needs its optimizing runtime" "${graal_hint[@]}"
    else
        ok "JAVA_HOME: GraalVM $(major "$JAVA_HOME/bin/java")"
    fi
    m=$(major java); m=${m:-0}
    if (( m < 25 )); then
        bad "\`java\` on PATH is $([[ $m = 0 ]] && echo 'missing or not a JDK' || echo "Java $m"), 25+ required" "${graal_hint[@]}"
    else
        ok "java on PATH: $m"
    fi
    if [[ "{{scope}}" == test-all* ]]; then
        command -v native-image >/dev/null && ok "native-image" \
            || bad "native-image not on PATH (Truffle's native suites; ships with GraalVM)" "${graal_hint[@]}"
        # native-image links with the system C toolchain. Linking a trivial program catches a broken
        # one up front, e.g. a newer SDK left in CommandLineTools/SDKs that this ld cannot read.
        t=$(mktemp -d); echo 'int main(void){return 0;}' > "$t/t.c"
        if ! command -v cc >/dev/null; then
            bad "no C compiler (native-image links with it)" "xcode-select --install"
        elif err=$(cc -o "$t/t" "$t/t.c" 2>&1); then
            ok "C toolchain links (SDK: $(basename "$(xcrun --show-sdk-path 2>/dev/null)"))"
        else
            bad "the C toolchain cannot link a trivial program (native-image needs it):" \
                "default SDK $(basename "$(xcrun --show-sdk-path 2>/dev/null)"): $(grep 'error:' <<<"$err" | grep -v 'linker command failed' | tail -1 | sed 's/^.*error: //')" \
                "just setup   (pins SDKROOT to an installed SDK that links), or fix the install for good:" \
                "sudo rm -rf /Library/Developer/CommandLineTools && xcode-select --install"
        fi
        rm -rf "$t"
        # javascript::test-ide drives headless Chrome over CDP; same lookup as ide-tests.js.
        chrome=""
        for c in "${CHROME_PATH:-}" "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome" \
                 /usr/bin/google-chrome /usr/bin/google-chrome-stable /usr/bin/chromium /usr/bin/chromium-browser; do
            [[ -n "$c" && -e "$c" ]] && { chrome=$c; break; }
        done
        [[ -n "$chrome" ]] && ok "chrome: $chrome" \
            || bad "Chrome not found (javascript::test-ide drives it headless)" "brew install --cask google-chrome   (or: just setup), or set CHROME_PATH"
    fi
    command -v mvn  >/dev/null && ok "mvn: $(mvn -v 2>/dev/null | sed -nE '1s/Apache Maven ([^ ]+).*/\1/p')" \
        || bad "mvn not found (bootstrap, truffle and java builds)" "brew install maven   (or: just setup)"
    command -v node >/dev/null && ok "node: $(node -v)" \
        || bad "node not found (test dashboard + JavaScript platform; CI uses 22)" "brew install node@22   (or: just setup)"
    command -v pnpm >/dev/null && ok "pnpm: $(pnpm -v)" \
        || bad "pnpm not found (JavaScript platform; CI uses 9)" "brew install pnpm   (or: just setup)"
    fv=$(flatc --version 2>/dev/null | sed -nE 's/^flatc version //p')
    if [[ "$fv" == "{{flatc_version}}" ]]; then
        ok "flatc: $fv"
    else
        bad "flatc $([[ -z $fv ]] && echo 'not found' || echo "is $fv"), exactly {{flatc_version}} required (bootstrap's generated Java must match flatbuffers-java {{flatc_version}})" \
            "just setup   (installs the {{flatc_version}} release binary to ~/.local/bin; Homebrew's flatbuffers is a different version)"
    fi
    if (( missing )); then
        printf '\n  Missing requirements above. `just setup` installs them (macOS, Homebrew + flatc release binary).\n' >&2
        exit 1
    fi

# Install the toolchain `doctor` checks, with Homebrew (macOS). Never run implicitly: it installs
# software system-wide (flatc into ~/.local/bin), and appends marked JAVA_HOME/PATH (and, only when
# the C toolchain cannot link, SDKROOT) blocks to your shell init file (once each).
setup:
    #!/usr/bin/env bash
    set -euo pipefail
    {{header}} 'setup'
    if ! command -v brew >/dev/null; then
        echo "Homebrew not found: install it from https://brew.sh, or install the tools listed by \`just doctor\` by hand." >&2
        exit 1
    fi
    ls /Library/Java/JavaVirtualMachines 2>/dev/null | grep -qi graalvm.*25 || brew install --cask graalvm-jdk@25
    command -v mvn  >/dev/null || brew install maven
    command -v node >/dev/null || { brew install node@22 && brew link --overwrite node@22; }
    command -v pnpm >/dev/null || brew install pnpm
    [[ -n "${CHROME_PATH:-}" || -e "/Applications/Google Chrome.app" ]] || brew install --cask google-chrome
    # flatc from the GitHub release, as CI does: Homebrew's flatbuffers is not {{flatc_version}}.
    if [[ "$(flatc --version 2>/dev/null)" != "flatc version {{flatc_version}}" &&
          "$("$HOME/.local/bin/flatc" --version 2>/dev/null)" != "flatc version {{flatc_version}}" ]]; then
        tmp=$(mktemp -d); trap 'rm -rf "$tmp"' EXIT
        # Mac.flatc.binary.zip is a universal (arm64 + x86_64) binary.
        curl -fsSL "https://github.com/google/flatbuffers/releases/download/v{{flatc_version}}/Mac.flatc.binary.zip" -o "$tmp/flatc.zip"
        unzip -q -o "$tmp/flatc.zip" -d "$tmp"
        mkdir -p "$HOME/.local/bin"
        install -m 755 "$tmp/flatc" "$HOME/.local/bin/flatc"
        echo "  installed flatc {{flatc_version}} to ~/.local/bin/flatc"
    fi
    # Point JAVA_HOME at GraalVM 25 from the shell init file. The GraalVM path is written literally:
    # `java_home -v 25` could pick another JDK 25 if one is installed beside it.
    graal=$(ls -d /Library/Java/JavaVirtualMachines/graalvm*25*.jdk/Contents/Home 2>/dev/null | head -1)
    [[ -n "$graal" ]] || { echo "GraalVM 25 not found under /Library/Java/JavaVirtualMachines after install." >&2; exit 1; }
    case "$(basename "${SHELL:-zsh}")" in
        bash) rc="$HOME/.bash_profile" ;;
        *)    rc="$HOME/.zshrc" ;;
    esac
    marker="# >>> legend-pure-next JAVA_HOME >>>"
    if grep -qF "$marker" "$rc" 2>/dev/null; then
        echo "  $rc already has the legend-pure-next JAVA_HOME block"
    elif grep -qE '^[^#]*JAVA_HOME=' "$rc" 2>/dev/null; then
        echo "  $rc already sets JAVA_HOME; left as is. Point it at $graal if \`just doctor\` complains." >&2
    else
        printf '\n%s\nexport JAVA_HOME="%s"\nexport PATH="$JAVA_HOME/bin:$HOME/.local/bin:$PATH"\n%s\n' \
            "$marker" "$graal" "# <<< legend-pure-next JAVA_HOME <<<" >> "$rc"
        echo "  added JAVA_HOME=$graal to $rc"
    fi
    # native-image needs a C toolchain that links. If the default SDK does not (a newer SDK left in
    # CommandLineTools/SDKs that this ld cannot read), pin SDKROOT to the newest installed one that
    # does, in its own marked block — a workaround to delete once the Command Line Tools are fixed.
    xcode-select -p >/dev/null 2>&1 || { echo "  installing the Command Line Tools: finish the dialog, then re-run \`just setup\`"; xcode-select --install; exit 1; }
    t=$(mktemp -d); echo 'int main(void){return 0;}' > "$t/t.c"
    sdk_marker="# >>> legend-pure-next SDKROOT >>>"
    if ! cc -o "$t/t" "$t/t.c" 2>/dev/null; then
        sdk=""
        for s in $(ls -d /Library/Developer/CommandLineTools/SDKs/MacOSX[0-9]*.sdk 2>/dev/null | sort -rV); do
            [[ -L "$s" ]] && continue
            SDKROOT="$s" cc -o "$t/t" "$t/t.c" 2>/dev/null && { sdk=$s; break; }
        done
        if [[ -z "$sdk" ]]; then
            echo "  no installed SDK links; reinstall the Command Line Tools:" >&2
            echo "    sudo rm -rf /Library/Developer/CommandLineTools && xcode-select --install" >&2
        elif grep -qF "$sdk_marker" "$rc" 2>/dev/null; then
            echo "  $rc already has the legend-pure-next SDKROOT block (this shell has not loaded it)"
            pinned=$(sed -nE 's/^export SDKROOT="(.*)"$/\1/p' "$rc" | tail -1)
            [[ "$pinned" == "$sdk" ]] || echo "  it pins $pinned, but $sdk is the newest SDK that links: edit that block" >&2
            export SDKROOT="$pinned"
        else
            printf '\n%s\n# The default SDK (%s) does not link with this ld. Delete this block once\n# `sudo rm -rf /Library/Developer/CommandLineTools && xcode-select --install` is done.\nexport SDKROOT="%s"\n%s\n' \
                "$sdk_marker" "$(basename "$(xcrun --show-sdk-path)")" "$sdk" "# <<< legend-pure-next SDKROOT <<<" >> "$rc"
            echo "  default SDK $(basename "$(xcrun --show-sdk-path)") does not link: pinned SDKROOT=$sdk in $rc"
            export SDKROOT="$sdk"
        fi
    fi
    rm -rf "$t"
    # Verify with what the init file now sets, in a fresh `just` (this one predates the install).
    export JAVA_HOME="$graal" PATH="$graal/bin:$HOME/.local/bin:$PATH"
    just --justfile "{{justfile()}}" doctor test-all
    printf '\n  Open a new terminal (or `source %s`) so your shell picks up the changes.\n' "$rc"

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
build: doctor bootstrap::build modules::protocol::build extensions::build-tests modules::build modules::translation_java::build-tests truffle::build javascript::build

# Top-level `build` already chains every subproject's `build` in dep order — alias for symmetry with sub-Justfiles' `build-all`.
build-all: build

# Run all tests across subprojects, then render the dashboard (see `test-all`).
test: doctor
    #!/usr/bin/env bash
    status=0
    just --justfile "{{justfile()}}" _test || status=$?
    just --justfile "{{justfile()}}" dashboard
    exit $status

# The suites `test` runs. modules::build stages the translator pdbs
# javascript::test's generate step needs (see `build` ordering note, which is also why protocol, the
# extensions and the modules come before truffle::test).
_test: bootstrap::test modules::protocol::build extensions::build-tests modules::build modules::translation_java::build-tests truffle::test javascript::test java::test-all

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
test-all: (doctor "test-all")
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
    branch_truffle()    { j truffle::spec truffle::spec-compiler-selfhost truffle::native::test-all && j --no-deps truffle::usage-example && j --no-deps extensions::pure::tds::test-all; }
    branch_javascript() { j javascript::test-all modules::translation_javascript::test-all; }
    branch_modules()    { j --no-deps modules::language_java::test-all modules::language_javascript::test-all modules::translation_shared::test-all; }
    branch_java()       { j java::test-all; }
    branches="bootstrap truffle javascript modules java"
    rm -f "$logs"/*.status "$logs"/*.result "$logs/.done"
    # On a terminal, a live block redrawn every second: one line per lane, with the step it is on and a
    # bar for that step's (n/total) counter. Otherwise (CI, redirected output) one line every 30s, and
    # each lane's ✓/✗ as it finishes.
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
        echo "$line" > "$logs/$name.result"
        $live || printf '  %-11s %s\n' "$name" "$line"
        echo "$status" > "$logs/$name.status"
    }
    # A lane's state from its log: the last step header ([module::recipe]), then that step's last
    # progress counter ("37%  (59/155)"), as "step<TAB>n<TAB>total".
    lane_state() {
        LC_ALL=C tr '\r' '\n' < "$logs/$1.log" 2>/dev/null | LC_ALL=C awk '
            match($0, /\[[A-Za-z0-9_-]+(::[A-Za-z0-9_-]+)+\]/) { step = substr($0, RSTART + 1, RLENGTH - 2); n = ""; m = "" }
            match($0, /[0-9]+%  \([0-9]+\/[0-9]+\)/) { s = substr($0, RSTART, RLENGTH); sub(/.*\(/, "", s); sub(/\)/, "", s); split(s, a, "/"); n = a[1]; m = a[2] }
            END { printf "%s\t%s\t%s\n", step, n, m }'
    }
    lane_line() {
        local name=$1 tick=$2 step n m width=30 filled bar
        if [ -f "$logs/$name.result" ]; then
            printf '  %-11s %s' "$name" "$(cat "$logs/$name.result")"
            return
        fi
        IFS=$'\t' read -r step n m <<< "$(lane_state "$name")"
        step=${step#"$name"::}
        if [ -n "$m" ] && [ "$m" -gt 0 ]; then
            filled=$(( n * width / m ))
            bar=$(printf '%*s' "$filled" '' | sed 's/ /█/g')$(printf '%*s' $(( width - filled )) '' | sed 's/ /░/g')
            printf '  %-11s %-28.28s [%s] %5s/%s' "$name" "$step" "$bar" "$n" "$m"
        else
            printf '  %-11s %-28.28s %s' "$name" "${step:-starting}" "$(echo '|/-\' | cut -c$(( tick % 4 + 1 )))"
        fi
    }
    elapsed() { local t=$(( SECONDS - started_all )); printf '%dm%02ds' $(( t / 60 )) $(( t % 60 )); }
    progress() {
        local tick=0 lines=0 b out
        while :; do
            if $live; then
                out=$(printf '  [%s]\n' "$(elapsed)"; for b in $branches; do lane_line "$b" "$tick"; echo; done)
                [ "$lines" -gt 0 ] && printf '\033[%dA' "$lines"
                printf '%s\n' "$out" | while IFS= read -r l; do printf '\033[2K%s\n' "$l"; done
                lines=$(( $(echo $branches | wc -w) + 1 ))
            elif [ $(( tick % 30 )) -eq 0 ] && [ "$tick" -gt 0 ]; then
                out=""
                for b in $branches; do
                    [ -f "$logs/$b.result" ] && continue
                    IFS=$'\t' read -r step n m <<< "$(lane_state "$b")"
                    out="$out · $b ${step#"$b"::}${m:+ $n/$m}"
                done
                [ -n "$out" ] && printf '  [%s] %s\n' "$(elapsed)" "${out# · }"
            fi
            [ -f "$logs/.done" ] && break
            sleep 1; tick=$(( tick + 1 ))
        done
    }
    echo "  running in parallel: $branches  (logs in test-results/logs/)"
    pids=""
    for b in $branches; do run "$b" & pids="$pids $!"; done
    progress & progress_pid=$!
    wait $pids
    touch "$logs/.done"
    wait "$progress_pid"
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
test-all-serial: (doctor "test-all")
    #!/usr/bin/env bash
    status=0
    just --justfile "{{justfile()}}" _test-all-serial || status=$?
    just --justfile "{{justfile()}}" dashboard
    exit $status

_test-all-serial: clean \
    (_stage-start "bootstrap") javascript::antlr-bundle bootstrap::test-all (_stage-end "bootstrap") \
    (_stage-start "modules-build") modules::protocol::build extensions::build-tests modules::build modules::translation_java::build-tests (_stage-end "modules-build") \
    (_stage-start "truffle") truffle::test-all extensions::pure::tds::test-all (_stage-end "truffle") \
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
# (bootstrap::generate-writer, truffle::generate-pdb-reader, diagram's generate,
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
