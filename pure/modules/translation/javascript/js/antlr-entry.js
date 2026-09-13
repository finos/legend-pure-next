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
// lexer/parsers + the 17 ANTLR-bridge native impls, and exposes them on
// globalThis as `__parseAntlr`, `__getText`, … — the same names the
// translator emits for calls to `meta::pure::functions::meta::antlr::*`.
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


const g = globalThis;
g.__parseAntlr = __parseAntlr;
g.__getText = __getText;
g.__grammarRuleName = __grammarRuleName;
g.__getChild = __getChild;
g.__getChildren = __getChildren;
g.__getTopLevelChildren = __getTopLevelChildren;
g.__getChildTextAt = __getChildTextAt;
g.__getTokenText = __getTokenText;
g.__getTokenTexts = __getTokenTexts;
g.__hasChild = __hasChild;
g.__hasToken = __hasToken;
g.__getStartLine = __getStartLine;
g.__getStartColumn = __getStartColumn;
g.__getStopLine = __getStopLine;
g.__getStopColumn = __getStopColumn;
g.__stripTripleQuotesDedented = __stripTripleQuotesDedented;
g.__computeFirstNonNewlineLine = __computeFirstNonNewlineLine;


