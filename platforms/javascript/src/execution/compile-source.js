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
// globals) plus the two injected capabilities — the registry (for dependency
// validation) and the host's evalJs (Node uses vm.runInThisContext, the
// browser indirect eval).
//
// runtime-lib's __compileSource routes here: parse happens at the call site
// (the native takes an already-parsed PureFile), we compile with the
// TRANSLATED compiler (metadata reads flow through those globals to the
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
 *
 * ── TWO COMPILE MODES ────────────────────────────────────────────────────────
 * Compiling happens in two places and they differ in ONE respect: whether the
 * compiled elements join the graph.
 *
 *   RUNTIME (this function) — `compileSource` called FROM Pure code. Isolated:
 *     translate and eval so the code is invocable, but leave the graph alone.
 *     PCT pins it:
 *     meta::pure::functions::meta::tests::compileSource::testCompileSourceDoesNotMutatePlatformGraph
 *     asserts pathToElement on a compiled path still fails afterwards.
 *
 *   PLATFORM (translateProgramElements below) — a host compiling the program
 *     it is about to run, e.g. the editor page. Here the compiled elements ARE
 *     the graph, so they are indexed into the registry's in-memory module and
 *     reflection over them resolves (`x::Test.properties.name`).
 *
 * Keep them as two named entry points: the difference is a contract, not a flag
 * a caller should have to remember to pass.
 */
export function translateCompiledElements(elements, evalJs, sourceId = "dynamic") {
    return translateElements(elements, evalJs, sourceId, null);
}

/**
 * PLATFORM mode: translate, eval, AND index each element into `memModule` (the
 * registry's in-memory module) so just-compiled elements are addressable by
 * reflection, not merely invocable as functions. Paths are already computed
 * here for __purePath, so indexing costs a Map.set per element.
 *
 * Only for a host whose compile IS the program. Never from the compileSource
 * native — see the contract note above.
 */
export function translateProgramElements(elements, evalJs, sourceId = "program", memModule = null) {
    return translateElements(elements, evalJs, sourceId, memModule);
}

function translateElements(elements, evalJs, sourceId, memModule) {
    const emitted = [];
    for (const el of elements) {
        if (!el || typeof el !== "object") continue;
        const src = globalThis[TRANSLATE_ELEMENT](el);
        el.__purePath = globalThis.__elementToPath(el);
        memModule?.addElement?.(el.__purePath, el);
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
        // RUNTIME mode — isolated by contract (see translateCompiledElements).
        translateCompiledElements(elements, evalJs, (file && file.sourceId) || "dynamic");
        return mkResult(elements, []);
    };
}
