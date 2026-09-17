// tools/test-dashboard/surefire.mjs - turns the JUnit results Maven's surefire plugin leaves in
// <module>/target/surefire-reports/TEST-*.xml into dashboard reports (see README.md):
//   test-results/java/<maven-module>/<class>.json   one per test class
//   test-results/spec/<spec>/<run>.json             for the classes that run a specification corpus
//
// Every report written here carries "producer": "surefire". Each import first deletes the previous
// import's reports, so a module Maven cleaned leaves nothing stale, and never overwrites a report
// another producer (a Pure runner) wrote.

import { existsSync, mkdirSync, readFileSync, readdirSync, rmSync, statSync, writeFileSync } from "node:fs";
import { basename, dirname, join, relative, sep } from "node:path";

const PRODUCER = "surefire";
const SCAN_ROOTS = ["bootstrap", "platforms"];
const SKIP_DIRS = new Set([".git", "node_modules", "src", "generated"]);

// JUnit classes that run a specification corpus. Their test names are corpus paths, the same ids the
// Pure runners report, so these runs line up with the others in the specification matrix.
const SPEC_RUNS = [
  { spec: "grammar", run: "java", classes: ["org.finos.legend.pure.next.parser.PureToJsonRoundtripTest"] },
  // The Pure grammar parser, run on bootstrap, checked against the Java parser; its grammar-corpus cases are "grammar:<fixture>".
  { spec: "grammar", run: "pure@cli-bootstrap", classes: ["org.finos.legend.pure.next.parser.mappings.PureParserMatchesJavaParserTest"], idPrefix: "grammar:" },
  { spec: "compiler", run: "java", classes: ["org.finos.legend.pure.m3.specification.CompilerCompiledGraphTest", "org.finos.legend.pure.m3.specification.CompilerErrorTest"] },
  { spec: "pdb", run: "java", classes: ["org.finos.legend.pure.m3.specification.CompilerCompiledGraphPdbRoundTripTest"] },
];

function surefireFiles(repoRoot) {
  const files = [];
  const visit = dir => {
    let entries;
    try {
      entries = readdirSync(dir, { withFileTypes: true });
    } catch {
      return;
    }
    for (const e of entries) {
      if (!e.isDirectory() || SKIP_DIRS.has(e.name)) continue;
      const path = join(dir, e.name);
      if (e.name !== "target") {
        visit(path);
        continue;
      }
      const reports = join(path, "surefire-reports");
      if (!existsSync(reports)) continue;
      for (const f of readdirSync(reports)) if (/^TEST-.+\.xml$/.test(f)) files.push({ module: basename(dir), file: join(reports, f) });
    }
  };
  for (const root of SCAN_ROOTS) visit(join(repoRoot, root));
  return files.sort((a, b) => a.file.localeCompare(b.file));
}

const ENTITIES = { lt: "<", gt: ">", amp: "&", quot: "\"", apos: "'" };
const decode = s => s.replace(/&(#x[0-9a-fA-F]+|#\d+|\w+);/g, (m, e) =>
  e[0] !== "#" ? ENTITIES[e] ?? m : String.fromCodePoint(e[1] === "x" ? parseInt(e.slice(2), 16) : parseInt(e.slice(1), 10)));
const attributes = s => Object.fromEntries([...s.matchAll(/([\w:.-]+)="([^"]*)"/g)].map(([, k, v]) => [k, decode(v)]));

// Element text: CDATA sections verbatim, everything else entity-decoded.
function textOf(s = "") {
  let out = "", last = 0;
  for (const m of s.matchAll(/<!\[CDATA\[([\s\S]*?)\]\]>/g)) {
    out += decode(s.slice(last, m.index)) + m[1];
    last = m.index + m[0].length;
  }
  return out + decode(s.slice(last));
}

// Surefire names a parameterized or dynamic test "<method>(<parameter types>) <display name>" when
// usePhrasedTestCaseMethodName is on (both parent poms), "<method>(<parameter types>)[<n>]" otherwise; a plain
// @Test method is just "<method>". Only generated tests are corpus tests.
const PHRASED = /^[\w$]+\([^)]*\) (?=\S)/;
const isGenerated = name => PHRASED.test(name) || /^[\w$]+\([^)]*\)\[\d+\]$/.test(name);

function caseOf(suiteName, attrs, inner) {
  // The display name is the id. TestPCT names the tests it skips "<path> [skipped: <reason>]".
  const name = (attrs.name ?? "").replace(PHRASED, "").replace(/ \[skipped: [^\]]*\]$/, "");
  const nested = attrs.classname && attrs.classname !== suiteName;
  const id = nested ? `${attrs.classname.slice(attrs.classname.lastIndexOf(".") + 1)}.${name}` : name;
  const durationMs = Math.round(Number(attrs.time || 0) * 1000);
  const failure = inner.match(/<(failure|error)\b([^>]*?)(?:\/>|>([\s\S]*?)<\/\1>)/);
  if (failure) {
    const fa = attributes(failure[2]);
    const lines = textOf(failure[3]).split("\n").map(l => l.trimEnd()).filter(Boolean);
    const message = fa.message || lines[0] || "Failed without a message";
    const stack = lines.filter(l => /^\s*at /.test(l)).map(l => l.trim().slice(3));
    return { id, status: "failed", durationMs, error: { message: fa.type && !message.startsWith(fa.type) ? `${fa.type}: ${message}` : message, stack } };
  }
  return { id, status: /<skipped\b/.test(inner) ? "skipped" : "passed", durationMs };
}

function readSuite({ module, file }, repoRoot) {
  const xml = readFileSync(file, "utf8");
  const suite = attributes(xml.match(/<testsuite\b([^>]*)>/)?.[1] ?? "");
  const className = suite.name || basename(file).replace(/^TEST-|\.xml$/g, "");
  const testcases = [...xml.matchAll(/<testcase\b([^>]*?)(?:\/>|>([\s\S]*?)<\/testcase>)/g)].map(m => ({ attrs: attributes(m[1]), inner: m[2] ?? "" }));
  const cases = testcases.map(t => caseOf(className, t.attrs, t.inner));
  const corpusCases = cases.filter((c, i) => isGenerated(testcases[i].attrs.name ?? ""));
  return {
    module,
    className,
    corpusCases,
    report: {
      schema: 1,
      producer: PRODUCER,
      suite: "junit",
      durationMs: Math.round(Number(suite.time || 0) * 1000),
      recordedAt: statSync(file).mtime.toISOString(),
      source: relative(repoRoot, file).split(sep).join("/"),
      cases
    }
  };
}

function jsonFiles(dir) {
  if (!existsSync(dir)) return [];
  return readdirSync(dir, { withFileTypes: true }).flatMap(e => e.isDirectory() ? jsonFiles(join(dir, e.name)) : e.name.endsWith(".json") ? [join(dir, e.name)] : []);
}

const producerOf = file => {
  try {
    return JSON.parse(readFileSync(file, "utf8")).producer;
  } catch {
    return undefined;
  }
};

export function importSurefire(repoRoot, resultsDir) {
  for (const file of jsonFiles(resultsDir)) if (producerOf(file) === PRODUCER) rmSync(file);
  const kept = [];
  const write = (path, report) => {
    const file = join(resultsDir, `${path}.json`);
    if (existsSync(file)) {
      kept.push(path);
      return;
    }
    mkdirSync(dirname(file), { recursive: true });
    writeFileSync(file, JSON.stringify(report) + "\n");
  };

  const suites = surefireFiles(repoRoot).map(f => readSuite(f, repoRoot));
  for (const s of suites) write(`java/${s.module}/${s.className.replace(/^org\.finos\.legend\.pure\./, "")}`, s.report);

  const specs = [];
  for (const { spec, run, classes, idPrefix } of SPEC_RUNS) {
    const parts = suites.filter(s => classes.includes(s.className));
    if (!parts.length) continue;
    // Corpus tests only: a class's plain @Test methods (such as a discovery check) are not fixtures.
    const corpus = parts.flatMap(s => s.corpusCases);
    const cases = idPrefix ? corpus.filter(c => c.id.startsWith(idPrefix)).map(c => ({ ...c, id: c.id.slice(idPrefix.length) })) : corpus;
    if (!cases.length) continue;
    const recordedAt = parts.map(s => s.report.recordedAt).sort().at(-1);
    write(`spec/${spec}/${run}`, { schema: 1, producer: PRODUCER, suite: "junit", durationMs: parts.reduce((a, s) => a + s.report.durationMs, 0), recordedAt, source: parts.map(s => s.report.source).join(", "), cases });
    specs.push(`spec/${spec}/${run}`);
  }
  return { classes: suites.length, specs, kept };
}
