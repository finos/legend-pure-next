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
 * Per-context state for the Pure Truffle language.
 *
 * <p>Minimal in Phase A: just holds a reference to the environment. Later
 * phases will add:
 * <ul>
 *   <li>{@code TruffleMetadataAccess resolver} — element lookup from loaded PDBs</li>
 *   <li>{@code NativeRepository natives} — bridged native function registry</li>
 *   <li>{@code ASTCache astCache} — {@code FunctionDefinition} → {@code CallTarget}</li>
 * </ul>
 * </p>
 */
public final class PureContext
{
    private final PureLanguage language;
    private final TruffleLanguage.Env env;

    public PureContext(PureLanguage language, TruffleLanguage.Env env)
    {
        this.language = language;
        this.env = env;
    }

    public PureLanguage getLanguage()
    {
        return language;
    }

    public TruffleLanguage.Env getEnv()
    {
        return env;
    }
}
