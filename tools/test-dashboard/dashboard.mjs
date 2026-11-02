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

// The test dashboard from the last test run (bazel run //:dashboard): the reports of the tests that ran
// (tools/bazel/collect-reports.mjs, into test-results/ and <module>/target/surefire-reports), rendered into
// test-results/dashboard.html (tools/test-dashboard/build.mjs). Both run from the source tree, which build.mjs reads
// the galleries and JUnit results from, with the Node Bazel runs this with.

import { execFileSync } from "node:child_process";
import { mkdtempSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";

const root = process.env.BUILD_WORKSPACE_DIRECTORY;
if (!root) {
    console.error("run it with bazel run //:dashboard");
    process.exit(2);
}
const testlogs = execFileSync("bazel", ["info", "bazel-testlogs"], { cwd: root, encoding: "utf8" }).trim();
// Only the tests the workspace has: Bazel keeps the logs of a test it no longer has.
const targets = join(mkdtempSync(join(tmpdir(), "dashboard-")), "targets.txt");
writeFileSync(targets, execFileSync("bazel", ["query", "tests(//...)"], { cwd: root, encoding: "utf8" }));
const run = (script, args) => execFileSync(process.execPath, [join(root, script), ...args], { cwd: root, stdio: "inherit" });
run("tools/bazel/collect-reports.mjs", [testlogs, root, "--targets", targets]);
run("tools/test-dashboard/build.mjs", []);
console.log(`  open ${join(root, "test-results", "dashboard.html")}`);
