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

// The host API end to end, as bootstrap's TestPureRuntime.
//
// Run: node --stack-size=4000 src/core/tests/pure-runtime-tests.js [--report <path>]

import { readFileSync } from "node:fs";
import { ModuleRegistry, PdbModule } from "../compiler/module/ModuleRegistry.js";
import { InMemoryModule } from "../compiler/module/inMemoryModule/InMemoryModule.js";
import { Execution } from "../execution/Execution.js";
import { NativeRegistry } from "../execution/natives/NativeRegistry.js";
import { PureRuntime } from "../runtime/PureRuntime.js";
import { loadTranslatedCode } from "../runtime/load-runtime.js";
import { TestReport } from "./test-report.js";

const SOURCE = `
Class sample::Person
{
    firstName : String[1];
    lastName  : String[1];
}

function sample::fullName(person:sample::Person[1]):String[1]
{
    $person.firstName + ' ' + $person.lastName
}

function sample::greet(firstName:String[1], lastName:String[1]):String[1]
{
    'Hello, ' + ^sample::Person(firstName = $firstName, lastName = $lastName)->sample::fullName() + '!'
}
`;

const repo = (path) => new URL(`../../../../../${path}`, import.meta.url);

const registry = new ModuleRegistry(readFileSync(repo("shared/specification/m3.fbs"), "utf8"));
const core = PdbModule.open(repo("shared/core.pdb"));
registry.register(core);
// Compiling runs compiler-pure and executing runs translated JavaScript: register the compiler and the translator.
registry.register(PdbModule.open(repo("shared/core-tests.pdb")));
registry.register(PdbModule.open(repo("shared/compiler.pdb")));
registry.register(PdbModule.open(repo("pure/modules/language/javascript/build/javascript.pdb")));
registry.register(PdbModule.open(repo("pure/modules/translation/shared/build/translation-shared.pdb")));
registry.register(PdbModule.open(repo("pure/modules/translation/javascript/build/javascript-translation.pdb")));
registry.register(new InMemoryModule("sample", [core.name]));
registry.validate();

const runtime = PureRuntime.builder()
    .withRegistry(registry)
    .withNatives(NativeRegistry.createDefault())
    .withEvalJs(new Execution(registry.module("sample")).evalJs)
    .build();
await loadTranslatedCode();

const report = new TestReport("pure-runtime");
const started = Date.now();
let failure = null;
try {
    runtime.setSource("sample", "sample.pure", SOURCE);
    const result = runtime.compile();
    if (result.errors.length > 0) throw new Error(`compile errors: ${result.errors.join("; ")}`);
    const greeting = runtime.execute(
        runtime.registry.getElement("sample::greet_String_1__String_1__String_1_"),
        "Ada",
        "Lovelace",
    );
    console.log(greeting);
    if (greeting !== "Hello, Ada Lovelace!") throw new Error(`sample::greet returned ${JSON.stringify(greeting)}`);
} catch (error) {
    failure = error;
}
const id = "setSource, compile and execute sample::greet";
if (failure) report.fail(id, Date.now() - started, failure);
else report.pass(id, Date.now() - started);
report.write();
if (failure) {
    console.log(`  FAIL ${id}\n    ${failure.message}`);
    process.exit(1);
}
