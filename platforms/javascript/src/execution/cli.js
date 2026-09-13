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

// Command-line entry for the execution host (bin/pure-js) — the Node
// counterpart of `pure-truffle execute` (platforms/truffle PureCompileMain), so
// module recipes invoke both hosts with the same arguments:
//
//   pure-js execute [--pdb <file>...] --function <path> [--args <arg>...]
//
// `--function` takes the mangled path pure-truffle takes
// (`meta::pure::test::runTests_String_1__String_1_`). A non-empty result is
// printed to stdout; a thrown error — runTests and runPCTTests assert when any
// test fails — goes to stderr with exit code 1. `--pdb` replaces the host's
// default PDB set.

import { loadExecution } from "./execution.js";

const USAGE = "Usage: pure-js execute [--pdb <file>...] --function <path> [--args <arg>...]";

function fail(message) {
    console.error(message);
    process.exit(1);
}

const [command, ...rest] = process.argv.slice(2);
if (command !== "execute") fail(USAGE);

const pdbs = [];
const fnArgs = [];
let fnPath = null;
for (let i = 0; i < rest.length; i++) {
    switch (rest[i]) {
        case "--pdb": pdbs.push(rest[++i]); break;
        case "--function": fnPath = rest[++i]; break;
        case "--args":
            while (i + 1 < rest.length && !rest[i + 1].startsWith("--")) fnArgs.push(rest[++i]);
            break;
        default: fail(`Unknown option: ${rest[i]}\n${USAGE}`);
    }
}
if (!fnPath) fail(USAGE);

const { resolveFn } = await loadExecution(pdbs.length ? { pdbs } : {});

try {
    const result = resolveFn(fnPath, fnArgs.length)(...fnArgs);
    const values = result === undefined || result === null ? [] : Array.isArray(result) ? result : [result];
    if (values.length) process.stdout.write(values.map(String).join("\n") + "\n");
} catch (e) {
    console.error(e && e.stack ? e.stack : String(e));
    process.exitCode = 1;
}
