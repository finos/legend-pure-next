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

// The compileSource native's host implementation, shared by every JS host
// (Node execution host, browser page). Host-agnostic: it only touches
// globalThis (the translated compiler/translator globals and the metadata
// bridge) plus the two injected capabilities — the registry (for dependency
// validation) and the host's evalJs (Node uses vm.runInThisContext, the
// browser indirect eval).
//
// runtime-lib's __compileSource routes here: parse happens at the call site
// (the native takes an already-parsed PureFile), we compile with the
// TRANSLATED compiler (metadata reads flow through the bridge to the
// boot-registered modules), translate each compiled function in-process,
// eval it, and return the compile result's elements as SELF-CONTAINED
// VALUES — nothing is registered; the module registry stays immutable after
// boot. Each function value is tagged with __purePath so evaluate/eval
// dispatch to the freshly eval'd translated global via __metadataInvoke.

const COMPILE = "meta$pure$compiler$compile_PureFile_MANY__CompilationResult_1_";
const TRANSLATE_ELEMENT = "meta$external$language$javascript$translation$pdb$translateElement_PackageableElement_1__String_1_";

/**
 * Translate every translatable element kind from a compile result and eval
 * each emission — the same dispatcher the package bundler uses: functions get
 * their bodies, classes get their `__registerClass` metadata decls (so `new`
 * on a freshly-compiled class resolves properties/equality keys at run time),
 * enumerations their self-describing consts, packages nothing. Failure
 * comments ("// … failed …") are skipped, mirroring the bundler's degrade
 * path. Tags each element with `__purePath` so evaluate/eval dispatch to the
 * freshly eval'd translated global via `__metadataInvoke`.
 *
 * Returns the emitted `{path, source}` pairs — hosts that want to SHOW the
 * generated JavaScript (the browser page) read them from here.
 */
export function translateCompiledElements(elements, evalJs, sourceId = "dynamic") {
    const emitted = [];
    for (const el of elements) {
        if (!el || typeof el !== "object") continue;
        const src = globalThis[TRANSLATE_ELEMENT](el);
        el.__purePath = globalThis.__elementToPath(el);
        if (typeof src === "string" && src.trim() && !/^\/\/ .* failed/.test(src.trim())) {
            evalJs(src, `compileSource:${sourceId}.js`);
            emitted.push({ path: el.__purePath, source: src });
        }
    }
    return emitted;
}

export function installHostCompileSource(registry, evalJs) {
    globalThis.__hostCompileSource = (file, dependencies) => {
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
        translateCompiledElements(elements, evalJs, (file && file.sourceId) || "dynamic");
        return mkResult(elements, []);
    };
}
