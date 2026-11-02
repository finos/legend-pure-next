#!/usr/bin/env node
// ©2026 JP Morgan Chase & Co. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

// The reports of a Bazel test run, where the test dashboard (tools/test-dashboard) reads them:
//   - every Pure report a test wrote among its outputs, at test-results/<its path>: the reports several tests write
//     at one path (each host's PDB read-backs, one test per fixture) are one report, with all their cases;
//   - every JUnit report, which the console launcher writes one per engine, split into one per class, in the surefire layout
//     (<module>/target/surefire-reports/TEST-<class>.xml), the module being the package of the test;
//   - for a test that ran without a report of its own (a PDB comparison, a registry check, a usage example), a
//     check: test-results/checks/<package>/<name>.json, one case passed or failed as Bazel recorded it.
// A collection replaces the previous one: the files it wrote (test-results/.collected.json) are removed first, so a
// report of a test since removed or renamed does not stay; anything else there (history.json) is kept.
//
//   node tools/bazel/collect-reports.mjs [<testlogs>] [<repository root>] [--targets <file>]
//
// <testlogs> is `bazel info bazel-testlogs` by default, the repository root the working directory. With --targets,
// a file of test labels (`bazel query 'tests(//...)'`), only those are collected: Bazel keeps the logs of a test
// it no longer has.

import { execFileSync } from "node:child_process";
import { existsSync, mkdirSync, readdirSync, readFileSync, rmSync, statSync, writeFileSync } from "node:fs";
import { dirname, join, relative, sep } from "node:path";
import { fileURLToPath } from "node:url";

// TRANSITIONAL: where the dashboard finds the reports whose Bazel path differs, until it reads the spec layout
// (test-results/<owner>/<suite>/<host>.json, docs/superpowers/specs/2026-11-01-bazel-migration-design.md 2.6).
const RENAMES = [
    [/^spec\/(printer-compiled|printer-protocol)\//, "pure/$1/"],
    [/^(pure\/[^/]+)\/unit\//, "$1/"],
];

/** Where the dashboard reads a Pure report Bazel wrote at 'path', relative to its test outputs. */
export function reportPath(path) {
    return RENAMES.reduce((p, [from, to]) => p.replace(from, to), path);
}

const attr = (text, name) => (text.match(new RegExp(`\\b${name}="([^"]*)"`)) ?? [])[1];

const escapeAttr = (s) => s.replace(/&/g, "&amp;").replace(/"/g, "&quot;").replace(/</g, "&lt;");

/** A case named as surefire names it (usePhrasedTestCaseMethodName), from the
 * display name the launcher records in its output: the method alone when the display name is the method's own
 * ('name' for 'name()', 'name(Path)' as it is), else the method signature, a space and the display name (the corpus
 * path of a corpus test: 'testCompiledGraph(String, String) association/profile/profile'). */
function phrased(testcase) {
    const display = (testcase.match(/^display-name: (.*)$/m) ?? [])[1]?.trim();
    if (display === undefined) return testcase;
    const signature = (attr(testcase, "name") ?? "").replace(/\[\d+\]$/, "");
    const name = display === signature ? signature.replace(/\(\)$/, "") : `${signature} ${display}`;
    return testcase.replace(/(<testcase\b[^>]*?\bname=")[^"]*(")/, (_, a, b) => a + escapeAttr(name) + b);
}

/** A launcher report (one <testsuite> of every class) as surefire writes it: {file name: XML}, one per class. */
export function splitJUnit(xml) {
    const cases = [...xml.matchAll(/<testcase\b[^>]*?(?:\/>|>[\s\S]*?<\/testcase>)/g)].map((m) => phrased(m[0]));
    const byClass = cases.reduce((acc, c) => {
        const cls = attr(c, "classname") ?? "unknown";
        return { ...acc, [cls]: [...(acc[cls] ?? []), c] };
    }, {});
    return Object.fromEntries(Object.entries(byClass).map(([cls, list]) => {
        const count = (re) => list.filter((c) => re.test(c)).length;
        const time = list.reduce((sum, c) => sum + Number(attr(c, "time") ?? 0), 0);
        const suite = `<testsuite name="${cls}" tests="${list.length}" skipped="${count(/<skipped/)}" failures="${count(/<failure/)}" errors="${count(/<error/)}" time="${time.toFixed(3)}">`;
        return [`TEST-${cls}.xml`, ['<?xml version="1.0" encoding="UTF-8"?>', suite, ...list, "</testsuite>", ""].join("\n")];
    }));
}

const walk = (dir) => readdirSync(dir).flatMap((name) => {
    const path = join(dir, name);
    return statSync(path).isDirectory() ? walk(path) : [path];
});

const put = (path, text) => {
    mkdirSync(dirname(path), { recursive: true });
    writeFileSync(path, text);
};

/** The directory of the test a file below 'testlogs' belongs to: '<package>/<name>'. */
const testDirOf = (f) => {
    const parts = f.split(sep);
    const outputs = parts.indexOf("test.outputs");
    return (outputs >= 0 ? parts.slice(0, outputs) : parts.slice(0, -1)).join(sep);
};

/** The label of a test, from its directory below 'testlogs'. */
const labelOf = (testlogs, dir) => {
    const parts = relative(testlogs, dir).split(sep);
    return `//${parts.slice(0, -1).join("/")}:${parts.at(-1)}`;
};

/** What a failed check said: the end of its log, without the header Bazel writes. */
const logTail = (dir) => {
    const log = join(dir, "test.log");
    if (!existsSync(log)) return "Failed without a log";
    const lines = readFileSync(log, "utf8").replace(/\x1b\[[0-9;]*[A-Za-z]/g, "").split("\n")
        .filter((l) => l.trim() !== "" && !/^exec \$\{PAGER|^Executing tests from |^-{4,}$/.test(l));
    return lines.slice(-20).join("\n") || "Failed without a message";
};

/** The report of a check: one case, the test, as its test.xml records it. */
const checkReport = (testlogs, dir) => {
    const xml = readFileSync(join(dir, "test.xml"), "utf8");
    const label = labelOf(testlogs, dir);
    const failed = Number(attr(xml, "failures") ?? 0) + Number(attr(xml, "errors") ?? 0) > 0;
    const durationMs = Math.round(Number(attr(xml, "time") ?? 0) * 1000);
    const testCase = failed
        ? { id: label, status: "failed", durationMs, error: { message: logTail(dir), stack: [] } }
        : { id: label, status: "passed", durationMs };
    return { schema: 1, producer: "bazel", suite: "check", durationMs, recordedAt: statSync(join(dir, "test.xml")).mtime.toISOString(), source: label, cases: [testCase] };
};

/** Several reports at one path as one: every case, the durations added, the latest time. */
const merged = (texts) => {
    if (texts.length === 1) return texts[0];
    const reports = texts.map((text) => JSON.parse(text));
    return JSON.stringify({
        ...reports[0],
        durationMs: reports.reduce((sum, r) => sum + (r.durationMs ?? 0), 0),
        recordedAt: reports.map((r) => r.recordedAt ?? "").sort().at(-1),
        cases: reports.flatMap((r) => r.cases ?? []),
    });
};

/** Copy every report below 'testlogs' into 'root' (of the tests in 'live', a set of labels, when given), in place of
 * what the previous collection wrote; how many Pure reports, JUnit classes and checks it wrote. */
export function collectReports(testlogs, root, live) {
    const manifest = join(root, "test-results", ".collected.json");
    if (existsSync(manifest)) JSON.parse(readFileSync(manifest, "utf8")).forEach((f) => rmSync(join(root, f), { force: true }));
    const written = [];
    const record = (path, text) => {
        put(join(root, path), text);
        written.push(path);
    };
    const result = collectInto(testlogs, root, live, record);
    put(manifest, JSON.stringify(written));
    return result;
}

function collectInto(testlogs, root, live, record) {
    // No test ran (a step skipped, or a build that failed first): nothing to collect, and nothing to fail on here.
    if (!existsSync(testlogs)) return { reports: 0, classes: 0, checks: 0 };
    const files = walk(testlogs).filter((f) => !live || live.has(labelOf(testlogs, testDirOf(f))));
    const outputs = files.filter((f) => f.split(sep).includes("test.outputs"));
    const relativeToOutputs = (f) => {
        const parts = f.split(sep);
        return parts.slice(parts.indexOf("test.outputs") + 1).join("/");
    };
    const pure = outputs.filter((f) => f.endsWith(".json") && !relativeToOutputs(f).startsWith("junit/"));
    const byPath = pure.reduce((acc, f) => {
        const path = reportPath(relativeToOutputs(f));
        return { ...acc, [path]: [...(acc[path] ?? []), readFileSync(f, "utf8")] };
    }, {});
    Object.entries(byPath).forEach(([path, texts]) => record(join("test-results", path), merged(texts)));
    const junit = outputs.filter((f) => relativeToOutputs(f).startsWith("junit/") && /TEST-.*\.xml$/.test(f));
    const classes = junit.flatMap((f) => {
        // junit/<package>/<test name>/TEST-junit-jupiter.xml: the module is the package.
        const module = relativeToOutputs(f).split("/").slice(1, -2).join("/");
        return Object.entries(splitJUnit(readFileSync(f, "utf8"))).map(([name, xml]) => {
            record(join(module, "target", "surefire-reports", name), xml);
            return name;
        });
    });
    const reported = new Set([...pure, ...junit].map(testDirOf));
    const checks = files.filter((f) => f.endsWith(`${sep}test.xml`)).map(testDirOf).filter((dir) => !reported.has(dir));
    checks.forEach((dir) => record(join("test-results", "checks", `${relative(testlogs, dir).split(sep).join("/")}.json`),
        JSON.stringify(checkReport(testlogs, dir))));
    return { reports: Object.keys(byPath).length, classes: classes.length, checks: checks.length };
}

if (process.argv[1] && fileURLToPath(import.meta.url) === process.argv[1]) {
    const targets = process.argv.indexOf("--targets");
    const live = targets >= 0 ? new Set(readFileSync(process.argv[targets + 1], "utf8").split("\n").map((l) => l.trim()).filter((l) => l)) : undefined;
    const args = process.argv.slice(2).filter((_, i, all) => all[i] !== "--targets" && all[i - 1] !== "--targets");
    const testlogs = args[0] ?? execFileSync("bazel", ["info", "bazel-testlogs"]).toString().trim();
    const root = args[1] ?? process.cwd();
    const { reports, classes, checks } = collectReports(testlogs, root, live);
    console.log(`  ${reports} Pure reports and ${checks} checks into ${relative(process.cwd(), join(root, "test-results")) || "test-results"}, ${classes} JUnit classes into <module>/target/surefire-reports`);
}
