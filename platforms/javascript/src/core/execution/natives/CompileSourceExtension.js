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

// The compileSource native (meta::pure::functions::meta::compileSource) as a
// NativesExtension: compile with the TRANSLATED compiler, translate and eval each
// compiled element in-process (execution/translate-elements.js), and return the
// elements as self-contained values — nothing is registered.

import { COMPILE_SOURCE } from "./native-signatures.js";
import { translateCompiledElements } from "../translate-elements.js";

const COMPILE = "meta$pure$compiler$compile_PureFile_MANY__CompilationResult_1_";

/** Host native for dynamic compilation: meta::pure::functions::meta::compileSource. */
export const compileSourceExtension = {
    registerAll(natives) {
        natives.register(COMPILE_SOURCE, (runtime) => compileSource(runtime.registry, runtime.evalJs));
    },
};

function compileSource(registry, evalJs) {
    return (file, dependencies) => {
        const asList = (v) => (v === undefined || v === null ? [] : Array.isArray(v) ? v : [v]);
        // `dependencies` names boot-registered modules (the in-process --pdb
        // flags). Validate loudly; scoping the compiler's view to exactly
        // this subset is a registry feature for later — reads currently see
        // all boot modules.
        const registered = new Set(registry.modules.map((m) => m.name));
        for (const d of asList(dependencies)) {
            // Message is part of the native's cross-platform contract (PCT
            // assertError pins it) — keep it host-detail-free.
            if (!registered.has(d)) throw new Error(`compileSource: dependency module '${d}' is not loaded`);
        }
        // Compile diagnostics are DATA, not exceptions: a failed compile is a
        // normal outcome of compiling user-provided source — return them on
        // the CompileSourceResult (an unknown dependency, by contrast, is a
        // caller bug and throws above).
        const mkResult = (elements, errors) => ({
            elements, errors,
            classifierGenericType: {
                type: globalThis.__pureResolve("meta::pure::functions::meta::CompileSourceResult"),
                __equalityKeys: [],
            },
        });
        let result;
        try {
            result = globalThis[COMPILE]([file]);
        } catch (e) {
            return mkResult([], [String(e && e.message ? e.message : e)]);
        }
        const errors = asList(result.errors).map(String);
        if (errors.length) return mkResult([], errors);
        const elements = asList(result.elements);
        // RUNTIME mode — isolated by contract (see translateCompiledElements).
        translateCompiledElements(elements, evalJs, (file && file.sourceId) || "dynamic");
        return mkResult(elements, []);
    };
}
