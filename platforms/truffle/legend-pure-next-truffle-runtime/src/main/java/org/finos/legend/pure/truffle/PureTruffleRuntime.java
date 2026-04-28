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

import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.nodes.RootNode;
import org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.function.FunctionDefinition;
import org.finos.legend.pure.truffle.runtime.TruffleMetadataAccess;
import org.finos.legend.pure.truffle.ast.ConstantNode;
import org.finos.legend.pure.truffle.ast.PureNode;
import org.finos.legend.pure.truffle.builder.NativeNodeRegistry;
import org.finos.legend.pure.truffle.types.PureSequence;

import java.util.ArrayList;
import java.util.List;

import org.finos.legend.pure.next.parser.ParserExtension;
import org.finos.legend.pure.next.parser.PureParser;
import org.finos.legend.pure.next.parser.m3.PureLanguageParser;

/**
 * Plain Java entry point for running Pure code through the Truffle interpreter.
 *
 * <p>Mirrors {@code PureExecution}'s Builder pattern. Bypasses the Polyglot
 * Context boundary: we don't embed Pure as a language; we just use Truffle
 * as an AST/execution runtime.</p>
 */
public final class PureTruffleRuntime
{
    private final org.graalvm.polyglot.Context polyglotContext;
    private final PureContext context;
    private final TruffleMetadataAccess resolver;

    private PureTruffleRuntime(TruffleMetadataAccess resolver,
                               List<? extends ParserExtension> parserExtensions)
    {
        this.resolver = resolver;

        // Configure and create the Truffle polyglot context
        PureLanguage.configure(resolver, NativeNodeRegistry.createDefault());
        this.polyglotContext = org.graalvm.polyglot.Context.newBuilder(PureLanguage.ID)
                .allowAllAccess(true)
                .build();
        this.polyglotContext.initialize(PureLanguage.ID);
        this.polyglotContext.enter();

        // Get the PureContext created by the language
        this.context = PureLanguage.get(null);

        // Build the PureParser with the standard Pure language parser + any extra extensions
        List<ParserExtension> allExtensions = new ArrayList<>();
        allExtensions.add(new PureLanguageParser());
        parserExtensions.forEach(allExtensions::add);
        this.context.setPureParser(PureParser.builder()
                .withExtensions(allExtensions)
                .build());
    }

    public static final class Builder
    {
        private TruffleMetadataAccess resolver;
        private final List<ParserExtension> parserExtensions = new ArrayList<>();

        public Builder withResolver(TruffleMetadataAccess resolver)
        {
            this.resolver = resolver;
            return this;
        }

        public Builder withParserExtensions(Iterable<? extends ParserExtension> extensions)
        {
            if (extensions != null)
            {
                extensions.forEach(parserExtensions::add);
            }
            return this;
        }

        public PureTruffleRuntime build()
        {
            return new PureTruffleRuntime(resolver, parserExtensions);
        }
    }

    public static Builder builder()
    {
        return new Builder();
    }

    /**
     * Execute a compiled {@link FunctionDefinition} with the given raw Java args.
     * Uses StandaloneEvaluator as primary path. TruffleEvaluator stays on
     * EvaluatorHolder for BridgedNativeCallNode fallback on remaining bridge
     * signatures.
     */
    public Object execute(FunctionDefinition function, Object... args)
    {
        Object result = context.executeFunction(function, args);
        if (result instanceof PureSequence ps && ps.isEmpty())
        {
            return null;
        }
        return result;
    }

    /**
     * Phase A smoke test — compiles and runs a constant-returning RootNode.
     */
    public static Object hello()
    {
        PureNode body = new ConstantNode(42L);
        RootNode root = new PureRootNode(null, "hello", FrameDescriptor.newBuilder().build(), body);
        return root.getCallTarget().call();
    }
}
