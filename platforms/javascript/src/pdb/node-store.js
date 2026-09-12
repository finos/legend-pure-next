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

// Node-only convenience: read the schema + PDB files from disk and build a
// module registry (one PdbModule per archive, named after its file). Kept
// separate from modules.js so the neutral core stays importable in the
// browser, which has no `node:fs` — there, fetch the bytes, create the
// modules, and call createRegistry directly.

import { readFileSync } from "node:fs";
import { basename } from "node:path";
import { openZip } from "./zip/zip.js";
import { createRegistry, createPdbModule } from "./modules.js";

/** Build a registry from a schema (.fbs) path and a list of .pdb file paths. */
export function createStore(schemaPath, pdbPaths) {
    // Schema TEXT, not a parsed schema: the registry parses it lazily with the
    // translated Pure parser, which is only importable after generated/ loads.
    const modules = pdbPaths.map((p) =>
        createPdbModule(basename(p, ".pdb"), openZip(readFileSync(p))));
    return createRegistry(readFileSync(schemaPath, "utf8"), modules);
}
