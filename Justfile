# Legend Pure Next — top-level build orchestrator
#
# Usage:
#   just                     # build and test everything
#   just test                # run all tests (Java + Pure)
#   just ide                 # launch the IDE
#   just clean               # clean everything
#
# Subproject-specific recipes live in per-subproject Justfiles and are
# addressable as `just bootstrap::<recipe>`, `just truffle::<recipe>`, and
# `just typescript::<recipe>`. Run `just --list bootstrap` (etc.) to see them.

set quiet := true
# Bash needed for process substitution (filtering stderr without touching exit codes).
set shell := ["bash", "-c"]

mod bootstrap
mod truffle "platforms/truffle/Justfile"
mod typescript "platforms/typescript/Justfile"

root := justfile_directory()
out  := root / "shared"

banner := "printf '\\n══════════════════════════════════════════════════════════════════════════════════\\n  %-13s %s\\n══════════════════════════════════════════════════════════════════════════════════\\n'"

# Default: build and test everything.
default: test

# Build everything by delegating to per-subproject builds.
# Order matters: bootstrap stages core.pdb / compiler.pdb that truffle codegen reads.
# typescript has no dependency on the others and could run in parallel; sequenced last.
build: bootstrap::build truffle::build typescript::build

# Test everything: each module's `test` runs Java unit tests + Pure spec tests
# on its evaluator, and `self-host` exercises the compile-via-Pure path. Together
# these cover what the old root `test` did.
test: bootstrap::test truffle::test typescript::test

clean: bootstrap::clean truffle::clean typescript::clean
    @{{banner}} '[clean]' 'remove shared/'
    rm -rf {{out}}

ide: bootstrap::ide
