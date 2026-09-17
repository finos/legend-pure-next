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

// The ANTLR natives (meta::pure::functions::meta::antlr::*) as a NativesExtension:
// parse with a named grammar and walk the resulting parse tree. They are implemented
// by the ANTLR bundle (pure/modules/translation/javascript/js/build/antlr-bundle.js),
// a classic script exposing them as the `PureAntlr` namespace. Hosts load that script
// after building the runtime, so each hook finds its implementation on first call.

import { ANTLR_NATIVES } from "./native-signatures.js";

function antlrBundle() {
    const bundle = globalThis.PureAntlr;
    if (!bundle) throw new Error("the ANTLR bundle is not loaded (antlr-bundle.js)");
    return bundle;
}

/** Host natives for meta::pure::functions::meta::antlr. */
export const antlrExtension = {
    registerAll(natives) {
        for (const [name, signature] of ANTLR_NATIVES) {
            natives.register(signature, () => {
                let impl = null;
                return (...args) => (impl ??= antlrBundle()[name])(...args);
            });
        }
    },
};
