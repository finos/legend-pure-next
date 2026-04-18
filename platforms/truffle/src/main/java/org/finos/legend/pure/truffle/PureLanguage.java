// Copyright 2024 Goldman Sachs
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

package org.finos.legend.pure.truffle;

import com.oracle.truffle.api.TruffleLanguage;

/**
 * Truffle language registration for Pure.
 *
 * <p>This is a minimal scaffolding registration. We don't currently parse
 * source strings via this language (Pure source is parsed by the Java ANTLR
 * front-end in {@code legend-pure-next-parser}). Instead callers invoke
 * {@link PureTruffleRuntime#execute} with a {@code FunctionDefinition}
 * loaded from a PDB and the runtime builds Truffle ASTs on demand.</p>
 *
 * <p>Registering a language is still useful:
 * <ul>
 *   <li>Gives us a {@link PureContext} lifecycle that Graal understands</li>
 *   <li>Enables {@code native-image --language:pure} packaging</li>
 *   <li>Opens the door to a future Polyglot front-end (Phase post-G)</li>
 * </ul>
 * </p>
 */
@TruffleLanguage.Registration(
        id = PureLanguage.ID,
        name = "Pure",
        defaultMimeType = PureLanguage.MIME_TYPE,
        characterMimeTypes = PureLanguage.MIME_TYPE,
        contextPolicy = TruffleLanguage.ContextPolicy.SHARED,
        website = "https://legend.finos.org/"
)
public final class PureLanguage extends TruffleLanguage<PureContext>
{
    public static final String ID = "pure";
    public static final String MIME_TYPE = "application/x-pure";

    @Override
    protected PureContext createContext(Env env)
    {
        return new PureContext(this, env);
    }

    /**
     * Lookup the current {@link PureContext} from inside an AST node.
     */
    public static PureContext currentContext()
    {
        return com.oracle.truffle.api.TruffleLanguage.ContextReference
                .create(PureLanguage.class)
                .get(null);
    }
}
