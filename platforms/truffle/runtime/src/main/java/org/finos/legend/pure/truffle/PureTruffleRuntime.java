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
import org.finos.legend.pure.execution.NativeExtension;
import org.finos.legend.pure.truffle.runtime.TruffleMetadataAccess;
import org.finos.legend.pure.truffle.ast.ConstantNode;
import org.finos.legend.pure.truffle.ast.PureNode;
import org.finos.legend.pure.truffle.builder.NativeNodeRegistry;
import org.finos.legend.pure.truffle.runtime.StandaloneEvaluatorHolder;
import org.finos.legend.pure.truffle.types.PureNull;

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
    private final StandaloneEvaluator standalone;
    private final TruffleMetadataAccess resolver;

    private PureTruffleRuntime(TruffleMetadataAccess resolver,
                               Iterable<? extends NativeExtension> nativeExtensions,
                               List<? extends ParserExtension> parserExtensions)
    {
        this.resolver = resolver;
        this.standalone = new StandaloneEvaluator(resolver, null, NativeNodeRegistry.createDefault(), null);

        // Build the PureParser with the standard Pure language parser + any extra extensions
        List<ParserExtension> allExtensions = new ArrayList<>();
        allExtensions.add(new PureLanguageParser());
        parserExtensions.forEach(allExtensions::add);
        this.standalone.setPureParser(PureParser.builder()
                .withExtensions(allExtensions)
                .build());
    }

    public static final class Builder
    {
        private TruffleMetadataAccess resolver;
        private final List<NativeExtension> nativeExtensions = new ArrayList<>();
        private final List<ParserExtension> parserExtensions = new ArrayList<>();

        public Builder withResolver(TruffleMetadataAccess resolver)
        {
            this.resolver = resolver;
            return this;
        }

        public Builder withNativeExtensions(Iterable<? extends NativeExtension> extensions)
        {
            if (extensions != null)
            {
                extensions.forEach(nativeExtensions::add);
            }
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
            return new PureTruffleRuntime(resolver, nativeExtensions, parserExtensions);
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
        StandaloneEvaluatorHolder.set(standalone);
        try
        {
            Object result = standalone.executeFunction(function, args);
            if (result instanceof PureNull)
            {
                return null;
            }
            return result;
        }
        finally
        {
            StandaloneEvaluatorHolder.clear();
        }
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
