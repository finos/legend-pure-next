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

header := "h(){ s=\"[root::$1]\"; ru=$(printf '█%.0s' $(seq 1 ${#s})); printf '\\n  %s\\n  %s\\n  %s\\n' \"$ru\" \"$s\" \"$ru\"; }; h"
substep := "p(){ ru=$(printf '─%.0s' $(seq 1 $((${#1}+2)))); printf '\\n  %s\\n  ↳ %s\\n  %s\\n' \"$ru\" \"$1\" \"$ru\"; }; p"

# Build and test everything.
default: test

# Build all subprojects (bootstrap → truffle → modules → javascript). The
# javascript platform's generate recipes run the TRANSLATED translator, which
# lives in the modules tree's pdbs (javascript.pdb, translation-shared.pdb,
# javascript-translation.pdb) — so modules must build first.
build: bootstrap::build truffle::build extensions::build-tests modules::build javascript::build

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
_test: bootstrap::test truffle::test extensions::build-tests modules::build javascript::test java::test-all

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

# `clean`, not `clean-generated`: a full run starts from nothing, so every
# artifact under test is built by this run. clean-generated only removed the
# generated/ trees, leaving each module's target/ and the staged shared/ PDBs
# from whatever built them last — and it deletes generated/ directories that are
# build INPUTS (Truffle's translated PDB reader, compiler-pure's generated
# writer), so it took things away without putting the rest back.
# Order is load-bearing, and `clean` is what makes it so. The platforms' builds
# consume the modules' PDBs: truffle::generate-pdb-reader translates its PDB
# reader with the JAVA translator, so java.pdb, translation-shared.pdb and
# java-translation{,-tests}.pdb must exist before truffle::test-all — and
# modules::test-all runs LAST. While this recipe only ran clean-generated those
# PDBs survived from an earlier build and the ordering never showed; a real clean
# deletes them, and truffle then fails with NoSuchFileException: java.pdb.
#
# modules::build alone is not enough: it builds translation_java::build, not
# build-tests, and the reader generation needs the tests PDB as well (its entry
# points are <<test.TestDependency>>).
_test-all: clean javascript::antlr-bundle bootstrap::test-all modules::build modules::translation_java::build-tests truffle::test-all javascript::test-all modules::test-all

# Render every translator gallery (java + javascript + truffle).
gallery: modules::gallery

# Render every test report under test-results/ into test-results/dashboard.html (tools/test-dashboard).
dashboard:
    @{{header}} 'dashboard'
    @{{substep}} 'render test-results/ -> test-results/dashboard.html'
    node {{root}}/tools/test-dashboard/build.mjs

# Remove shared/ and per-subproject build artifacts.
clean: bootstrap::clean truffle::clean javascript::clean java::clean modules::clean
    @{{header}} 'clean'
    @{{substep}} 'remove shared/'
    rm -rf {{out}}

# Launch the bootstrap-backed Pure IDE.
ide: bootstrap::ide
