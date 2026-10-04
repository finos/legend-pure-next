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

// esbuild --bundle entry: pulls in antlr4's runtime + the generated JS
// lexer/parsers + the 17 ANTLR-bridge native impls, and exports them — built with
// --global-name=PureAntlr, the bundle exposes them as `globalThis.PureAntlr`. The
// JavaScript PureRuntime installs them as the `__parseAntlr`, `__getText`, … hooks the
// translator emits for `meta::pure::functions::meta::antlr::*` (AntlrExtension).
//
// The bundle output (build/antlr-bundle.js) is a single no-imports IIFE so
// it runs unchanged in any host without module resolution AND in the
// browser (where any module bundler can include it as a side-effect script
// before loading translated parser-mappings code).
//
// PARSER HALF ONLY - deliberately free of any Pure/PDB dependency, so it can
// be built from just `pnpm install` + the antlr4 jar + specification/grammar/
// antlr/*.g4. That is what lets the extension jar be packaged before any PDB
// exists (CI builds the jar in truffle-self-host, upstream of the javascript
// PDBs). The translated parser-mappings interpreter and `__pureParseTop` live
// in the companion bundle: parser-mappings-entry.js -> parser-mappings-bundle.js.

import {
    __parseAntlr,
    __getText,
    __grammarRuleName,
    __getChild,
    __getChildren,
    __getTopLevelChildren,
    __getChildTextAt,
    __getTokenText,
    __getTokenTexts,
    __hasChild,
    __hasToken,
    __getStartLine,
    __getStartColumn,
    __getStopLine,
    __getStopColumn,
    __stripTripleQuotesDedented,
    __computeFirstNonNewlineLine,
} from "./antlr-natives.js";


export {
    __parseAntlr as parseAntlr,
    __getText as getText,
    __grammarRuleName as grammarRuleName,
    __getChild as getChild,
    __getChildren as getChildren,
    __getTopLevelChildren as getTopLevelChildren,
    __getChildTextAt as getChildTextAt,
    __getTokenText as getTokenText,
    __getTokenTexts as getTokenTexts,
    __hasChild as hasChild,
    __hasToken as hasToken,
    __getStartLine as getStartLine,
    __getStartColumn as getStartColumn,
    __getStopLine as getStopLine,
    __getStopColumn as getStopColumn,
    __stripTripleQuotesDedented as stripTripleQuotesDedented,
    __computeFirstNonNewlineLine as computeFirstNonNewlineLine,
};
