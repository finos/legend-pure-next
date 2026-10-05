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

// What the page says when a `###Section` header is misspelt — the failure that stops a
// whole file compiling while naming only the thing that does not exist.
//
// Run: node src/interfaces/web/tests/section-hint-tests.js [--report <path>]

import assert from "node:assert/strict";
import { closestSection, sectionHeaderLine, unknownSectionHint, unknownSectionName }
    from "../section-hint.js";
import { TestReport } from "../../../core/tests/test-report.js";

const results = [];
const report = new TestReport("section-hint");
function check(name, fn) {
    const started = Date.now();
    try { fn(); results.push({ name }); report.pass(name, Date.now() - started); }
    catch (error) { results.push({ name, error }); report.fail(name, Date.now() - started, error); }
}

const KNOWN = ["CompiledGraph", "CompilerStats", "Diagram", "File", "Pure", "ReverseIndex"];
const failure = (name) => new Error(`No parser registered for section: ###${name}`);

check("recognises the unknown-section failure and pulls the name out", () => {
    assert.equal(unknownSectionName(failure("Diargam")), "Diargam");
    assert.equal(unknownSectionName(new Error("something else entirely")), null);
    assert.equal(unknownSectionName(undefined), null);
});

check("catches a transposition — the typo that prompted this", () => {
    // ###Diargam: two letters swapped, and nothing in the original message hints at it.
    assert.equal(closestSection("Diargam", KNOWN), "Diagram");
});

check("catches a miscapitalisation", () => {
    assert.equal(closestSection("diagram", KNOWN), "Diagram");
    assert.equal(closestSection("DIAGRAM", KNOWN), "Diagram");
});

check("catches a missing or doubled letter", () => {
    assert.equal(closestSection("Diagam", KNOWN), "Diagram");
    assert.equal(closestSection("Diaggram", KNOWN), "Diagram");
    assert.equal(closestSection("Pur", KNOWN), "Pure");
});

check("suggests nothing for a name that is not a typo of anything", () => {
    // Guessing wildly is worse than staying quiet — the list of known sections still prints.
    assert.equal(closestSection("Mapping", KNOWN), null);
    assert.equal(closestSection("Relational", KNOWN), null);
});

check("does not let a short name match everything", () => {
    assert.equal(closestSection("Zzz", ["Pure", "Diagram"]), null);
});

check("the hint names the likely intent and what is available", () => {
    const lines = unknownSectionHint(failure("Diargam"), KNOWN);
    assert.equal(lines.length, 2);
    assert.ok(lines[0].includes("###Diargam") && lines[0].includes("###Diagram"), lines[0]);
    assert.ok(lines[1].includes("###Pure") && lines[1].includes("###Diagram"), lines[1]);
});

check("with no near match the hint still lists what is available", () => {
    const lines = unknownSectionHint(failure("Mapping"), KNOWN);
    assert.equal(lines.length, 1);
    assert.ok(lines[0].includes("###CompiledGraph"));
});

check("says so plainly when nothing is registered", () => {
    assert.deepEqual(unknownSectionHint(failure("Diagram"), []), ["No section parsers are registered."]);
});

check("no hint at all for an unrelated failure", () => {
    assert.deepEqual(unknownSectionHint(new Error("unexpected token"), KNOWN), []);
});

check("finds the line the bad header is on, so it can be marked", () => {
    const text = "Class a::B {}\n\n###Diargam\nDiagram a::D\n{\n}\n";
    assert.equal(sectionHeaderLine(text, "Diargam"), 3);
    assert.equal(sectionHeaderLine(text, "Nope"), null);
    // The first line counts too.
    assert.equal(sectionHeaderLine("###Diargam\nDiagram a::D\n{}\n", "Diargam"), 1);
});

const failures = results.filter((r) => r.error);
for (const f of failures) console.log(`  FAIL ${f.name}\n    ${f.error.message}`);
console.log(`section hint: ${results.length - failures.length}/${results.length} passed`);
report.write();
process.exit(failures.length ? 1 : 0);
