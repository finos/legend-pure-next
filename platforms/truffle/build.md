# Truffle build

Truffle is built and tested by Bazel, from what the bootstrap builds (`bootstrap/build.md`). Every package
below is relative to `platforms/truffle/legend-pure-next-platform-truffle/`.

## What it builds

| Target | What it is |
|---|---|
| `…-pure:pdb_reader` | the PDB reader, translated from compiler-pure's |
| `…-pure:truffle-pure` | the Truffle language: the interpreter, PDB loading, the natives |
| `…-extensions/…-extensions-{diagram,tds,relation}:extension` | the extensions' grammars and natives |
| `…-interfaces/legend-pure-next-platform-truffle-cli:pure-truffle` | the CLI, on the JVM: `compile`, `execute`; Bazel builds every module and extension PDB with it |
| `…-interfaces/legend-pure-next-platform-truffle-cli:pure-truffle-native` | the same CLI as a native image (GraalVM `native-image`) |
| `…-interfaces/legend-pure-next-platform-truffle-ide:pure-ide-truffle` | the IDE server on Truffle |
| `//platforms/truffle:gallery` | the PCT gallery |

## Its tests

| Tests | Tier |
|---|---|
| its JUnit tests (`…-pure:test_junit`), the usage example (`…-usage-example:test_usage_example`) | required, CI job `truffle` |
| every owner's suites on the JIT (`//platforms/truffle:<owner>_<suite>_jit`) | required, CI jobs `truffle`, `truffle-specifications` and others (`tools/bazel/hosts.bzl`: `job_of`) |
| the same on the native image (`…_native`) | required, CI job `truffle-native` |
| compiler-pure compiled by Truffle, JIT and native, against the bootstrap compile (`…_selfhost`, `…_native_selfhost`) | `selfhost` (manual; CI job `truffle-native`) |

What a suite loads is derived from the module manifests (`module.json`), and a known gap is a `PENDING` entry in
`tools/bazel/hosts.bzl`, whose test fails once the suite passes.
