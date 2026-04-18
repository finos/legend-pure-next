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
import meta.pure.metamodel.function.FunctionDefinition;
import meta.pure.metamodel.valuespecification.ValueSpecification;
import meta.pure.metamodel.valuespecification.VariableExpression;
import org.finos.legend.pure.execution.NativeExtension;
import org.finos.legend.pure.execution.NativeRepository;
import org.finos.legend.pure.execution._E_ValueSpecification;
import org.finos.legend.pure.m3.module.MetadataAccess;
import org.finos.legend.pure.truffle.ast.ConstantNode;
import org.finos.legend.pure.truffle.ast.PureNode;
import org.finos.legend.pure.truffle.runtime.EvaluatorHolder;

import java.util.ArrayList;
import java.util.List;

import org.finos.legend.pure.next.parser.ParserExtension;

/**
 * Plain Java entry point for running Pure code through the Truffle interpreter.
 *
 * <p>Mirrors {@code PureExecution}'s Builder pattern. Bypasses the Polyglot
 * Context boundary: we don't embed Pure as a language; we just use Truffle
 * as an AST/execution runtime.</p>
 */
public final class PureTruffleRuntime
{
    private final TruffleEvaluator evaluator;
    private final StandaloneEvaluator standalone;
    private final MetadataAccess resolver;

    private PureTruffleRuntime(MetadataAccess resolver,
                               Iterable<? extends NativeExtension> nativeExtensions,
                               List<? extends ParserExtension> parserExtensions)
    {
        this.resolver = resolver;
        NativeRepository natives = NativeRepository.builder()
                .withResolver(resolver)
                .withParserExtensions(parserExtensions)
                .withNativeExtensions(nativeExtensions)
                .build();
        this.evaluator = new TruffleEvaluator(natives, null);
        // StandaloneEvaluator shares the same NativeNodeRegistry as TruffleEvaluator.
        // Bridge fallback for ~31 remaining signatures uses EvaluatorHolder → TruffleEvaluator.
        this.standalone = new StandaloneEvaluator(resolver, null, evaluator.astBuilder().specialized(), natives);
    }

    public static final class Builder
    {
        private MetadataAccess resolver;
        private final List<NativeExtension> nativeExtensions = new ArrayList<>();
        private final List<ParserExtension> parserExtensions = new ArrayList<>();

        public Builder withResolver(MetadataAccess resolver)
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
        EvaluatorHolder.set(evaluator);
        org.finos.legend.pure.truffle.runtime.StandaloneEvaluatorHolder.set(standalone);
        try
        {
            Object result = standalone.executeFunction(function, args);
            if (result instanceof meta.pure.metamodel.valuespecification.ValueSpecification vs)
            {
                return _E_ValueSpecification.unwrap(vs);
            }
            if (result instanceof org.finos.legend.pure.truffle.types.PureNull)
            {
                return null;
            }
            return result;
        }
        finally
        {
            org.finos.legend.pure.truffle.runtime.StandaloneEvaluatorHolder.clear();
            EvaluatorHolder.clear();
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
