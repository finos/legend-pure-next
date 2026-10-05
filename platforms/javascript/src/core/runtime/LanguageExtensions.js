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

// Language extensions for the JavaScript host: BOTH halves of a language, one per `###Section`
// (docs/superpowers/specs/2026-09-13-unified-host-api-design.md §3b/§3c).
//
// Both halves are Pure code — a `Pair<String, parser>` the translated parser-mappings
// `parseDocument` dispatches on, and a `CompilerExtension` the translated compiler-pure dispatches
// on — so an extension contributes the two of them and nothing else:
//
//   { name, sectionParsers(runtime) → Pair<String, parser>[],
//           compilerExtensions(runtime) → CompilerExtension[] }
//
// NOTHING HERE NAMES A LANGUAGE. Every extension under pure/extensions comes from the generated
// descriptors (generated/extensions/*/language_<name>.js, built from each `language_<name>.json`), so adding a language is a
// directory on disk and not an edit to this file. `###Pure` and compiler-pure's test sections are
// written out below because they are not extensions: they ship inside core and compiler-pure.
//
// PureRuntime asks every registered extension at parse and compile time, so a half that arrives
// with generated JavaScript loaded after the runtime was built still counts — which is what lets an
// extension's module be registered late.

import { LANGUAGES, PURE_LANGUAGE_PDBS } from "../../../generated/languages.js";

const asArray = (v) => (v === undefined || v === null ? [] : Array.isArray(v) ? v : [v]);
/** A Pure element path to the name its translation is installed under. */
const mangled = (path) => path.split("::").join("$");

/**
 * THE MODULES THE PURE LANGUAGE ITSELF NEEDS — mandatory, on every host, before any source can be
 * compiled or run. Re-exported from the generated core descriptor, so the list lives in
 * pure/specification/language_pure.json beside the language rather than in this host.
 */
export { PURE_LANGUAGE_PDBS };

/**
 * One language extension, from its generated descriptor.
 *
 * Each half is guarded on its own Pure element being registered AND its translation being loaded,
 * so an extension whose module is not loaded contributes nothing rather than throwing. That is
 * deliberate — it is what lets the module arrive late — and it is why ModuleRegistry.validate()
 * checks that a registered extension's module is actually there: a silently inert extension is a
 * bad error to debug.
 */
export function languageExtensionFor(descriptor) {
    const half = (elementPath) => (runtime) => {
        if (!elementPath) return [];
        const fn = globalThis[mangled(elementPath)];
        if (typeof fn !== "function") {
            // A BUNDLED language's parser is not optional: `###Pure` arrives through
            // parser-mappings-bundle.js, so a missing one is a broken host, not an absent feature.
            if (descriptor.bundled) {
                throw new Error(`the ${descriptor.name} section parser is not loaded `
                    + "(parser-mappings-bundle.js)");
            }
            return [];
        }
        // Guarded on the element being REGISTERED, so an extension whose module has not been loaded
        // contributes nothing rather than throwing — that is what lets a module arrive late. A
        // bundled language has no module to check against.
        if (!descriptor.bundled && !runtime.registry.hasElement(elementPath)) return [];
        return asArray(fn());
    };
    // `pure` is the manifest's own block, re-exported verbatim by the generated descriptor — so a
    // key added to language_<name>.json arrives here without the generator or this file changing.
    const pure = descriptor.pure ?? {};
    const sectionParsers = half(pure.sectionParsers);
    const compilerExtensions = half(pure.compilerExtension);
    return {
        name: descriptor.name,
        descriptor,
        sectionParsers,
        compilerExtensions,
    };
}

// What separates an extension from a language that ships with the compiler: `dir`, and the paths the
// generator derives from it (pdbPath, jsPaths). Nothing else does, which is why one baked list serves
// both and this file filters rather than importing two.
const isExtension = (language) => Boolean(language.dir);

/** Every extension found under pure/extensions, as language extensions. */
export const extensionLanguages = LANGUAGES.filter(isExtension).map(languageExtensionFor);

/** The descriptors themselves — what a host passes to ModuleRegistry.registerExtension. */
export const EXTENSION_DESCRIPTORS = LANGUAGES.filter(isExtension);

/** The extension named `name`, for a host that wants one rather than all of them. */
export const extensionNamed = (name) => extensionLanguages.find((e) => e.name === name) ?? null;

/** The languages that ship with the compiler — `###Pure` and compiler-pure's test sections. */
export const coreLanguages = LANGUAGES.filter((l) => !isExtension(l)).map(languageExtensionFor);

// Core languages first — `###Pure` has to be registered before anything can be parsed — then every
// extension found on disk. NOTHING HERE NAMES A LANGUAGE: both halves of this list come from
// manifests (pure/specification/language_*.json and pure/extensions/*/language_<name>.json),
// aggregated into one generated list.
export const DEFAULT_LANGUAGE_EXTENSIONS = [...coreLanguages, ...extensionLanguages];

/** The language named `name` — core or extension alike, since nothing distinguishes them here. */
export const languageNamed = (name) => DEFAULT_LANGUAGE_EXTENSIONS.find((l) => l.name === name) ?? null;
