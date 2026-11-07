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

// Pure maps on the JavaScript platform (runtime-lib's __PureMap): they behave as the [k, v] arrays they replaced,
// copied before every update with keys compared by ===, and they are immutable: whatever is done to any
// version, every version keeps reading exactly as it did when it was made.
//
// Run: node src/core/tests/persistent-map-tests.js [--report <path>]

import assert from "node:assert/strict";
import vm from "node:vm";
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { dirname, join } from "node:path";
import { TestReport } from "./test-report.js";

const HERE = dirname(fileURLToPath(import.meta.url));
vm.runInThisContext(readFileSync(join(HERE, "../../../../../pure/modules/translation/javascript/js/runtime-lib.js"), "utf8"), { filename: "runtime-lib.js" });
const g = globalThis;

const results = [];
const report = new TestReport("persistent-map");
function check(name, fn) {
    const started = Date.now();
    try {
        fn();
        results.push({ name });
        report.pass(name, Date.now() - started);
    } catch (error) {
        results.push({ name, error });
        report.fail(name, Date.now() - started, error);
    }
}

// The model: what a map was before, an array of [k, v] copied by every update.
const model = {
    put: (entries, k, v) => (entries.some(([ek]) => ek === k) ? entries.map(([ek, ev]) => (ek === k ? [k, v] : [ek, ev])) : [...entries, [k, v]]),
    remove: (entries, k) => entries.filter(([ek]) => ek !== k),
    putAll: (entries, other) => other.reduce((acc, [k, v]) => model.put(acc, k, v), entries),
};

// A deterministic generator, so a failure replays.
function random(seed) {
    let state = seed;
    return (n) => {
        state = (Math.imul(state, 1103515245) + 12345) & 0x7fffffff;
        return state % n;
    };
}

// "Aa" and "BB" hash alike under the 31-multiplier string hash, and so do their concatenations: whole-hash
// collisions. Objects are keys by identity, BigInts and numbers by value.
const OBJECTS = Array.from({ length: 20 }, (_, i) => ({ id: i }));
const COLLIDING = ["Aa", "BB", "AaAa", "AaBB", "BBAa", "BBBB"];
function randomKey(next) {
    const kind = next(5);
    if (kind === 0) return COLLIDING[next(COLLIDING.length)];
    if (kind === 1) return OBJECTS[next(OBJECTS.length)];
    if (kind === 2) return BigInt(next(200));
    if (kind === 3) return next(200);
    return "s" + next(200);
}

function assertSame(entries, map) {
    assert.equal(g.__isMap(map), true);
    assert.equal(map.__mapEntries.length, entries.length);
    entries.forEach(([k, v], i) => {
        assert.equal(map.__mapEntries[i][0], k);
        assert.equal(map.__mapEntries[i][1], v);
        assert.deepEqual(g.__mapGet(map, k), [v]);
    });
    assert.deepEqual(g.__mapKeys(map), entries.map(([k]) => k));
    assert.deepEqual(g.__mapValues(map), entries.map(([, v]) => v));
}

check("random updates of random versions match copied entry arrays", () => {
    const next = random(11);
    const versions = [g.__newMap()];
    const models = [[]];
    let head = 0;
    Array.from({ length: 4000 }).forEach((_, step) => {
        // Mostly from the head of a main line, so maps grow large, and otherwise from any older one.
        const from = next(4) > 0 ? head : next(versions.length);
        const op = next(10);
        const key = randomKey(next);
        const [map, entries] = op < 6
            ? [g.__mapPut(versions[from], key, step), model.put(models[from], key, step)]
            : op < 8
                ? [g.__mapRemove(versions[from], key), model.remove(models[from], key)]
                : (() => {
                    const other = next(versions.length);
                    return [g.__mapPutAll(versions[from], versions[other]), model.putAll(models[from], models[other])];
                })();
        versions.push(map);
        models.push(entries);
        if (from === head) head = versions.length - 1;
    });
    versions.forEach((map, k) => assertSame(models[k], map));
    assert.ok(Math.max(...models.map((m) => m.length)) > 300);
});

check("a large map grown and shrunk in a loop keeps its order", () => {
    const keys = Array.from({ length: 40000 }, (_, i) => "k" + i);
    const full = keys.reduce((m, k, i) => g.__mapPut(m, k, i), g.__newMap());
    // Removing most entries rebuilds the order vector; the order of the rest must survive it.
    const kept = keys.filter((_, i) => i % 3 === 2);
    const shrunk = g.__mapRemoveAll(full, keys.filter((_, i) => i % 3 !== 2));
    assertSame(kept.map((k) => [k, Number(k.slice(1))]), shrunk);
    assert.equal(full.__mapEntries.length, 40000);
    assert.deepEqual(g.__mapGet(full, "k39999"), [39999]);
});

check("pairs build maps, literal maps convert, and getIfAbsentPut only reads", () => {
    const pair = (first, second) => ({ first, second });
    const built = g.__newMap([pair("a", 1), pair("b", 2), pair("a", 3)]);
    assertSame([["a", 3], ["b", 2]], built);
    const literal = { __mapEntries: [["x", 1], ["y", 2]] };
    assertSame([["x", 1], ["y", 2], ["z", 3]], g.__mapPut(literal, "z", 3));
    assertSame([["x", 1], ["y", 2]], g.__mapPutAll(g.__newMap(), literal));
    assertSame([["a", 3], ["b", 2], ["c", 4]], g.__mapPutAll(built, [pair("c", 4)]));
    assert.equal(g.__mapGetIfAbsentPut(built, "zz", () => 9), 9);
    assertSame([["a", 3], ["b", 2]], built);
});

check("maps with the same entries are equal whatever their order, and entries cannot be written", () => {
    const ab = g.__mapPut(g.__mapPut(g.__newMap(), "a", 1), "b", 2);
    const ba = g.__mapPut(g.__mapPut(g.__newMap(), "b", 2), "a", 1);
    assert.equal(g.__eq(ab, ba), true);
    assert.equal(g.__eq(ab, g.__mapPut(ba, "a", 5)), false);
    assert.equal(Object.isFrozen(ab.__mapEntries), true);
});

const failures = results.filter((r) => r.error);
failures.forEach((f) => console.log(`  FAIL ${f.name}\n    ${f.error.stack}`));
console.log(`persistent map: ${results.length - failures.length}/${results.length} passed`);
report.write();
process.exit(failures.length ? 1 : 0);
