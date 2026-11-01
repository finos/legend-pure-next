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

// node --test tools/bazel/compare-reports.test.mjs

import { test } from "node:test";
import assert from "node:assert/strict";
import { mkdtempSync, mkdirSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { dirname, join } from "node:path";
import { compareReports } from "./compare-reports.mjs";

const report = (cases) => ({ schema: 1, suite: "s", durationMs: 1, cases: cases.map(([id, status]) => ({ id, status, durationMs: 1 })) });

// A tree of reports: { "spec/compiler/pure@cli-truffle.json": [["a", "passed"]], … }
function tree(files) {
    const root = mkdtempSync(join(tmpdir(), "reports-"));
    Object.entries(files).forEach(([path, cases]) => {
        mkdirSync(dirname(join(root, path)), { recursive: true });
        writeFileSync(join(root, path), JSON.stringify(report(cases)));
    });
    return root;
}

const ONE = { "spec/compiler/pure@cli-truffle.json": [["a", "passed"], ["b", "skipped"]] };

test("identical trees pass", () => {
    const result = compareReports(tree(ONE), tree(ONE));
    assert.equal(result.ok, true);
    assert.deepEqual(result.problems, []);
});

test("a missing report fails, naming its path", () => {
    const result = compareReports(tree(ONE), tree({}));
    assert.equal(result.ok, false);
    assert.match(result.problems.join("\n"), /missing report 'spec\/compiler\/pure@cli-truffle\.json'/);
});

test("a missing case fails, naming its id", () => {
    const result = compareReports(tree(ONE), tree({ "spec/compiler/pure@cli-truffle.json": [["a", "passed"]] }));
    assert.equal(result.ok, false);
    assert.match(result.problems.join("\n"), /missing case 'b'/);
});

test("a case going from passed to failed fails", () => {
    const result = compareReports(tree(ONE), tree({ "spec/compiler/pure@cli-truffle.json": [["a", "failed"], ["b", "skipped"]] }));
    assert.equal(result.ok, false);
    assert.match(result.problems.join("\n"), /case 'a' was 'passed' and is 'failed'/);
});

test("a skipped case staying skipped passes", () => {
    assert.equal(compareReports(tree(ONE), tree(ONE)).ok, true);
});

test("extra reports and cases are allowed, and listed", () => {
    const result = compareReports(tree(ONE), tree({
        "spec/compiler/pure@cli-truffle.json": [["a", "passed"], ["b", "skipped"], ["c", "passed"]],
        "spec/pct/pure@cli-truffle.json": [["x", "passed"]],
    }));
    assert.equal(result.ok, true);
    assert.match(result.extras.join("\n"), /extra report 'spec\/pct\/pure@cli-truffle\.json'/);
    assert.match(result.extras.join("\n"), /extra case 'c'/);
});

test("--only compares the reports under a path prefix", () => {
    const baseline = tree({ ...ONE, "spec/pct/pure@cli-truffle.json": [["x", "passed"]] });
    assert.equal(compareReports(baseline, tree(ONE), { only: "spec/compiler" }).ok, true);
    assert.equal(compareReports(baseline, tree(ONE)).ok, false);
});

test("--only-run compares only the reports of one run, by file name", () => {
    const baseline = tree({ ...ONE, "spec/compiler/pure@cli-bootstrap.json": [["a", "passed"]] });
    assert.equal(compareReports(baseline, tree(ONE), { onlyRun: "pure@cli-truffle" }).ok, true);
    assert.equal(compareReports(baseline, tree(ONE), { onlyRun: "pure@cli-bootstrap" }).ok, false);
});
