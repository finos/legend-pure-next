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
# `just truffle::<recipe>`, `just typescript::<recipe>`. Use
# `just --list bootstrap` (etc.) to see what each subproject offers.

set quiet := true
# Bash needed for process substitution (filtering stderr without touching exit codes).
set shell := ["bash", "-c"]

mod bootstrap
mod truffle "platforms/truffle/Justfile"
mod typescript "platforms/typescript/Justfile"

root := justfile_directory()
out  := root / "shared"

banner := "printf '\\n══════════════════════════════════════════════════════════════════════════════════\\n  %-13s %s\\n══════════════════════════════════════════════════════════════════════════════════\\n'"

# Build and test everything.
default: test

# Build all subprojects (bootstrap → truffle → typescript).
build: bootstrap::build truffle::build typescript::build

# Run all tests across subprojects.
test: bootstrap::test truffle::test typescript::test

# Includes Pure-on-Java/Truffle self-host parity and standalone runtime test
# suites on top of each subproject's `test`.
# Run `test-all` across subprojects.
test-all: bootstrap::test-all truffle::test-all typescript::test-all

# Remove shared/ and per-subproject build artifacts.
clean: bootstrap::clean truffle::clean typescript::clean
    @{{banner}} '[clean]' 'remove shared/'
    rm -rf {{out}}

# Launch the bootstrap-backed Pure IDE.
ide: bootstrap::ide
