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

// Test report for the test dashboard (tools/test-dashboard/README.md): a Node test script
// records each case and, when run with `--report <path>`, writes the report JSON there.
//
//   const report = new TestReport("pct");
//   report.pass(id, ms); report.fail(id, ms, error);
//   report.write();   // no-op without --report

import { mkdirSync, writeFileSync } from "node:fs";
import { dirname } from "node:path";

const stackOf = (error) => typeof error?.stack === "string"
    ? error.stack.split("\n").slice(1).map((l) => l.trim().replace(/^at /, "")).filter(Boolean)
    : [];

export class TestReport {
    constructor(suite) {
        this.suite = suite;
        this.cases = [];
        this.started = Date.now();
    }

    pass(id, durationMs) {
        this.cases.push({ id, status: "passed", durationMs });
    }

    // `error` is an Error or a message.
    fail(id, durationMs, error) {
        const message = String(error?.message ?? error);
        this.cases.push({ id, status: "failed", durationMs, error: { message, stack: stackOf(error) } });
    }

    write(argv = process.argv) {
        const i = argv.indexOf("--report");
        const path = i >= 0 ? argv[i + 1] : undefined;
        if (!path) return;
        mkdirSync(dirname(path), { recursive: true });
        writeFileSync(path, JSON.stringify({ schema: 1, suite: this.suite, durationMs: Date.now() - this.started, cases: this.cases }) + "\n");
    }
}
