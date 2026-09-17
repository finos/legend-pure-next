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

// Standalone compiler conformance runner — compiles each Pure spec test with
// the translated compiler (no JVM) and compares against its committed oracle:
//   - `###CompiledGraph` tests: printCompiledGraph(result) must match (success).
//   - `###Error` tests: the compile errors must match (error-flow).
// Metadata reflection is served by the PDB reader (src/core/compiler/module), so this runs fully
// standalone.
//
// The `###Pure` section is placed at its original line in the file (error
// positions are file-relative, and `###Error` sections precede `###Pure`), and
// the sourceId is the test's relative path (without `.pure`) so `(at <id>:LcC)`
// positions match. For `###CompiledGraph` tests `###Pure` is first, so this is a
// no-op offset and the graph (which omits the sourceId) is unaffected.
//
// Pass --roundtrip to additionally exercise the PDB writer: every ###CompiledGraph
// test that passes is serialized to .pdb bytes, reopened through the reader, and
// re-printed; the round-tripped print must be byte-identical to the direct one.
//
// Pass --golden to check the PDB WRITER against Java: every passing ###CompiledGraph
// test is serialized to .pdb and structurally diffed (src/core/compiler/module/tests/struct-diff) against
// the Java-produced golden in pure/specification/compiler/tests-pdb-serialization (regenerate with the Java
// PdbGoldenGeneratorTest). This is the authoritative "writes exactly what Java writes"
// check; --roundtrip only proves JS reader/writer self-consistency.
//
// Pass --report <path> to write a test report for the dashboard (tools/test-dashboard): one case per
// corpus file, failed when its compile result doesn't match the oracle.
//
// Run: node --stack-size=4000 src/core/compiler/tests/compiler-tests.js [--roundtrip] [--golden] [--report <path>]

import { readFileSync, readdirSync, existsSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { dirname, join, relative } from "node:path";
import { loadCompiler } from "../load-compiler.js";
import { roundTripElements, withModule } from "../module/tests/roundtrip.js";
import { openZip } from "../module/pdbModule/zip/zip.js";
import { diffArchives } from "../module/tests/struct-diff.js";
import { TestReport } from "../../tests/test-report.js";

const HERE = dirname(fileURLToPath(import.meta.url)); // .../src/core/compiler/tests
const REPO = join(HERE, "../../../../../.."); // -> repo root
const TESTS = join(REPO, "pure/specification/compiler/tests");
const GOLDENS = process.env.PDB_GOLDENS_DIR || join(HERE, "../../../../../../pure/specification/compiler/tests-pdb-serialization");

const clean = (s) => (s || "").replace(/\r/g, "").split("\n").map((l) => l.trim()).filter((l) => l).join("\n").trim();
const section = (c, n) => { const m = c.match(new RegExp("###" + n + "\\n([\\s\\S]*?)(?=\\n###|$)")); return m ? m[1] : null; };

function findTests(dir) {
    const out = [];
    for (const e of readdirSync(dir, { withFileTypes: true })) {
        const p = join(dir, e.name);
        if (e.isDirectory()) out.push(...findTests(p));
        else if (e.name.endsWith(".pure")) out.push(p);
    }
    return out;
}

async function main() {
    const roundtrip = process.argv.includes("--roundtrip");
    const golden = process.argv.includes("--golden");
    const { parse, compile, printGraph, registry } = await loadCompiler();

    // The compiler prints progress/stats to stdout; mute it during the run.
    const realWrite = process.stdout.write.bind(process.stdout);
    let muted = false;
    process.stdout.write = (...a) => (muted ? true : realWrite(...a));

    const files = findTests(TESTS)
        .filter((f) => { const c = readFileSync(f, "utf8"); return c.includes("###CompiledGraph") || c.includes("###Error"); })
        .sort();

    let graphPass = 0, graphTotal = 0, errPass = 0, errTotal = 0;
    let rtPass = 0, rtTotal = 0;
    let gPass = 0, gTotal = 0, eIdxGaps = 0;
    const fails = [], rtFails = [], gFails = [];
    const report = new TestReport("compiledGraph");
    // A file's case settles when the next one starts (the loop body `continue`s on failure).
    let open = null;
    const settle = () => {
        if (!open) return;
        const ms = Date.now() - open.t0;
        if (fails.length > open.fails) report.fail(open.id, ms, fails[open.fails].trim());
        else report.pass(open.id, ms);
        open = null;
    };
    for (const f of files) {
        settle();
        const c = readFileSync(f, "utf8");
        const rel = relative(TESTS, f);
        const sid = rel.replace(/\.pure$/, "");
        open = { id: sid.split("\\").join("/"), t0: Date.now(), fails: fails.length };
        const isError = c.includes("###Error");
        if (isError) errTotal++; else graphTotal++;
        try {
            muted = true;
            // Position ###Pure at its original file line so error positions match.
            const nl = (c.slice(0, c.indexOf("###Pure")).match(/\n/g) || []).length;
            const input = "\n".repeat(nl) + "###Pure\n" + section(c, "Pure");
            const r = compile([parse(sid, input)]);
            muted = false;
            if (isError) {
                const actual = clean((r.errors || []).join("\n"));
                if (actual === clean(section(c, "Error"))) errPass++;
                else fails.push(`  FAIL ${rel}  [error mismatch]`);
            } else {
                if (r.errors && r.errors.length) { fails.push(`  FAIL ${rel}  [${r.errors.length} unexpected errors]`); continue; }
                const ctx = Array.isArray(r.context) ? r.context[0] : r.context;
                const direct = clean(printGraph(r.elements, ctx));
                if (direct !== clean(section(c, "CompiledGraph"))) { fails.push(`  FAIL ${rel}  [graph mismatch]`); continue; }
                graphPass++;
                if (roundtrip) {
                    rtTotal++;
                    muted = true;
                    try {
                        const rt = roundTripElements(r.elements);
                        const reprint = withModule(registry, rt.module, () =>
                            clean(printGraph(rt.paths.map((p) => globalThis.__pureResolve(p)), ctx)));
                        const same = reprint === direct;
                        muted = false;
                        if (same) rtPass++;
                        else rtFails.push(`  RT-FAIL ${rel}  [round-trip print differs]`);
                    } catch (e) {
                        muted = false;
                        rtFails.push(`  RT-FAIL ${rel}  [${String(e.message).slice(0, 70)}]`);
                    }
                }
                if (golden) {
                    const gpath = join(GOLDENS, sid + ".pdb");
                    if (existsSync(gpath)) {
                        gTotal++;
                        muted = true;
                        try {
                            const referencedBy = Array.isArray(ctx?.referencedBy) ? ctx.referencedBy[0] : ctx?.referencedBy;
                            const rt = roundTripElements(r.elements, referencedBy);
                            const js = openZip(rt.bytes);
                            const java = openZip(readFileSync(gpath));
                            const findings = diffArchives(registry.schema, js, java);
                            // Non-element sections (manifest, functionIndex,
                            // reverseReferenceIndex) must match the Java reference BYTE-for-byte —
                            // they use a shared per-section builder, so structural diffing isn't
                            // needed and byte equality is the strongest check.
                            //
                            // elementIndex is byte-checked only when the element ENTRY SETS match:
                            // the goldens carry module Package elements (file-anchor packages the
                            // Java compiler registers eagerly) that the Pure compiler's
                            // CompilationResult.elements doesn't yet include — an upstream
                            // registration gap, not a serializer difference. Counted and reported
                            // below; elementIndex byte parity on a matching element list is
                            // covered by the truffle self-host deep diff.
                            const jsEls = new Set(js.names().filter((n) => n.startsWith("elements/")));
                            const javaEls = new Set(java.names().filter((n) => n.startsWith("elements/")));
                            const elSetsMatch = jsEls.size === javaEls.size && [...jsEls].every((n) => javaEls.has(n));
                            if (!elSetsMatch) eIdxGaps++;
                            const sections = [...new Set([...js.names(), ...java.names()])]
                                .filter((n) => !n.startsWith("elements/"))
                                .filter((n) => elSetsMatch || n !== "elementIndex");
                            for (const s of sections) {
                                const a = js.has(s) ? js.read(s) : null;
                                const b = java.has(s) ? java.read(s) : null;
                                if (!a) findings.push({ kind: "section missing (js)", path: s });
                                else if (!b) findings.push({ kind: "section extra (js)", path: s });
                                else if (a.length !== b.length || a.some((v, i) => v !== b[i])) {
                                    findings.push({ kind: `section bytes differ (${a.length} vs ${b.length})`, path: s });
                                }
                            }
                            muted = false;
                            if (!findings.length) gPass++;
                            else { const f = findings[0]; gFails.push(`  G-FAIL ${rel}  [${findings.length}] ${f.field ? f.field + " js=" + f.a + " java=" + f.b : f.kind + " " + f.path}`); }
                        } catch (e) {
                            muted = false;
                            gFails.push(`  G-FAIL ${rel}  [${String(e.message).slice(0, 70)}]`);
                        }
                    }
                }
            }
        } catch (e) {
            muted = false;
            fails.push(`  FAIL ${rel}  [${String(e.message).slice(0, 70)}]`);
        }
    }
    settle();
    process.stdout.write = realWrite;
    report.write();

    const pass = graphPass + errPass, total = graphTotal + errTotal;
    console.log(`\nstandalone compiler: ${pass}/${total} match  ` +
        `(graph ${graphPass}/${graphTotal}, error-flow ${errPass}/${errTotal})\n`);
    if (fails.length) console.log(fails.join("\n"));
    if (roundtrip) {
        console.log(`\npdb round-trip: ${rtPass}/${rtTotal} re-print identically\n`);
        if (rtFails.length) console.log(rtFails.join("\n"));
    }
    if (golden) {
        console.log(`\npdb writer vs Java golden: ${gPass}/${gTotal} structurally identical\n`);
        if (eIdxGaps) {
            console.log(`  (${eIdxGaps} goldens carry Package entries the Pure compile result lacks — ` +
                `elementIndex byte check skipped there; known upstream registration gap)\n`);
        }
        if (gFails.length) console.log(gFails.join("\n"));
    }
    if (pass !== total || rtFails.length || gFails.length) process.exitCode = 1;
}

main();
