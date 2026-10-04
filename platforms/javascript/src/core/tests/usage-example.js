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
import { ModuleRegistry, PdbModule } from "../compiler/module/ModuleRegistry.js";
import { InMemoryModule } from "../compiler/module/inMemoryModule/InMemoryModule.js";
import { Execution } from "../execution/Execution.js";
import { NativeRegistry } from "../execution/natives/NativeRegistry.js";
import { PureRuntime } from "../runtime/PureRuntime.js";
import { EXTENSION_DESCRIPTORS, PURE_LANGUAGE_PDBS } from "../runtime/LanguageExtensions.js";
import { PLATFORM_PDBS } from "../runtime/PlatformModules.js";
import { loadTranslatedCode } from "../runtime/load-runtime.js";

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

// 1. The modules. PURE_LANGUAGE_PDBS is the Pure language itself — mandatory, nothing compiles or
//    runs without it. "sample" is an in-memory module: the only one that holds source rather than a
//    prebuilt archive, and so the only one `compile()` has anything to do.
const registry = new ModuleRegistry(readFileSync(repo("shared/specification/m3.fbs"), "utf8"));
for (const pdb of [...PURE_LANGUAGE_PDBS, ...PLATFORM_PDBS]) {
    registry.register(PdbModule.open(repo(pdb)));
}

// Every language extension on disk, each registered as ONE act: its Pure module and the language it
// adds. The descriptors come from the extensions' own `language_<name>.json` manifests, so no name, path
// or Pure function is written here — add an extension and this loop picks it up.
for (const extension of EXTENSION_DESCRIPTORS) {
    registry.registerExtension(extension, PdbModule.open(repo(extension.pdbPath)));
}

const sample = registry.register(new InMemoryModule("sample", ["core"]));
// Checks every module's dependencies are present, and that each extension's module came with it.
registry.validate();

// 2. The runtime. It speaks `###Pure` plus every registered extension's section.
const runtime = PureRuntime.builder()
    .withRegistry(registry)
    .withNatives(NativeRegistry.createDefault())
    .withEvalJs(new Execution(sample).evalJs)
    .build();

// 3. The translated code the runtime runs on.
await loadTranslatedCode();

// 4. Compile. `compile()` parses every source in every in-memory module, compiles it, and puts the
//    elements back — so after this the registry holds sample::greet.
runtime.setSource("sample", "sample.pure", SOURCE);
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
