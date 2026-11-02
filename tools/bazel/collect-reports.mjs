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

// The reports of a Bazel test run, where the test dashboard (tools/test-dashboard) reads them, as just and Maven
// leave them:
//   - every Pure report a test wrote among its outputs, at test-results/<its path>;
//   - every JUnit report, which the console launcher writes one per engine, split into surefire's one per class
//     (<module>/target/surefire-reports/TEST-<class>.xml), the module being the package of the test.
//
//   node tools/bazel/collect-reports.mjs [<testlogs>] [<repository root>]
//
// <testlogs> is `bazel info bazel-testlogs` by default, the repository root the working directory.

import { execFileSync } from "node:child_process";
import { existsSync, mkdirSync, readdirSync, readFileSync, statSync, writeFileSync } from "node:fs";
import { dirname, join, relative, sep } from "node:path";
import { fileURLToPath } from "node:url";

// TRANSITIONAL: where just writes the reports whose Bazel path differs, until the dashboard reads the spec layout
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

/** A case named as surefire names it (usePhrasedTestCaseMethodName, bootstrap/legend-pure-next-bootstrap/pom.xml), from the
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

/** Copy every report below 'testlogs' into 'root'; how many Pure reports and JUnit classes it wrote. */
export function collectReports(testlogs, root) {
    // No test ran (a step skipped, or a build that failed first): nothing to collect, and nothing to fail on here.
    if (!existsSync(testlogs)) return { reports: 0, classes: 0 };
    const outputs = walk(testlogs).filter((f) => f.split(sep).includes("test.outputs"));
    const relativeToOutputs = (f) => {
        const parts = f.split(sep);
        return parts.slice(parts.indexOf("test.outputs") + 1).join("/");
    };
    const pure = outputs.filter((f) => f.endsWith(".json") && !relativeToOutputs(f).startsWith("junit/"));
    pure.forEach((f) => put(join(root, "test-results", reportPath(relativeToOutputs(f))), readFileSync(f, "utf8")));
    const junit = outputs.filter((f) => relativeToOutputs(f).startsWith("junit/") && /TEST-.*\.xml$/.test(f));
    const classes = junit.flatMap((f) => {
        // junit/<package>/<test name>/TEST-junit-jupiter.xml: the module is the package.
        const module = relativeToOutputs(f).split("/").slice(1, -2).join("/");
        return Object.entries(splitJUnit(readFileSync(f, "utf8"))).map(([name, xml]) => {
            put(join(root, module, "target", "surefire-reports", name), xml);
            return name;
        });
    });
    return { reports: pure.length, classes: classes.length };
}

if (process.argv[1] && fileURLToPath(import.meta.url) === process.argv[1]) {
    const testlogs = process.argv[2] ?? execFileSync("bazel", ["info", "bazel-testlogs"]).toString().trim();
    const root = process.argv[3] ?? process.cwd();
    const { reports, classes } = collectReports(testlogs, root);
    console.log(`  ${reports} Pure reports into ${relative(process.cwd(), join(root, "test-results")) || "test-results"}, ${classes} JUnit classes into <module>/target/surefire-reports`);
}
