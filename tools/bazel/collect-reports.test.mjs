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

// node --test tools/bazel/collect-reports.test.mjs

import { test } from "node:test";
import assert from "node:assert/strict";
import { existsSync, mkdirSync, mkdtempSync, readFileSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { dirname, join } from "node:path";
import { collectReports, reportPath, splitJUnit } from "./collect-reports.mjs";

const write = (root, path, text) => {
    mkdirSync(dirname(join(root, path)), { recursive: true });
    writeFileSync(join(root, path), text);
};

const JUNIT = `<?xml version="1.0" encoding="UTF-8"?>
<testsuite name="JUnit Jupiter" tests="3" skipped="1" failures="0" errors="0" time="1.5">
<testcase name="a()" classname="org.x.ATest" time="0.1"/>
<testcase name="b()" classname="org.x.ATest" time="0.2"><skipped/></testcase>
<testcase name="c()" classname="org.x.BTest" time="0.3"/>
</testsuite>`;

test("a Pure report keeps its path, but for the transitional renames to just paths", () => {
    assert.equal(reportPath("spec/pct/pure@cli-truffle.json"), "spec/pct/pure@cli-truffle.json");
    assert.equal(reportPath("spec/printer-compiled/pure@cli-truffle.json"), "pure/printer-compiled/pure@cli-truffle.json");
    assert.equal(reportPath("pure/language-java/unit/pure@cli-truffle.json"), "pure/language-java/pure@cli-truffle.json");
    assert.equal(reportPath("spec/tds/unit/pure@cli-truffle.json"), "spec/tds/unit/pure@cli-truffle.json");
});

test("a launcher report is split into one surefire report per class", () => {
    const files = splitJUnit(JUNIT);
    assert.deepEqual(Object.keys(files).sort(), ["TEST-org.x.ATest.xml", "TEST-org.x.BTest.xml"]);
    assert.match(files["TEST-org.x.ATest.xml"], /<testsuite name="org.x.ATest" tests="2" skipped="1" failures="0" errors="0"/);
    assert.match(files["TEST-org.x.BTest.xml"], /<testcase name="c\(\)" classname="org.x.BTest"/);
});

test("collectReports copies the Pure reports and splits the JUnit ones below each module", () => {
    const logs = mkdtempSync(join(tmpdir(), "testlogs-"));
    const repo = mkdtempSync(join(tmpdir(), "repo-"));
    write(logs, "platforms/truffle/core_pct_jit/test.outputs/spec/pct/pure@cli-truffle.json", "{}");
    write(logs, "bootstrap/m/tests/test.outputs/junit/bootstrap/m/tests/TEST-junit-jupiter.xml", JUNIT);
    const result = collectReports(logs, repo);
    assert.equal(readFileSync(join(repo, "test-results/spec/pct/pure@cli-truffle.json"), "utf8"), "{}");
    assert.ok(existsSync(join(repo, "bootstrap/m/target/surefire-reports/TEST-org.x.ATest.xml")));
    assert.deepEqual(result, { reports: 1, classes: 2, checks: 0 });
});

test("no test logs (no test ran) is nothing to collect", () => {
    const repo = mkdtempSync(join(tmpdir(), "repo-"));
    assert.deepEqual(collectReports(join(repo, "no-testlogs"), repo), { reports: 0, classes: 0, checks: 0 });
});

test("a generated case is named by its method and display name, as surefire names it (usePhrasedTestCaseMethodName)", () => {
    const files = splitJUnit(`<testsuite name="JUnit Jupiter" tests="1">
<testcase name="testCompiledGraph(String, String)[18]" classname="org.x.CTest" time="0.05">
<system-out><![CDATA[
unique-id: [engine:junit-jupiter]/[class:org.x.CTest]/[test-template:testCompiledGraph(java.lang.String, java.lang.String)]/[test-template-invocation:#18]
display-name: class/property/defaultValue
]]></system-out>
</testcase>
</testsuite>`);
    assert.match(files["TEST-org.x.CTest.xml"], /<testcase name="testCompiledGraph\(String, String\) class\/property\/defaultValue" classname="org.x.CTest"/);
});

test("a case whose display name is its method is named by the method alone, as surefire names it", () => {
    const files = splitJUnit(`<testsuite name="JUnit Jupiter" tests="1">
<testcase name="emptyModelCompiles()" classname="org.x.DTest" time="0.05">
<system-out><![CDATA[
display-name: emptyModelCompiles()
]]></system-out>
</testcase>
</testsuite>`);
    assert.match(files["TEST-org.x.DTest.xml"], /<testcase name="emptyModelCompiles" classname="org.x.DTest"/);
});

test("a method with parameters keeps them, as surefire names it", () => {
    const files = splitJUnit(`<testsuite name="JUnit Jupiter" tests="1">
<testcase name="writes(Path)" classname="org.x.ETest" time="0.05">
<system-out><![CDATA[
display-name: writes(Path)
]]></system-out>
</testcase>
</testsuite>`);
    assert.match(files["TEST-org.x.ETest.xml"], /<testcase name="writes\(Path\)" classname="org.x.ETest"/);
});

const testXml = (label, failures, seconds) => `<?xml version="1.0" encoding="UTF-8"?>
<testsuites>
  <testsuite name="${label}" tests="1" failures="${failures}" errors="0">
    <testcase name="${label}" classname="" status="run" duration="${seconds}" time="${seconds}"></testcase>
  </testsuite>
</testsuites>`;

test("a test that ran without a report of its own is a check: one case, passed or failed as Bazel recorded it", () => {
    const logs = mkdtempSync(join(tmpdir(), "testlogs-"));
    const repo = mkdtempSync(join(tmpdir(), "repo-"));
    write(logs, "tools/bazel/ids_up_to_date/test.xml", testXml("tools/bazel/ids_up_to_date", 0, 2));
    write(logs, "pure/x/pdbs_match/test.xml", testXml("pure/x/pdbs_match", 1, 1));
    write(logs, "pure/x/pdbs_match/test.log", "exec ${PAGER:-/usr/bin/less} \"$0\" || exit 1\nExecuting tests from //pure/x:pdbs_match\n----\nPDB deep diff\n  property diffs : 3\n");
    const result = collectReports(logs, repo);
    const passed = JSON.parse(readFileSync(join(repo, "test-results/checks/tools/bazel/ids_up_to_date.json"), "utf8"));
    assert.deepEqual(passed.cases, [{ id: "//tools/bazel:ids_up_to_date", status: "passed", durationMs: 2000 }]);
    const failed = JSON.parse(readFileSync(join(repo, "test-results/checks/pure/x/pdbs_match.json"), "utf8"));
    assert.equal(failed.cases[0].status, "failed");
    assert.match(failed.cases[0].error.message, /property diffs : 3/);
    assert.doesNotMatch(failed.cases[0].error.message, /PAGER|Executing tests/);
    assert.equal(result.checks, 2);
});

test("the reports several tests write at one path are one report, with all their cases", () => {
    const logs = mkdtempSync(join(tmpdir(), "testlogs-"));
    const repo = mkdtempSync(join(tmpdir(), "repo-"));
    const report = (id) => JSON.stringify({ schema: 1, producer: "bazel", suite: "read-back", durationMs: 5, recordedAt: "2026-11-02T00:00:00Z", source: "s", cases: [{ id, status: "passed", durationMs: 5 }] });
    write(logs, "platforms/truffle/a_read_back_x/test.outputs/spec/a/read-back/pure@cli-truffle.json", report("x"));
    write(logs, "platforms/truffle/a_read_back_y/test.outputs/spec/a/read-back/pure@cli-truffle.json", report("y"));
    collectReports(logs, repo);
    const merged = JSON.parse(readFileSync(join(repo, "test-results/spec/a/read-back/pure@cli-truffle.json"), "utf8"));
    assert.deepEqual(merged.cases.map((c) => c.id).sort(), ["x", "y"]);
    assert.equal(merged.durationMs, 10);
});

test("given the live test targets, the logs of any other (a test since removed) are left out", () => {
    const logs = mkdtempSync(join(tmpdir(), "testlogs-"));
    const repo = mkdtempSync(join(tmpdir(), "repo-"));
    write(logs, "platforms/truffle/core_pct_jit/test.outputs/spec/pct/pure@cli-truffle.json", "{\"cases\":[]}");
    write(logs, "platforms/truffle/gone_unit_jit/test.outputs/pure/gone/unit/pure@cli-truffle.json", "{\"cases\":[]}");
    write(logs, "tools/bazel/gone_check/test.xml", testXml("tools/bazel/gone_check", 0, 1));
    const result = collectReports(logs, repo, new Set(["//platforms/truffle:core_pct_jit"]));
    assert.ok(existsSync(join(repo, "test-results/spec/pct/pure@cli-truffle.json")));
    assert.ok(!existsSync(join(repo, "test-results/pure/gone")));
    assert.ok(!existsSync(join(repo, "test-results/checks")));
    assert.deepEqual(result, { reports: 1, classes: 0, checks: 0 });
});

test("a collection replaces the previous one: a report it no longer finds is removed, other files are kept", () => {
    const repo = mkdtempSync(join(tmpdir(), "repo-"));
    const first = mkdtempSync(join(tmpdir(), "testlogs-"));
    write(first, "platforms/truffle/a_unit_jit/test.outputs/pure/a/unit/pure@cli-truffle.json", "{\"cases\":[]}");
    write(first, "tools/bazel/check/test.xml", testXml("tools/bazel/check", 0, 1));
    collectReports(first, repo);
    write(repo, "test-results/history.json", "[]");
    const second = mkdtempSync(join(tmpdir(), "testlogs-"));
    write(second, "platforms/truffle/b_unit_jit/test.outputs/pure/b/unit/pure@cli-truffle.json", "{\"cases\":[]}");
    collectReports(second, repo);
    assert.ok(!existsSync(join(repo, "test-results/pure/a/pure@cli-truffle.json")));
    assert.ok(!existsSync(join(repo, "test-results/checks/tools/bazel/check.json")));
    assert.ok(existsSync(join(repo, "test-results/pure/b/pure@cli-truffle.json")));
    assert.ok(existsSync(join(repo, "test-results/history.json")));
});
