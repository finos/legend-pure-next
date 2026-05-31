# Legend Pure Next — top-level orchestrator.
#
# Quickstart:
#   just                    # build + test everything
#   just build              # build everything (no tests)
#   just test               # run all tests
#   just ide                # launch the IDE
#   just clean              # delete shared/ and per-subproject build artifacts
#
# Per-subproject recipes are addressable as `just bootstrap::<recipe>`,
# `just truffle::<recipe>`, `just typescript::<recipe>`, `just modules::<recipe>`.
# Use `just --list bootstrap` (etc.) to see what each subproject offers.

set quiet := true
# Bash needed for process substitution (filtering stderr without touching exit codes).
set shell := ["bash", "-c"]

mod bootstrap
mod truffle "platforms/truffle/Justfile"
mod typescript "platforms/typescript/Justfile"
mod modules "pure/modules/Justfile"

root := justfile_directory()
out  := root / "shared"

header := "h(){ s=\"[root::$1]\"; ru=$(printf '█%.0s' $(seq 1 ${#s})); printf '\\n  %s\\n  %s\\n  %s\\n' \"$ru\" \"$s\" \"$ru\"; }; h"
substep := "p(){ ru=$(printf '─%.0s' $(seq 1 $((${#1}+2)))); printf '\\n  %s\\n  ↳ %s\\n  %s\\n' \"$ru\" \"$1\" \"$ru\"; }; p"

# Build and test everything.
default: test

# Build all subprojects (bootstrap → truffle → typescript → modules).
build: bootstrap::build truffle::build typescript::build modules::build

# Top-level `build` already chains every subproject's `build` in dep order — alias for symmetry with sub-Justfiles' `build-all`.
build-all: build

# Run all tests across subprojects.
test: bootstrap::test truffle::test typescript::test

# Includes Pure-on-Java/Truffle self-host parity and standalone runtime test
# suites on top of each subproject's `test`.
# Run `test-all` across subprojects.
test-all: bootstrap::test-all truffle::test-all typescript::test-all modules::test-all

# Render every translator gallery (java + typescript + truffle).
gallery: modules::gallery

# Remove shared/ and per-subproject build artifacts.
clean: bootstrap::clean truffle::clean typescript::clean modules::clean
    @{{header}} 'clean'
    @{{substep}} 'remove shared/'
    rm -rf {{out}}

# Launch the bootstrap-backed Pure IDE.
ide: bootstrap::ide
