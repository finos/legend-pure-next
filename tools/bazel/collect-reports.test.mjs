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
    assert.deepEqual(result, { reports: 1, classes: 2 });
});

test("no test logs (no test ran) is nothing to collect", () => {
    const repo = mkdtempSync(join(tmpdir(), "repo-"));
    assert.deepEqual(collectReports(join(repo, "no-testlogs"), repo), { reports: 0, classes: 0 });
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
