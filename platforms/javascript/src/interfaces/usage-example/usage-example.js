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

// THE SIMPLEST WAY TO START PURE ON JAVASCRIPT, and the shortest program that compiles and runs
// Pure source. Read it top to bottom; there are five steps and nothing else:
//
//   1. a registry of modules   — the Pure code the runtime can see
//   2. a runtime               — natives, languages, and how to evaluate translated JavaScript
//   3. the translated code     — runtime-lib, the parser bundles, the generated modules
//   4. a source, compiled      — into the one module that holds code
//   5. a function, executed
//
// There are deliberately NO assertions here. This file is an example, so it has to stay readable;
// what it proves is only that the system starts, because it throws if any step fails. The
// behaviour is pinned next door in host-api-tests.js.
//
// Run: node --stack-size=4000 src/core/tests/usage-example.js

import { readFileSync } from "node:fs";
import { ModuleRegistry, PdbModule } from "../../core/compiler/module/ModuleRegistry.js";
import { InMemoryModule } from "../../core/compiler/module/inMemoryModule/InMemoryModule.js";
import { Execution } from "../../core/execution/Execution.js";
import { NativeRegistry } from "../../core/execution/natives/NativeRegistry.js";
import { PureRuntime } from "../../core/runtime/PureRuntime.js";
import { loadTranslatedCode } from "../../core/runtime/load-runtime.js";

const repo = (path) => new URL(`../../../../../${path}`, import.meta.url);

// One file, two languages. `###Pure` is the language itself; `###Diagram` comes from a language
// extension (pure/extensions/diagram) and compiles to an element just like the classes do.
const SOURCE = `###Pure
Class sample::Person
{
    firstName : String[1];
    lastName  : String[1];
}

function sample::greet(firstName:String[1], lastName:String[1]):String[1]
{
    'Hello, ' + $firstName + ' ' + $lastName + '!'
}

###Diagram
Diagram sample::PersonDiagram
{
   TypeView tv_person(type=sample::Person, position=(100.0, 20.0), width=200.0, height=80.0)
}
`;

// 1. The only module this host owns: "sample" holds source rather than a prebuilt archive, and so is
//    the only one `compile()` has anything to do. What the LANGUAGES need is not named here.
const registry = new ModuleRegistry(readFileSync(repo("shared/specification/m3.fbs"), "utf8"));
const sample = registry.register(new InMemoryModule("sample", ["core"]));

// 2. The runtime, which brings the rest: the modules `###Pure` declares in its own manifest, this
//    platform's metamodel and translator, and every language extension found on disk — each paired with
//    the language it adds. It then loads the translated code it runs on. The two loaders are all a host
//    supplies, because only it knows HOW to read: files here, `fetch` in the browser page.
const runtime = await PureRuntime.builder()
    .withRegistry(registry)
    .withNatives(NativeRegistry.createDefault())
    .withEvalJs(new Execution(sample).evalJs)
    .withModuleLoader(async (paths) => paths.map((pdb) => PdbModule.open(repo(pdb))))
    .withTranslatedCode(loadTranslatedCode)
    .buildAsync();

// 4. Compile. The source goes into the MODULE that holds it — the same call the browser IDE makes and
//    the Java example's `sample.setSource(...)`. `compile()` then parses every source in every
//    in-memory module, compiles it, and puts the elements back, so after this the registry holds
//    sample::greet.
sample.setSource("sample.pure", SOURCE);
const { elements, errors } = runtime.compile();
if (errors.length > 0) throw new Error(`compile failed: ${errors.join("; ")}`);
console.log(`compiled ${elements.length} elements`);

// 5. Execute.
const greet = registry.getElement("sample::greet_String_1__String_1__String_1_");
console.log(runtime.execute(greet, "Ada", "Lovelace"));

// And the diagram is in the graph beside them, its view pointing at the class the same compile
// produced. `[].concat` because a Pure [*] slot crosses as a value or as an array.
const diagram = registry.getElement("sample::PersonDiagram");
console.log(`sample::PersonDiagram: ${[].concat(diagram.typeViews).length} type view(s)`);
