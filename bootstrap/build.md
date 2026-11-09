# Bootstrap build

The bootstrap is built and tested by Bazel. It builds Truffle, and only what Truffle needs to be built
(the migration design, `docs/superpowers/specs/2026-11-01-bazel-migration-design.md`, Section 1). Every
package below is relative to `bootstrap/legend-pure-next-bootstrap/`.

## What it builds

| Target | What it is |
|---|---|
| `…-core/legend-pure-next-bootstrap-generators:generated_specification` | `m3.pure`, `m3.fbs` and the protocol model (`m3_protocol.pure`), generated from `pure/specification/m3.ttl` |
| `…-core/legend-pure-next-bootstrap-grammar/legend-pure-next-bootstrap-grammar-parser:grammar-parser` | the ANTLR parser (`//tools/bazel:antlr`) and the protocol model |
| `…-core/legend-pure-next-bootstrap-compiler:compiler` | the Java compiler |
| `//pure/specification:core_compile`, `:parser_mappings_compile`, `:compiler_compile` | `core.pdb` and `core-tests.pdb`, `parser-mappings.pdb`, `compiler.pdb` and `compiler-tests.pdb`: built by these tools, beside their sources |
| `…-core/legend-pure-next-bootstrap-execution:execution`, `…-runtime:runtime` | the tree-walking interpreter and its natives |
| `…-interfaces/legend-pure-next-bootstrap-cli:pure-bootstrap` | the CLI: `compile`, `compile-via-pure`, `execute` |
| `…-interfaces/legend-pure-next-bootstrap-ide:pure-ide` | the IDE server |

The PDB format and compiler-pure are compiled from these in `//pure/specification`: `pdb_compile` (`pdb.pdb`), the
generated writer and archive writer (`pdb_writer_compile`), then `compiler_compile` (`compiler.pdb` and `compiler-tests.pdb`). `//:shared` gathers the PDBs and schemas into the
`shared/` layout the tests and the language manifests name.

## Its tests

| Tests | Tier |
|---|---|
| the JUnit tests of each module (`…:tests`) | required (`bazel test //...`), CI job `bootstrap` |
| the field-ID registry checks (`//tools/bazel:test_fbs_ids_up_to_date__*`) | required, CI job `bootstrap` |
| the specification suites on the interpreter (`//bootstrap/test/java/<module directory below pure/>:test_<kind>`: `core` and `compiler` suites, the legacy layer) | `bootstrap` (manual; CI job `bootstrap-self-host`) |
| every checked module compiled by the compiler written in Pure (`compile-via-pure`, which runs `compileModule` on the interpreter), against the Java compile, or the Truffle build where the Java compiler cannot compile the module (`//bootstrap/test/pure/…:test_pdb_check`) | `selfhost` (manual; CI job `bootstrap-self-host`) |

The manual tiers run with
`bazel test $(bazel query 'attr(tags, "[\[ ]bootstrap[,\]]", //...)')` (or `selfhost`).

## Writing back to the source tree

A build never writes a source file. When a model change adds a field, `bazel run //:update_fbs_ids__m3`
writes the field IDs it was given back to `pure/specification/m3-fbs-field-ids.txt`;
`bazel run //:update_pdb_goldens__compiler` (and `__island`) rewrites the PDB goldens.
