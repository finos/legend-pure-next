# Reference PDB goldens

One `.pdb` per clean-compiling `###CompiledGraph` spec test (same relative path
as the test under `../tests`), produced by the REFERENCE IMPLEMENTATION — the
hand-written Java compiler (`pureLanguageCompiler`) serialized by the Java
writer (`GeneratedFlatBufferWriter` via `CompressedArchiveWriter`). They pin
the wire contract: any port (the Pure compiler on JVM/Truffle, the JavaScript
host, the self-hosted Pure PDB writer) must produce structurally identical
archives for the same source — and byte-identical non-element sections
(`elementIndex`, `manifest`, `functionIndex`, `reverseReferenceIndex`).
Element entries (and hence the indexes) are SORTED by path.

The reference writer is built on the OFFICIAL FlatBuffers toolchain (flatc-
generated table classes + Google's `FlatBufferBuilder` runtime) — these
goldens are therefore the anchor tying every port's from-scratch encoding
(notably the self-hosted `fbBuilder.pure`) to real FlatBuffers semantics.
For that reason, goldens must NEVER be regenerated from a port: only the
flatc-backed reference mints them, even if it retires from production
writing paths.

Consumers:
- `pure/specification/compiler/compiler-pure/test/pdbGoldenRunner.pure`
  (`runPdbGoldenTests`: compiles each fixture with the Pure compiler, writes it with
  the Pure writer and compares the archive with its golden, entirely in Pure — the
  golden's deflated entries are opened by `pdb/reader/inflate.pure`. Run by the
  `spec-pdb-golden` recipes on cli-truffle, cli-truffle-native and cli-javascript —
  not on bootstrap, whose interpreter takes minutes per large archive; reported as
  the dashboard's PDB row)
- `platforms/javascript/src/compiler/tests/compiler-tests.js --golden`
  (structural diff of JS-written archives vs these; `PDB_GOLDENS_DIR` overrides)
- `platforms/javascript/src/pdb/tests/pure-reader-parity.mjs`
  (translated Pure reader vs the hand-written JS reader over every entry)

Regenerate (after any deliberate wire-contract change in the reference):

    cd bootstrap/legend-pure-next-bootstrap/legend-pure-next-bootstrap-core/legend-pure-next-bootstrap-compiler
    mvn -o test -Dtest=PdbGoldenGeneratorTest \
        -Dpdb.goldens.dir=<repo>/pure/specification/compiler/tests-pdb-serialization
