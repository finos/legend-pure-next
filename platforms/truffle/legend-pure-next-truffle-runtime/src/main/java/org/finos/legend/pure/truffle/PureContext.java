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
import org.finos.legend.pure.truffle.builder.NativeNodeRegistry;
import org.finos.legend.pure.truffle.runtime.TruffleMetadataAccess;

/**
 * Per-context state for the Pure Truffle language.
 * Holds the evaluator, resolver, and all runtime services.
 */
public final class PureContext
{
    private final PureLanguage language;
    private final TruffleLanguage.Env env;
    private StandaloneEvaluator evaluator;

    public PureContext(PureLanguage language, TruffleLanguage.Env env)
    {
        this.language = language;
        this.env = env;
    }

    /**
     * Initialize the context with runtime configuration.
     * Called from {@link PureLanguage#createContext} with the pending configuration.
     */
    void initialize(TruffleMetadataAccess resolver, NativeNodeRegistry registry)
    {
        this.evaluator = new StandaloneEvaluator(resolver, language, registry, null);
    }

    public PureLanguage getLanguage()
    {
        return language;
    }

    public TruffleLanguage.Env getEnv()
    {
        return env;
    }

    public StandaloneEvaluator getEvaluator()
    {
        return evaluator;
    }

    public TruffleMetadataAccess resolver()
    {
        return evaluator != null ? evaluator.resolver() : null;
    }
}
