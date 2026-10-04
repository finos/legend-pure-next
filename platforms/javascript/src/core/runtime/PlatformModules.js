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

// PlatformModules.js — the Pure modules THIS PLATFORM brings, as opposed to the ones the language
// needs.
//
// The distinction matters because the two lists have different owners. `PURE_LANGUAGE_PDBS` comes
// from pure/specification/language_pure.json: core.pdb and compiler.pdb are what `###Pure` means,
// on any host. The three below are the JavaScript metamodel and the Pure -> JavaScript translator —
// they are what makes Pure EXECUTABLE here rather than merely compilable, and a Truffle or JVM host
// brings its own instead. They were in the language manifest at first, which was wrong: a language
// specification should not name one platform's translator.
//
// Kept apart from load-runtime.js because the browser host needs them too and cannot import that
// module — it reaches for node:fs and node:vm.

/** Repo-relative, like the language's own list: each module writes into its own build/. */
export const PLATFORM_PDBS = [
    "pure/modules/language/javascript/build/javascript.pdb",                // the JavaScript metamodel
    "pure/modules/translation/shared/build/translation-shared.pdb",         // shared canonicalisation
    "pure/modules/translation/javascript/build/javascript-translation.pdb", // Pure -> JavaScript
];
