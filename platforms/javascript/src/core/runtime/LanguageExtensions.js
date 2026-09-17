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

// Language extensions for the JavaScript host: the parser half of a language, one per
// `###Section` (docs/superpowers/specs/2026-09-13-unified-host-api-design.md §3b/§3c).
// On JavaScript a section's parser is Pure code — a `Pair<String, parser>` the
// translated parser-mappings `parseDocument` dispatches on — and its compiler half is
// compiler-pure, so an extension contributes its section parsers:
//
//   { name, sectionParsers(runtime) → Pair<String, parser>[] }
//
// PureRuntime.parse asks every registered extension at parse time, so parsers that
// arrive with generated JavaScript loaded after the runtime was built still count.

// `###Pure`: the translated parser-mappings interpreter (parser-mappings-bundle.js).
const PURE_SECTION_PARSER = "meta$pure$parser$mappings$interpreter$pureSectionParser__Pair_1_";
// compiler-pure's test sections (`###CompiledGraph`, `###Error`, …), as Truffle's
// TrufflePureParser registers them.
const TEST_SECTION_PARSERS = "meta$pure$compiler$test$testSectionParsers__Pair_MANY_";
const TEST_SECTION_PARSERS_ELEMENT = "meta::pure::compiler::test::testSectionParsers__Pair_MANY_";

const asArray = (v) => (v === undefined || v === null ? [] : Array.isArray(v) ? v : [v]);

/** The `###Pure` section. */
export const pureLanguageExtension = {
    name: "Pure",
    sectionParsers() {
        const parser = globalThis[PURE_SECTION_PARSER];
        if (typeof parser !== "function") {
            throw new Error("the Pure section parser is not loaded (parser-mappings-bundle.js)");
        }
        return [parser()];
    },
};

/** compiler-pure's test sections, when compiler-tests is registered and its translated code is loaded. */
export const compilerTestSectionsExtension = {
    name: "compiler test sections",
    sectionParsers(runtime) {
        const parsers = globalThis[TEST_SECTION_PARSERS];
        if (typeof parsers !== "function" || !runtime.registry.hasElement(TEST_SECTION_PARSERS_ELEMENT)) return [];
        return asArray(parsers());
    },
};

export const DEFAULT_LANGUAGE_EXTENSIONS = [pureLanguageExtension, compilerTestSectionsExtension];
