# Test dashboard

One page over every test result in the repository. `build.mjs` renders each report under
`test-results/` into `test-results/dashboard.html`, a self-contained file you can open from disk.

```bash
bazel test //...                                 # run the suites (each writes its report among its test outputs)
node tools/bazel/collect-reports.mjs             # gather them under test-results/ (and the JUnit XML, below)
node tools/test-dashboard/build.mjs              # render the dashboard
tools/test-dashboard/build.mjs --results <dir> --out <file>
```

On GitHub, every test job in `.github/workflows/build.yml` converts its JUnit XML and uploads its
`test-results/` as a `dashboard-reports-*` artifact. `publish-gallery` merges them, renders the
dashboard and publishes it to GitHub Pages as `dashboard.html`, next to the translation galleries
(on push to main only, and also when a suite failed).

History carries over between deploys through the site itself: `publish-gallery` downloads the published
`history.json` and `builds.json` (the build step times just recorded before Bazel, kept as published through
`merge-builds.mjs`), renders the dashboard (which appends this run's snapshot to `history.json`, latest 60
kept) and publishes both files next to `dashboard.html`. A first deploy, or a reset site, starts them fresh.

The PCT runs that have a translation gallery link to it under their board cell: the Java and JavaScript
translation adapters and Truffle JIT (the Truffle AST gallery). Locally the links point at the galleries
built under `pure/modules/translation/*/build/` (when they exist); `--published` points them at `java.html`,
`javascript.html` and `truffle.html` next to the page, as on GitHub Pages.

- `index.html` is the page: styles, markup and the script that draws the views.
- `build.mjs` collects the reports, inlines them into the page and keeps `test-results/history.json`.
- `merge-builds.mjs` merges `builds.json` files (see Build time below).
- `surefire.mjs`, called by `build.mjs`, imports JUnit results from `<module>/target/surefire-reports/TEST-*.xml`,
  where `tools/bazel/collect-reports.mjs` writes those of the Bazel JUnit tests, as surefire did (skip it with
  `--no-surefire`).

## Reports

A test run writes one JSON file at `test-results/<kind>/<group>/<run>.json`:

```json
{ "schema": 1, "suite": "pct", "durationMs": 754,
  "cases": [ { "id": "platform::pure::functions::collection::tests::sort::testSimpleSort_Function_1__Boolean_1_",
               "status": "passed", "durationMs": 15 },
             { "id": "...", "status": "failed", "durationMs": 3,
               "error": { "message": "Assert failure ...", "stack": ["..."] } } ] }
```

| Kind | Group | Run | Written by |
|---|---|---|---|
| `spec` | the specification: `grammar`, `compiler`, `functions`, `pct`, `pdb` (archives against the Java goldens), `pdb-format` (the PDB format's `<<test.Test>>` suite) | `<implementation>@<host>[+<source>]`, e.g. `pure@cli-truffle+inMemory`; the host names the CLI that runs it (`cli-bootstrap`, `cli-truffle`, `cli-truffle-native`, `cli-javascript`), and the Java reference is plain `java` | the Pure runners, through `writeSuiteReport` in `pure/specification/runtime/test/testReport.pure` |
| `pure` | Pure code checked on each host: a library (`printer-compiled`, `printer-protocol`) or a module's `<<test.Test>>` suite (`language-java`, `language-javascript`, `translation-shared`, `translation-java`, `translation-java-functions` for the runtime functions with the Java extension loaded, `translation-javascript`) | `pure@<host>` | the printer corpus runners; each module's `runTests` with a report path |
| `java` | the Maven module | the test class | JUnit |
| `javascript` | the source area | the script | Node test scripts |

Test ids are element paths for Pure tests and corpus-relative paths for fixtures, so the same test
lines up across hosts in the specification matrix.

Optional report fields: `recordedAt` (ISO time the results were produced; defaults to the file's
modification time), `source` (where the results came from) and `producer` (the tool that wrote the
file).

## JUnit results

`surefire.mjs` writes one `java/<maven-module>/<class>.json` per test class, and turns the classes that
run a specification corpus into specification runs:

| JUnit class | Specification run |
|---|---|
| `PureToJsonRoundtripTest` | `spec/grammar/java` |
| `PureParserMatchesJavaParserTest` (its `grammar:` cases) | `spec/grammar/pure@junit-bootstrap` |
| `CompilerCompiledGraphTest` + `CompilerErrorTest` | `spec/compiler/java` |
| `CompilerCompiledGraphPdbRoundTripTest` | `spec/pdb/java` |

`collect-reports.mjs` names each case as surefire's `usePhrasedTestCaseMethodName` did, so a parameterized or
dynamic test is recorded under its display name: the corpus path or Pure element path, the same id the Pure
runners report. Only those generated
tests go into a specification run; plain `@Test` methods stay in the class report.

Its reports carry `"producer": "surefire"`. Each import deletes the previous import's reports first and
never overwrites a report another producer wrote.

## What the page does with them

- **Board** has three grids with the same technology columns: platform conformance (`spec` runs), Pure code on each host
  (the printer libraries: `pure` runs, which apply to the plain Pure hosts and the Java translation) and Modules on each host (each
  module's `pure` report, with the host columns some module reports on, plus the Java translation, where modules should be tested too). It groups runs by technology (Bootstrap, Java,
  Truffle, JavaScript). The column list lives in `TECHS` in `index.html`; a run it doesn't list still gets a column, under
  the technology its host implies.
  A column can list the specifications it applies to (a self-hosted compiler run only runs the compiler
  corpus, a translation adapter only PCT); the grid shows the rest as "n/a" unless a report exists anyway.
  Each cell shows the result, passed over run, and how long the run took.
- **Failures** are grouped by cause: the first line of the error message with element paths,
  resource locations, quoted values and numbers blanked out.
- **Trends** start with **Build time** (the steps just recorded, before Bazel): one stacked bar per `just` command that ran heavy build steps, then a table giving each step
  a sparkline, its latest and median duration, and the latest against the median of earlier runs (coloured beyond 10% and 200 ms).
  Below, the kind cards and **Test execution time** chart `history.json`, one snapshot per build whose results differ from the last.
  Each snapshot keeps per-kind totals and every report's duration, test and failure counts.

## Build time

Before Bazel, the heavy `just` recipes recorded their duration into `builds.json` (`bootstrap/maven-*`,
`bootstrap/compiler-pdb`, `truffle/maven-*`, `truffle/native-image`, `modules/pdbs`, `javascript/generate`). The
published history is kept and still charted (`STEPS` in `index.html`); nothing records new steps. Bazel reports its
own timings (`bazel test` prints each test's, and `--profile` writes the build's).
