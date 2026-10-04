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

// The workspace store: what survives a reload, and what happens when the browser refuses.
//
// The failure modes are the point. `localStorage` is absent or throws in a private window,
// comes back empty after cleared site data, throws on quota, and can hold a blob written by
// an older or newer version of the page. In every one of those the editor must still open —
// on the sample if need be — because failing to remember is a nuisance and failing to open
// is a broken page.
//
// Run: node src/interfaces/web/tests/source-store-tests.js [--report <path>]

import assert from "node:assert/strict";
import { clearWorkspace, debounce, loadWorkspace, normalizeSourceId, saveWorkspace, uniqueSourceId }
    from "../source-store.js";
import { TestReport } from "../../../core/tests/test-report.js";

const results = [];
const report = new TestReport("source-store");

// Awaited, and the cases are queued rather than run inline: debounce is time-based, so one
// case here is async, and a non-awaiting runner would turn its failure into an unhandled
// rejection that still printed a pass.
const queue = [];
const check = (name, fn) => queue.push([name, fn]);

async function run() {
    for (const [name, fn] of queue) {
        const started = Date.now();
        try { await fn(); results.push({ name }); report.pass(name, Date.now() - started); }
        catch (error) { results.push({ name, error }); report.fail(name, Date.now() - started, error); }
    }
}

/** A working localStorage stand-in. */
const fake = (initial = {}) => {
    const map = new Map(Object.entries(initial));
    return {
        getItem: (k) => (map.has(k) ? map.get(k) : null),
        setItem: (k, v) => map.set(k, String(v)),
        removeItem: (k) => map.delete(k),
        get size() { return map.size; },
    };
};
/** One that throws on everything, as a private window or blocked site data does. */
const hostile = () => ({
    getItem() { throw new Error("SecurityError"); },
    setItem() { throw new Error("QuotaExceededError"); },
    removeItem() { throw new Error("SecurityError"); },
});

const WORKSPACE = { sources: { "editor": "Class a::B {}", "other.pure": "Class a::C {}" }, active: "other.pure" };

check("round-trips a workspace", () => {
    const store = fake();
    assert.equal(saveWorkspace(WORKSPACE, store), true);
    assert.deepEqual(loadWorkspace(store), WORKSPACE);
});

check("nothing stored yields nothing, not an empty workspace", () => {
    // The caller distinguishes "never saved" from "saved empty" to decide on the sample.
    assert.equal(loadWorkspace(fake()), null);
});

check("survives storage that throws on every access", () => {
    const store = hostile();
    assert.equal(loadWorkspace(store), null);
    assert.equal(saveWorkspace(WORKSPACE, store), false);
    clearWorkspace(store);   // must not throw
});

check("survives storage being absent altogether", () => {
    assert.equal(loadWorkspace(null), null);
    assert.equal(saveWorkspace(WORKSPACE, null), false);
    clearWorkspace(null);
});

check("ignores a corrupt blob rather than throwing", () => {
    assert.equal(loadWorkspace(fake({ "pure-web-sources": "{not json" })), null);
    assert.equal(loadWorkspace(fake({ "pure-web-sources": "null" })), null);
    assert.equal(loadWorkspace(fake({ "pure-web-sources": '{"v":1}' })), null);
});

check("ignores a blob from another version instead of guessing at it", () => {
    // Showing someone a misread version of their own file is worse than the sample.
    assert.equal(loadWorkspace(fake({ "pure-web-sources": '{"v":99,"sources":{"a":"x"}}' })), null);
    assert.equal(loadWorkspace(fake({ "pure-web-sources": '{"sources":{"a":"x"}}' })), null);
});

check("drops entries that are not name-to-text", () => {
    const store = fake({ "pure-web-sources": '{"v":1,"sources":{"a.pure":"ok","b.pure":42,"":"x"}}' });
    assert.deepEqual(loadWorkspace(store), { sources: { "a.pure": "ok" }, active: "a.pure" });
});

check("falls back to a real file when the active one is gone", () => {
    const store = fake({ "pure-web-sources": '{"v":1,"sources":{"a.pure":"x"},"active":"deleted.pure"}' });
    assert.equal(loadWorkspace(store).active, "a.pure");
});

check("keeps an empty file, and can make it active", () => {
    // An empty file is a real file the user created; only a missing one is not.
    const store = fake();
    saveWorkspace({ sources: { "a.pure": "" }, active: "a.pure" }, store);
    assert.deepEqual(loadWorkspace(store), { sources: { "a.pure": "" }, active: "a.pure" });
});

check("clearing means the next load starts fresh", () => {
    const store = fake();
    saveWorkspace(WORKSPACE, store);
    clearWorkspace(store);
    assert.equal(loadWorkspace(store), null);
});

// --- file names -----------------------------------------------------------------------

check("gives a name the .pure extension and strips separators", () => {
    assert.equal(normalizeSourceId("model"), "model.pure");
    assert.equal(normalizeSourceId("model.pure"), "model.pure");
    assert.equal(normalizeSourceId("  spaced  "), "spaced.pure");
    // A path would make the id ambiguous and the label misleading.
    assert.equal(normalizeSourceId("a/b/model.pure"), "abmodel.pure");
    assert.equal(normalizeSourceId("..\\\\evil"), "..evil.pure");
});

check("rejects a name with nothing usable in it", () => {
    assert.equal(normalizeSourceId(""), null);
    assert.equal(normalizeSourceId("   "), null);
    assert.equal(normalizeSourceId("///"), null);
    assert.equal(normalizeSourceId(".pure"), null);
    assert.equal(normalizeSourceId(undefined), null);
});

check("makes a name unique rather than overwriting a file", () => {
    assert.equal(uniqueSourceId("model", []), "model.pure");
    assert.equal(uniqueSourceId("model", ["model.pure"]), "model-2.pure");
    assert.equal(uniqueSourceId("model", ["model.pure", "model-2.pure"]), "model-3.pure");
    assert.equal(uniqueSourceId("///", ["model.pure"]), null);
});

// --- debounce -------------------------------------------------------------------------

check("collapses a burst into one call", async () => {
    let calls = 0;
    const save = debounce(() => calls++, 5);
    save(); save(); save();
    assert.equal(calls, 0, "nothing runs during the burst");
    await new Promise((r) => setTimeout(r, 25));
    assert.equal(calls, 1);
});

check("flush runs the pending call immediately", () => {
    let seen = null;
    const save = debounce((v) => { seen = v; }, 1000);
    save("stale");
    save.flush("fresh");
    assert.equal(seen, "fresh");
});

await run();

const failures = results.filter((r) => r.error);
for (const f of failures) console.log(`  FAIL ${f.name}\n    ${f.error.message}`);
console.log(`source store: ${results.length - failures.length}/${results.length} passed`);
report.write();
process.exit(failures.length ? 1 : 0);
