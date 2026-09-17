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

// Host natives for the JavaScript host, keyed by mangled native signature as on
// the JVM hosts. Most JavaScript natives are translation-time coders
// (translation.pure) and never pass through here; these are the ones
// runtime-lib hands to the host through a __host* hook. An implementation is a
// factory `(runtime) => function`: PureRuntime calls it once when it is built,
// so natives reach the registry, evalJs and the in-memory module without
// module-level state.

import { antlrExtension } from "./AntlrExtension.js";
import { compileSourceExtension } from "./CompileSourceExtension.js";
import { javascriptLanguageExtension } from "./JavaScriptLanguageExtension.js";
import {
    ANTLR_NATIVES, COMPILE_SOURCE, DIRECTORY_TREE, EVALUATE, JS_COMPILE_MODULE, JS_DRAIN_COMPILED_SOURCES, JS_EXECUTE, READ_FILE, READ_FILE_BYTES, WRITE_FILE,
} from "./native-signatures.js";

// The key each host native is installed under on the runtime's host object (globalThis.__pureHost).
const HOST_HOOKS = new Map([
    [COMPILE_SOURCE, "hostCompileSource"],
    [EVALUATE, "hostEvaluateFunctionDefinition"],
    [JS_COMPILE_MODULE, "hostJsCompile"],
    [JS_EXECUTE, "hostJsExecute"],
    [JS_DRAIN_COMPILED_SOURCES, "hostJsDrainCompiledSources"],
    [READ_FILE, "hostReadFile"],
    [READ_FILE_BYTES, "hostReadFileBytes"],
    [DIRECTORY_TREE, "hostDirectoryTree"],
    [WRITE_FILE, "hostWriteFile"],
    ...ANTLR_NATIVES.map(([name, signature]) => [signature, name]),
]);

/** The host-object key a native signature is installed under. */
export function hostHookFor(signature) {
    const hook = HOST_HOOKS.get(signature);
    if (!hook) throw new Error(`no runtime-lib hook for native ${signature}`);
    return hook;
}

// Built-ins every JavaScript host can run. Node-only extensions (FileSystemExtension) are added by
// the Node host (runtime/load-runtime.js), so browser pages can import this module.
export const DEFAULT_NATIVES_EXTENSIONS = [compileSourceExtension, javascriptLanguageExtension, antlrExtension];

export class NativeRegistry {
    #natives = new Map();

    /** The host's natives: every extension in `extensions` registers its own. */
    static createDefault(extensions = DEFAULT_NATIVES_EXTENSIONS) {
        const natives = new NativeRegistry();
        for (const extension of extensions) extension.registerAll(natives);
        return natives;
    }

    /** Register `impl`, a factory `(runtime) => function`, under a native signature. */
    register(signature, impl) {
        hostHookFor(signature);
        this.#natives.set(signature, impl);
        return this;
    }

    has(signature) { return this.#natives.has(signature); }
    get signatures() { return [...this.#natives.keys()]; }
    entries() { return this.#natives.entries(); }
}
