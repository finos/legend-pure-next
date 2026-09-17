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

// Executes Pure on the JavaScript host: translate Pure code to JavaScript
// IN-PROCESS with the TRANSLATED TRANSLATOR, evaluate emitted JavaScript into the
// shared global scope, and resolve and call translated functions. The natives
// runtime-lib hands to the host live next to it (natives/). Bringing the PDBs,
// metadata globals, natives and generated code together is the runtime's job
// (runtime/load-runtime.js).
//
// The translator (pure/modules/translation/javascript, compiled into
// generated/translator.js + js-lang.js + translation-shared.js) reads the
// elements it translates through the same PDB-backed metadata access the
// compiler host uses. So the loop is self-hosted: Pure code committed to the
// PDBs -> translated to JS text by translated Pure code -> evaluated into this
// very context -> callable alongside everything else.

import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import vm from "node:vm";

const TRANSLATE_PACKAGE =
    "meta$external$language$javascript$translation$pdb$translatePackageToJs_String_1__String_1_";

export class Execution {
    #runtimeModule;
    /** Source text by the file name it was evaluated under (see sourceText). */
    #evaluatedSources = new Map();
    #moduleSources = new Map();

    /** @param runtimeModule the registry's InMemoryModule — translated globals are its function store */
    constructor(runtimeModule) {
        this.#runtimeModule = runtimeModule;
    }

    get runtimeModule() { return this.#runtimeModule; }

    // Methods are bound (arrow fields) so hosts can hand them around as plain
    // functions, e.g. PureRuntime.builder().withEvalJs(execution.evalJs).

    /** The bundled JS source for every translatable element under a package (translation::pdb::translatePackageToJs). */
    translatePackage = (pkgPath) => globalThis[TRANSLATE_PACKAGE](pkgPath);

    /**
     * Evaluate emitted JS into the shared global scope (same contract as the
     * generated modules: export-stripped classic script -> global declarations).
     * Returns the script's completion value, so a caller can scope a module in a
     * function expression and get its exports back (JavaScriptLanguageExtension compileModule).
     */
    evalJs = (source, filename = "translated.js") => {
        const text = source.replace(/^export /gm, "");
        this.#evaluatedSources.set(filename, text);
        const result = vm.runInThisContext(text, { filename });
        this.#runtimeModule.invalidate();
        return result;
    };

    /**
     * The translated global for a Pure function: a full function path (with its
     * signature suffix) resolves exactly; a bare path resolves by prefix, with
     * overloads disambiguated by parameter count.
     */
    resolveFn = (path, arity) => {
        const fn = this.#runtimeModule.resolveFn(path, arity);
        if (!fn) {
            throw new Error(`no translated function for ${path}${arity !== undefined ? `/${arity}` : ""} (loaded generated/ JS and eval'd sources have no matching global)`);
        }
        return fn;
    };

    /** Invoke a translated function by its Pure path. */
    call = (path, ...args) => this.resolveFn(path, args.length)(...args);

    /**
     * Source text by the file name V8 reports in a call site: sources evaluated
     * by evalJs, and the generated modules (imported, so reported as file: URLs).
     * runtime-lib maps call sites back to Pure positions through the markers in
     * that text (__pureStackFrames).
     */
    sourceText = (fileName) => {
        if (this.#evaluatedSources.has(fileName)) return this.#evaluatedSources.get(fileName);
        if (!String(fileName).startsWith("file:")) return undefined;
        if (!this.#moduleSources.has(fileName)) {
            let text;
            try {
                text = readFileSync(fileURLToPath(fileName), "utf8");
            } catch {
                text = undefined;
            }
            this.#moduleSources.set(fileName, text);
        }
        return this.#moduleSources.get(fileName);
    };

}
