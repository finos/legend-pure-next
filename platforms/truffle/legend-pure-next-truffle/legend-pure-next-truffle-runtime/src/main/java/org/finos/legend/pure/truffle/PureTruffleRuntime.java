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
import org.finos.legend.pure.truffle.ast.AtomicValueNode;
import org.finos.legend.pure.truffle.ast.PureNode;
import org.finos.legend.pure.truffle.ast.PureSourceHelper;
import org.finos.legend.pure.truffle.builder.NativeNodeRegistry;
import org.finos.legend.pure.truffle.types.PureSequence;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

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
    private final org.graalvm.polyglot.Engine engine;
    private final ByteArrayOutputStream graalLog;
    private final PureContext context;
    private final TruffleMetadataAccess resolver;

    private PureTruffleRuntime(TruffleMetadataAccess resolver,
                               List<? extends ParserExtension> parserExtensions,
                               List<Path> sourceRoots,
                               Map<String, String> polyglotOptions)
    {
        this.resolver = resolver;

        // Register source roots before any AST is built — once a Source is
        // cached for a sourceId, later root additions don't update its content.
        for (Path root : sourceRoots)
        {
            PureSourceHelper.addSourceRoot(root);
        }

        // Build a shared Engine with the Graal compilation tripwire wired in:
        //   - TraceCompilation logs every `opt done` / `opt failed` event
        //   - err is redirected into a buffer we scan in graalCompilationFailures()
        //   - CompileImmediately forces every reached function through Tier-1
        //     so the tripwire surfaces bailouts in code paths the suite calls
        //     just once (Truffle's default lazy threshold ~10 invocations
        //     would otherwise hide them).
        // Skipped under native-image AOT — the runtime never JITs there, so
        // there are no compilation events and no risk of "Too deep inlining"
        // bailouts at all.
        if (org.graalvm.nativeimage.ImageInfo.inImageRuntimeCode())
        {
            this.graalLog = null;
            this.engine = null;
        }
        else
        {
            this.graalLog = new ByteArrayOutputStream();
            org.graalvm.polyglot.Engine.Builder engineBuilder = org.graalvm.polyglot.Engine.newBuilder()
                    .allowExperimentalOptions(true)
                    .err(new PrintStream(graalLog, true, StandardCharsets.UTF_8))
                    .option("engine.TraceCompilation", "true")
                    .option("engine.WarnInterpreterOnly", "false")
                    .option("engine.CompilationFailureAction", "Silent")
                    .option("engine.CompileImmediately", "true");
            // Instrument options (cpusampler.*, pureprofiler.*) and explicit
            // engine.* overrides must go on the Engine, not the Context — when
            // a Context shares an Engine, instrument-scope options can only
            // be set when the Engine is built. Routing them here lets
            // `--cpu-sampler` / `--pure-profiler` flags actually take effect.
            for (Map.Entry<String, String> e : polyglotOptions.entrySet())
            {
                if (isEngineOption(e.getKey()))
                {
                    engineBuilder.option(e.getKey(), e.getValue());
                }
            }
            this.engine = engineBuilder.build();
        }

        // Configure and create the Truffle polyglot context
        PureLanguage.configure(resolver, NativeNodeRegistry.createDefault());
        org.graalvm.polyglot.Context.Builder ctxBuilder = org.graalvm.polyglot.Context.newBuilder(PureLanguage.ID)
                .allowAllAccess(true)
                .allowExperimentalOptions(true);
        if (engine != null)
        {
            ctxBuilder.engine(engine);
        }
        for (Map.Entry<String, String> e : polyglotOptions.entrySet())
        {
            if (!isEngineOption(e.getKey()))
            {
                ctxBuilder.option(e.getKey(), e.getValue());
            }
        }
        this.polyglotContext = ctxBuilder.build();
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

    private static boolean isEngineOption(String key)
    {
        // Engine-level instruments and engine.* options must be configured
        // when the Engine is built; setting them on a Context that shares
        // an Engine throws an IllegalArgumentException.
        return key.startsWith("engine.")
                || key.startsWith("cpusampler")
                || key.startsWith("pureprofiler");
    }

    /**
     * Close the underlying polyglot context (and the shared Engine, if any).
     * Triggers any attached engine tools (e.g. {@code cpusampler}) to flush
     * their output, and flushes any in-flight Graal compilations to the
     * captured err buffer so {@link #graalCompilationFailures()} sees them.
     */
    public void close()
    {
        try
        {
            polyglotContext.leave();
        }
        catch (RuntimeException ignored)
        {
            // already left or never entered
        }
        polyglotContext.close();
        if (engine != null)
        {
            engine.close();
        }
    }

    /**
     * Lines from the captured Graal err stream that match {@code opt failed}.
     * Empty under native-image AOT (no JIT events at all).
     *
     * <p>Callers should invoke {@link #close()} first so in-flight Graal
     * compilations have flushed their events into the buffer.</p>
     */
    public List<String> graalCompilationFailures()
    {
        if (graalLog == null)
        {
            return List.of();
        }
        return graalLog.toString(StandardCharsets.UTF_8).lines()
                .filter(line -> line.contains("opt failed"))
                .collect(Collectors.toList());
    }

    /**
     * Total Graal compilation events ({@code opt done} + {@code opt failed})
     * recorded during this runtime's lifetime. 0 under native-image AOT.
     */
    public long graalCompilationAttempts()
    {
        if (graalLog == null)
        {
            return 0;
        }
        return graalLog.toString(StandardCharsets.UTF_8).lines()
                .filter(line -> line.contains("opt done") || line.contains("opt failed"))
                .count();
    }

    public static final class Builder
    {
        private TruffleMetadataAccess resolver;
        private final List<ParserExtension> parserExtensions = new ArrayList<>();
        private final List<Path> sourceRoots = new ArrayList<>();
        private final Map<String, String> polyglotOptions = new LinkedHashMap<>();

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

        /**
         * Register a directory whose contents should be embedded in Truffle
         * {@link com.oracle.truffle.api.source.Source} objects when their
         * sourceId resolves under it. Required for {@code --cpu-sampler}
         * flamegraphs to render Pure source lines.
         */
        public Builder withSourceRoot(Path root)
        {
            if (root != null)
            {
                this.sourceRoots.add(root);
            }
            return this;
        }

        /**
         * Pass through a polyglot engine option, e.g.
         * {@code option("cpusampler", "true")}.
         */
        public Builder withPolyglotOption(String key, String value)
        {
            this.polyglotOptions.put(key, value);
            return this;
        }

        public PureTruffleRuntime build()
        {
            return new PureTruffleRuntime(resolver, parserExtensions, sourceRoots, polyglotOptions);
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
        PureNode body = new AtomicValueNode(42L);
        RootNode root = new PureRootNode(null, "hello", FrameDescriptor.newBuilder().build(), body);
        return root.getCallTarget().call();
    }
}
