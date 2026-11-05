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

// Command-line entry for the execution host (pure-js (//platforms/javascript:pure-js)) — the Node
// counterpart of `pure-truffle execute` (platforms/truffle PureCompileMain), so
// module suites invoke both hosts with the same arguments:
//
//   pure-js execute [--pdb <file>...] --function <path> [--args <arg>...]
//   pure-js compile --base-pdb <file>... --source <dir>... --output-dir <dir> [--tests {none|split|with}]
//
// `--function` takes the mangled path pure-truffle takes
// (`platform::pure::test::runTests_String_1__String_1_`). A non-empty result is
// printed to stdout; a thrown error — runTests and runPCTTests assert when any
// test fails — goes to stderr with exit code 1. `--pdb` replaces the host's
// default PDB set.

import { resolve } from "node:path";
import { loadRuntime } from "../../core/runtime/load-runtime.js";

const COMPILE_MODULE = "platform::pure::compiler::pdb::archive::compileModule_String_MANY__String_1__String_1__CompilerExtension_MANY__String_MANY_";

const USAGE = `Usage: pure-js execute [--pdb <file>...] --function <path> [--args <arg>...]
       pure-js compile --base-pdb <file>... --source <dir>... --output-dir <dir> [--tests {none|split|with}]`;

function fail(message) {
    console.error(message);
    process.exit(1);
}

const [command, ...rest] = process.argv.slice(2);
if (command !== "execute" && command !== "compile") fail(USAGE);

const pdbs = [];
const fnArgs = [];
const sources = [];
let fnPath = null;
let outputDir = null;
let tests = "none";
for (let i = 0; i < rest.length; i++) {
    switch (rest[i]) {
        case "--pdb":
        case "--base-pdb": pdbs.push(rest[++i]); break;
        case "--function": fnPath = rest[++i]; break;
        case "--args":
            while (i + 1 < rest.length && !rest[i + 1].startsWith("--")) fnArgs.push(rest[++i]);
            break;
        case "--source": sources.push(resolve(rest[++i])); break;
        case "--output-dir": outputDir = rest[++i]; break;
        case "--tests": tests = rest[++i]; break;
        default: fail(`Unknown option: ${rest[i]}\n${USAGE}`);
    }
}
const compile = command === "compile";
if (compile ? !sources.length || !outputDir : !fnPath) fail(USAGE);

const { registry, runtime } = await loadRuntime(pdbs.length ? { pdbs } : {});

const fn = registry.getElement(compile ? COMPILE_MODULE : fnPath);
if (!fn) fail(`Function not found: ${compile ? COMPILE_MODULE : fnPath}`);
try {
    const result = compile
        ? runtime.execute(fn, sources, outputDir, tests, runtime.compilerExtensions())
        : runtime.execute(fn, ...fnArgs);
    const values = result === undefined || result === null ? [] : Array.isArray(result) ? result : [result];
    if (values.length) process.stdout.write(values.map(String).join("\n") + "\n");
} catch (e) {
    console.error(e && e.stack ? e.stack : String(e));
    process.exitCode = 1;
}
