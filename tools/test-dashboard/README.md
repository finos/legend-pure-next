# Test dashboard

One page over every test result in the repository. `just dashboard` renders each report under
`test-results/` into `test-results/dashboard.html`, a self-contained file you can open from disk.

```bash
just dashboard                                   # from the repository root
just test / just test-all                        # run the suites, then render the dashboard (even when a suite fails)
tools/test-dashboard/build.mjs                   # the same, from anywhere
tools/test-dashboard/build.mjs --results <dir> --out <file>
```

On GitHub, every test job in `.github/workflows/build.yml` converts its JUnit XML and uploads its
`test-results/` as a `dashboard-reports-*` artifact. `publish-gallery` merges them, renders the
dashboard and publishes it to GitHub Pages as `dashboard.html`, next to the translation galleries
(on push to main only, and also when a suite failed).

History carries over between deploys through the site itself: `publish-gallery` downloads the published
`history.json` and `builds.json`, merges every job's build steps into the latter (`merge-builds.mjs`: duplicates
dropped, latest 2000 kept), renders the dashboard (which appends this run's snapshot to `history.json`, latest 60
kept) and publishes both files next to `dashboard.html`. A first deploy, or a reset site, starts them fresh.

The PCT runs that have a translation gallery link to it under their board cell: the Java and JavaScript
translation adapters and Truffle JIT (the Truffle AST gallery). Locally the links point at the galleries
built under `pure/modules/translation/*/build/` (when they exist); `--published` points them at `java.html`,
`javascript.html` and `truffle.html` next to the page, as on GitHub Pages.

- `index.html` is the page: styles, markup and the script that draws the views.
- `build.mjs` collects the reports, inlines them into the page and keeps `test-results/history.json`.
- `build-step.mjs` records how long heavy build steps take, into `test-results/builds.json` (see Build time below);
  `merge-builds.mjs` merges several such files, as CI does.
- `surefire.mjs`, called by `build.mjs`, imports the JUnit results Maven leaves in
  `<module>/target/surefire-reports/TEST-*.xml` (skip it with `--no-surefire`).

## Reports

A test run writes one JSON file at `test-results/<kind>/<group>/<run>.json`:

```json
{ "schema": 1, "suite": "pct", "durationMs": 754,
  "cases": [ { "id": "meta::pure::functions::collection::tests::sort::testSimpleSort_Function_1__Boolean_1_",
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
| `PureParserMatchesJavaParserTest` (its `grammar:` cases) | `spec/grammar/pure@cli-bootstrap` |
| `CompilerCompiledGraphTest` + `CompilerErrorTest` | `spec/compiler/java` |
| `CompilerCompiledGraphPdbRoundTripTest` | `spec/pdb/java` |

Both parent poms (`bootstrap/legend-pure-next-bootstrap/pom.xml`, `platforms/truffle/legend-pure-next-truffle/pom.xml`)
turn on surefire's `usePhrasedTestCaseMethodName`, so a parameterized or dynamic test is recorded under its
display name: the corpus path or Pure element path, the same id the Pure runners report. Only those generated
tests go into a specification run; plain `@Test` methods stay in the class report.

Its reports carry `"producer": "surefire"`. Each import deletes the previous import's reports first and
never overwrites a report another producer wrote. Surefire keeps a module's XML until the module is
cleaned, so a module that wasn't rebuilt shows as an older run on the page.

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
- **Trends** start with **Build time**: one stacked bar per `just` command that ran heavy build steps, then a table giving each step
  a sparkline, its latest and median duration, and the latest against the median of earlier runs (coloured beyond 10% and 200 ms).
  Below, the kind cards and **Test execution time** chart `history.json`, one snapshot per build whose results differ from the last.
  Each snapshot keeps per-kind totals and every report's duration, test and failure counts.

## Build time

Heavy recipes record their duration with `build-step.mjs`. The recipe's first dependency stamps the start and its last line
appends the step to `test-results/builds.json` (the last 2000 steps):

```just
_mvn mode: (_step-start "bootstrap/maven-" + mode)
    cd {{boot}} && mvn clean install ...
    {{build_step}} end "bootstrap/maven-{{mode}}"
```

A step that fails stops the recipe before its last line, so only completed steps are recorded. Each step keeps the
`just` command that ran it (the outermost `just` process), which is how the chart stacks one command's steps.

| Step | Recipe |
|---|---|
| `bootstrap/maven-skip-tests`, `bootstrap/maven-with-tests` | `bootstrap::_mvn` (Java + core.pdb, with or without unit tests) |
| `bootstrap/compiler-pdb` | `bootstrap::build-compiler-pdb` |
| `truffle/maven-skip-tests`, `truffle/maven-with-tests` | `truffle::_mvn` |
| `truffle/native-image` | `truffle::native::build` |
| `modules/pdbs` | `modules::build` |
| `javascript/generate` | `javascript::generate-all` (usually quick when its emission stamps are current) |

To time another step, add `_step-start` and the `end` line to its recipe and give it a label in `STEPS` in `index.html`.
