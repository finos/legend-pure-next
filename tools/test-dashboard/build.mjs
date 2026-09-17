#!/usr/bin/env node
// tools/test-dashboard/build.mjs - renders every test report under test-results/ into one
// self-contained page, test-results/dashboard.html (see README.md).
//
// Reports are one JSON file per suite run at test-results/<kind>/<group>/<run>.json, written by
// the Pure runners (pure/specification/runtime/test/testReport.pure):
//   { "schema": 1, "suite": "pct", "durationMs": 754,
//     "cases": [ { "id": "...", "status": "passed" | "failed" | "skipped", "durationMs": 15,
//                  "error": { "message": "...", "stack": ["..."] } } ] }
//
// Each build also appends a summary snapshot to test-results/history.json when the results
// changed since the previous one; the Trends view charts those snapshots.
//
// JUnit results are imported first, from the XML Maven's surefire plugin writes (surefire.mjs).
//
// Usage: tools/test-dashboard/build.mjs [--results <dir>] [--out <file>] [--no-surefire] [--published]   (or: just dashboard)
//   --published: the page sits next to the galleries on the published site (java.html, javascript.html, truffle.html).

import { createHash } from "node:crypto";
import { execFileSync } from "node:child_process";
import { existsSync, mkdirSync, readFileSync, readdirSync, statSync, writeFileSync } from "node:fs";
import { dirname, join, relative, resolve, sep } from "node:path";
import { fileURLToPath } from "node:url";
import { importSurefire } from "./surefire.mjs";

const HISTORY_LIMIT = 60;
const PLACEHOLDER = "__DASHBOARD_DATA__";
const toolDir = dirname(fileURLToPath(import.meta.url));
const repoRoot = resolve(toolDir, "..", "..");

function option(name, fallback) {
  const i = process.argv.indexOf(name);
  return i >= 0 && process.argv[i + 1] ? resolve(process.argv[i + 1]) : fallback;
}
const resultsDir = option("--results", join(repoRoot, "test-results"));
const outFile = option("--out", join(resultsDir, "dashboard.html"));
const historyFile = join(resultsDir, "history.json");
// The translation galleries a PCT run links to: [run, gallery built in the repository, its name on the published site].
const GALLERIES = [
  ["java-translation@cli-truffle", "pure/modules/translation/java/build/java-translation-gallery.html", "java.html"],
  ["javascript-translation@cli-javascript", "pure/modules/translation/javascript/build/javascript-translation-gallery.html", "javascript.html"],
  ["pure@cli-truffle", "pure/modules/translation/truffle/build/truffle-translation-gallery.html", "truffle.html"]
];
// Heavy build steps recorded by build-step.mjs.
const buildsFile = join(resultsDir, "builds.json");

function walk(dir) {
  return readdirSync(dir, { withFileTypes: true }).flatMap(e => e.isDirectory() ? walk(join(dir, e.name)) : [join(dir, e.name)]);
}

// Reports sit at least one directory deep (<kind>/.../<run>.json); top-level files such as
// history.json are not reports.
function readReports() {
  if (!existsSync(resultsDir)) return [];
  return walk(resultsDir)
    .filter(file => file.endsWith(".json") && relative(resultsDir, file).includes(sep))
    .sort()
    .flatMap(file => {
      const path = relative(resultsDir, file).split(sep).join("/").replace(/\.json$/, "");
      try {
        const report = JSON.parse(readFileSync(file, "utf8"));
        if (report.schema !== 1 || !Array.isArray(report.cases)) throw new Error("expected schema 1 with a cases array");
        const recordedAt = report.recordedAt ?? statSync(file).mtime.toISOString();
        return [{ path, suite: report.suite ?? "", durationMs: report.durationMs ?? 0, recordedAt, cases: report.cases }];
      } catch (e) {
        console.warn(`skipped ${relative(process.cwd(), file)}: ${e.message}`);
        return [];
      }
    });
}

function git(...args) {
  try {
    return execFileSync("git", args, { cwd: repoRoot, encoding: "utf8", stdio: ["ignore", "pipe", "ignore"] }).trim();
  } catch {
    return null;
  }
}

const isFailed = c => c.status !== "passed" && c.status !== "skipped";

function snapshotOf(reports, meta) {
  const kinds = {};
  // Per report, so the Trends view can chart each run's execution time.
  const runs = {};
  for (const r of reports) {
    runs[r.path] = { tests: r.cases.length, failed: r.cases.filter(isFailed).length, durationMs: r.durationMs };
    const k = kinds[r.path.split("/")[0]] ??= { tests: 0, failed: 0, skipped: 0, durationMs: 0 };
    k.tests += r.cases.length;
    k.failed += r.cases.filter(isFailed).length;
    k.skipped += r.cases.filter(c => c.status === "skipped").length;
    k.durationMs += r.durationMs;
  }
  // Durations are part of the fingerprint: re-rendering the same reports adds nothing, re-running the tests does.
  const fingerprint = createHash("sha1").update(JSON.stringify(reports.map(r => [r.path, r.durationMs, r.cases.map(c => [c.id, c.status])]))).digest("hex");
  return { at: meta.generatedAt, commit: meta.commit, dirty: meta.dirty, fingerprint, kinds, runs };
}

function updateHistory(snapshot) {
  let snapshots = [];
  try {
    snapshots = JSON.parse(readFileSync(historyFile, "utf8")).snapshots ?? [];
  } catch {
    // no history yet
  }
  const last = snapshots.at(-1);
  if (last?.fingerprint !== snapshot.fingerprint) snapshots.push(snapshot);
  // Same results, recorded before snapshots carried per-run durations: upgrade it in place.
  else if (!last.runs) snapshots[snapshots.length - 1] = snapshot;
  snapshots = snapshots.slice(-HISTORY_LIMIT);
  writeFileSync(historyFile, JSON.stringify({ schema: 1, snapshots }, null, 2) + "\n");
  return snapshots;
}

if (!process.argv.includes("--no-surefire")) {
  const imported = importSurefire(repoRoot, resultsDir);
  console.log(`imported ${imported.classes} JUnit classes from surefire, ${imported.specs.length} as specification runs`);
  for (const path of imported.kept) console.warn(`kept ${path}.json: it was written by another producer`);
}
const reports = readReports();
const meta = {
  branch: git("rev-parse", "--abbrev-ref", "HEAD"),
  commit: git("rev-parse", "--short", "HEAD"),
  dirty: (git("status", "--porcelain") ?? "") !== "",
  generatedAt: new Date().toISOString()
};
const history = reports.length ? updateHistory(snapshotOf(reports, meta)) : [];
let builds = [];
try {
  builds = JSON.parse(readFileSync(buildsFile, "utf8")).steps ?? [];
} catch {
  // no build steps recorded yet
}

const pieces = readFileSync(join(toolDir, "index.html"), "utf8").split(PLACEHOLDER);
if (pieces.length !== 2) throw new Error(`index.html must contain ${PLACEHOLDER} exactly once`);
// "<" is escaped so no report text (say, an error message quoting "</script>") can end the data block early.
// Links only to galleries that exist: next to the page when published, in the repository otherwise.
const published = process.argv.includes("--published");
const galleries = Object.fromEntries(GALLERIES.flatMap(([run, built, site]) => {
  const target = published ? join(dirname(outFile), site) : join(repoRoot, built);
  return existsSync(target) ? [[run, relative(dirname(outFile), target).split(sep).join("/")]] : [];
}));
const data = JSON.stringify({ meta, reports, history, builds, galleries }).replace(/</g, "\\u003c");
mkdirSync(dirname(outFile), { recursive: true });
writeFileSync(outFile, pieces[0] + data + pieces[1]);

const tests = reports.reduce((a, r) => a + r.cases.length, 0);
const failed = reports.reduce((a, r) => a + r.cases.filter(isFailed).length, 0);
console.log(`wrote ${relative(process.cwd(), outFile)}: ${reports.length} reports, ${tests} tests, ${failed} failed`);
