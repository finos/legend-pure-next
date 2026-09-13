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

// JavaScript runtime for Legend Pure Next — runs Pure with no JVM / GraalVM.
//
// Organized by concern:
//   - pdb/      : the FlatBuffer PDB reader + metadata globals — the shared
//                 metadata facility both hosts below read the type/function
//                 graph through (backs __metadataRead / __metadataSubtypeOf / …).
//   - grammar/  : the parser. `loadParser()` (grammar/parser.js) loads the
//                 self-contained parser bundle from translation/javascript and
//                 parses Pure source; its cast checks resolve via the PDB
//                 (core.pdb). Corpus runner: grammar/tests/.
//   - compiler/ : the compiler. `loadCompiler()` (compiler/host.js) installs the
//                 metadata globals over the wider PDB set and the generated
//                 compiler JS, so compilation runs standalone too. Corpus
//                 runner: compiler/tests/.
//
// Still to come (the larger arc): a tree-walking interpreter so execution
// (not just parsing + compilation) also runs standalone.

export const VERSION = "0.0.1";
export { loadParser } from "./grammar/parser.js";
export { loadCompiler } from "./compiler/host.js";
