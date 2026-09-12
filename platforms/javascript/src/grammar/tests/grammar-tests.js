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

// Standalone grammar-test runner — parses the Pure grammar-test corpus with
// the JavaScript parser (no JVM) and checks each parsed AST against the
// committed protocol.json. Mirrors the Java `PureToJsonRoundtripTest`
// (pure/specification/grammar/tests/**/{grammar.pure,protocol.json}).
//
// Parity is exact: toProtocolJson() applies the same serialization strategy as
// the Java Jackson serializer (simple-name `_type`, NON_EMPTY, numbers), and
// protocolEqual() is a strict structural compare. sourceId is "testFile" to
// match how the baselines were generated.

import { readFileSync, readdirSync, existsSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { dirname, join, relative } from "node:path";
import { loadParser } from "../parser.js";
import { toProtocolJson, protocolEqual } from "./protocol-json.js";

const HERE = dirname(fileURLToPath(import.meta.url));
const TESTS_DIR = join(HERE, "../../../../../pure/specification/grammar/tests");

function findGrammarFiles(dir) {
    const out = [];
    for (const entry of readdirSync(dir, { withFileTypes: true })) {
        const full = join(dir, entry.name);
        if (entry.isDirectory()) out.push(...findGrammarFiles(full));
        else if (entry.name === "grammar.pure") out.push(full);
    }
    return out;
}

function main() {
    const { parse } = loadParser();
    const files = findGrammarFiles(TESTS_DIR).sort();
    const failures = [];
    let compared = 0;

    for (const file of files) {
        const rel = relative(TESTS_DIR, file);
        let ast;
        try {
            ast = parse("testFile", readFileSync(file, "utf8"));
            if (!ast || !Array.isArray(ast.sections)) {
                throw new Error("parser returned no sections");
            }
        } catch (e) {
            failures.push(`  FAIL (parse)  ${rel}\n        ${e.message}`);
            continue;
        }

        // Compare to the committed protocol.json baseline, when present and
        // non-placeholder (mirrors the Java test skipping empty baselines).
        const protocolFile = join(dirname(file), "protocol.json");
        if (!existsSync(protocolFile)) continue;
        let expected;
        try {
            expected = JSON.parse(readFileSync(protocolFile, "utf8"));
        } catch (e) {
            failures.push(`  FAIL (baseline)  ${rel}\n        unreadable protocol.json: ${e.message}`);
            continue;
        }
        if (!expected || Object.keys(expected).length === 0) continue;

        const diff = protocolEqual(toProtocolJson(ast), expected);
        if (diff) failures.push(`  FAIL (protocol)  ${rel}\n        ${diff}`);
        else compared++;
    }

    console.log(
        `\nPure grammar corpus (standalone JS parser): ` +
        `${files.length - failures.length}/${files.length} ok ` +
        `(${compared} matched protocol.json)\n`
    );
    if (failures.length > 0) {
        console.log(failures.join("\n"));
        process.exit(1);
    }
}

main();
