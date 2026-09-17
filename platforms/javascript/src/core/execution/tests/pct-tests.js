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

// PCT conformance on the JavaScript platform, fully self-hosted:
//
//   1. TRANSLATE meta::pure::functions IN-PROCESS — the translated translator
//      reads every element (stdlib + the <<PCT.test>> corpus from
//      core-tests.pdb) through the PDB-backed metadata access and emits one JS
//      source. Its size and per-element translation-failure count are
//      reported against generated/core-functions.js (the JVM emission from
//      the same PDBs); byte parity is aspirational for now — the failure
//      comments embed platform-specific error text (Java object toStrings vs
//      JS JSON dumps), so only the failure COUNTS are comparable.
//   2. EVAL the emitted source into this context (redefining the stdlib with
//      the in-process output — so the tests below run what step 1 produced).
//   3. RUN every <<PCT.test>> under meta::pure::functions. Discovery is the
//      translated meta::pure::test::collectPCTTests walking Package children
//      through the metadata globals (same logic every other platform runs);
//      each test gets the translated in-memory adapter and passes or throws.
//
// Run: node --stack-size=4000 src/core/execution/tests/pct-tests.js [--report <path>]
// (needs generated/ JS: `just javascript::generate-all`)

import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { dirname, join } from "node:path";
import { loadRuntime } from "../../runtime/load-runtime.js";
import { TestReport } from "../../tests/test-report.js";

const HERE = dirname(fileURLToPath(import.meta.url));
const GEN = join(HERE, "../../../../generated");
const ROOT_PACKAGE = "meta::pure::functions";

const { translatePackage, evalJs, call, resolveFn } = await loadRuntime();

// The compiler/translator print progress to stdout; mute while they run.
const realWrite = process.stdout.write.bind(process.stdout);
let muted = false;
process.stdout.write = (...a) => (muted ? true : realWrite(...a));
const silently = (fn) => {
    muted = true;
    try { return fn(); } finally { muted = false; }
};

// --- 1. translate in-process, pin against the JVM-emitted module -------------
let t0 = Date.now();
const source = silently(() => translatePackage(ROOT_PACKAGE));
console.log(`translated ${ROOT_PACKAGE} in-process: ${source.length} bytes in ${Date.now() - t0} ms`);

const jvmEmitted = readFileSync(join(GEN, "core-functions.js"), "utf8");
const failCount = (s) => (s.match(/^\/\/ translation failed for /gm) || []).length;
const inProcFails = failCount(source), jvmFails = failCount(jvmEmitted);
console.log(source === jvmEmitted
    ? "translator parity vs generated/core-functions.js (JVM-emitted): IDENTICAL"
    : `translator vs JVM emission: ${source.length} vs ${jvmEmitted.length} bytes, ` +
      `${inProcFails} vs ${jvmFails} elements failed to translate (informational — byte parity is aspirational)`);

// --- 2. eval the emitted source ----------------------------------------------
evalJs(source, "core-functions.inprocess.js");

// --- 3. discover and run the PCT corpus --------------------------------------
const asArr = (v) => (v === undefined || v === null ? [] : Array.isArray(v) ? v : [v]);
const tests = asArr(silently(() =>
    call("meta::pure::test::collectPCTTests", globalThis.__pureResolve(ROOT_PACKAGE))));
if (tests.length === 0) throw new Error(`collectPCTTests found no <<PCT.test>> under ${ROOT_PACKAGE}`);

const adapter = resolveFn("meta::pure::test::pct::testAdapterForInMemoryExecution", 1);

t0 = Date.now();
let passed = 0;
const failures = [];
const report = new TestReport("pct");
for (const test of tests) {
    const path = test.__purePath;
    const started = Date.now();
    try {
        silently(() => resolveFn(path, 1)(adapter));
        passed++;
        report.pass(path, Date.now() - started);
    } catch (e) {
        report.fail(path, Date.now() - started, e instanceof Error ? e : String(e));
        failures.push(`  FAIL ${path}\n        ${String(e && e.message ? e.message : e).split("\n")[0].slice(0, 200)}`);
    }
}
const elapsed = Date.now() - t0;
report.write();

process.stdout.write = realWrite;
console.log(`\npct (in-process translator + eval): ${passed}/${tests.length} passed in ${elapsed} ms\n`);
if (failures.length) console.log(failures.join("\n"));
if (failures.length) process.exitCode = 1;
