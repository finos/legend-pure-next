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
 * Truffle language registration for Pure.
 *
 * <p>Configuration is passed via {@link #configure(TruffleMetadataAccess, NativeNodeRegistry)}
 * before the polyglot {@code Context} is created. The {@link #createContext} method
 * consumes the pending configuration and builds the {@link PureContext}.</p>
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

    // One-shot configuration bridge — set before Context.create(), consumed by createContext()
    private static volatile PendingConfig pendingConfig;

    /**
     * Configure the Pure language before creating a polyglot Context.
     * The configuration is consumed by {@link #createContext} and cleared.
     */
    public static void configure(TruffleMetadataAccess resolver, NativeNodeRegistry registry)
    {
        pendingConfig = new PendingConfig(resolver, registry);
    }

    @Override
    protected PureContext createContext(Env env)
    {
        PendingConfig config = pendingConfig;
        pendingConfig = null;
        PureContext ctx = new PureContext(this, env);
        if (config != null)
        {
            ctx.initialize(config.resolver, config.registry);
        }
        return ctx;
    }

    @Override
    protected boolean patchContext(PureContext context, Env newEnv)
    {
        // Support context pre-initialization in native image.
        // The context was created at build time; patch with runtime env.
        return true;
    }


    private static final ContextReference<PureContext> CONTEXT_REF =
            ContextReference.create(PureLanguage.class);

    /**
     * Lookup the current {@link PureContext} from inside an AST node.
     */
    public static PureContext get(com.oracle.truffle.api.nodes.Node node)
    {
        return CONTEXT_REF.get(node);
    }

    private record PendingConfig(TruffleMetadataAccess resolver, NativeNodeRegistry registry) {}
}
