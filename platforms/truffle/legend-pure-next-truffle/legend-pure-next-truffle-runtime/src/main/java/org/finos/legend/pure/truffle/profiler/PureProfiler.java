// Copyright 2026 Goldman Sachs
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

package org.finos.legend.pure.truffle.profiler;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.instrumentation.TruffleInstrument;
import org.graalvm.options.OptionCategory;
import org.graalvm.options.OptionDescriptor;
import org.graalvm.options.OptionDescriptors;
import org.graalvm.options.OptionKey;
import org.graalvm.options.OptionStability;

import java.io.PrintWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Per-Pure-function profiler — a {@link TruffleInstrument} whose
 * lifecycle exposes a static {@code enter}/{@code exit} pair that the
 * Pure {@code RootNode} subclasses call directly.
 *
 * <h2>Why not Truffle's polyglot CPU sampler?</h2>
 * <p>Under libgraal in our JVMCI configuration the polyglot {@code
 * cpusampler} reports "Recorded 0 samples with N missed" — the sampler
 * thread can't safely interrupt the main thread mid-Graal-compilation.
 * This instrument records on the recording thread itself in {@code
 * onEnter}/{@code onReturn} hooks, so it works regardless of JIT state.</p>
 *
 * <h2>Why not {@code attachExecutionEventListener}?</h2>
 * <p>That API requires the listener-targeted nodes to declare a tag
 * ({@code RootTag}, etc.) and to be {@link
 * com.oracle.truffle.api.instrumentation.InstrumentableNode}s. Pure's
 * {@code RootNode} subclasses aren't instrumentable today; making them so
 * needs a {@code @GenerateWrapper}-emitted wrapper per class. Direct
 * call-in is simpler and avoids any per-node wrapper allocations.</p>
 *
 * <h2>Overhead when disabled</h2>
 * <p>{@code enter}/{@code exit} short-circuit on the {@code volatile
 * boolean} {@link #ENABLED} flag — when the profiler isn't attached, both
 * hooks are a single field load + branch (which JIT folds away). When
 * enabled, each hook does a {@code System.nanoTime} + a {@link
 * ConcurrentHashMap} {@code computeIfAbsent} (amortised O(1)) + a couple
 * of atomic adds.</p>
 *
 * <h2>Self-time vs total-time</h2>
 * <p>Per-thread call stack tracks {@code childNanos} on each frame; on
 * exit we attribute {@code elapsed} as total-time and {@code elapsed -
 * childNanos} as self-time, then add {@code elapsed} to the parent
 * frame's {@code childNanos}. Matches the semantics most language CPU
 * samplers use for "Self Time" / "Total Time" in their histograms.</p>
 */
@TruffleInstrument.Registration(
        id = PureProfiler.ID,
        name = "Pure Profiler",
        services = PureProfiler.class
)
public final class PureProfiler extends TruffleInstrument
{
    public static final String ID = "pureprofiler";

    static final OptionKey<Boolean> ENABLED_OPT = new OptionKey<>(false);
    static final OptionKey<String> OUTPUT_FILE = new OptionKey<>("");
    static final OptionKey<Integer> TOP = new OptionKey<>(50);

    /**
     * Set to true when an instrument instance is enabled. Read by the
     * {@code enter}/{@code exit} hot paths to short-circuit when no
     * instrument is attached. {@code @CompilationFinal} so PE can
     * constant-fold the {@code if (!ENABLED) return;} branch out of
     * every Pure-function entry/exit when the profiler is off (the
     * common case). The paired {@link #ENABLED_DISABLED} assumption
     * is invalidated when the flag flips, forcing recompile of any
     * Truffle code that baked in the off path.
     */
    @com.oracle.truffle.api.CompilerDirectives.CompilationFinal
    private static boolean ENABLED;

    /**
     * Held while {@link #ENABLED} is {@code false}. PE folds the
     * profiler hooks away while this assumption holds; flipping the
     * profiler on calls {@link Assumption#invalidate()} and recompiles
     * affected RootNodes.
     */
    static final com.oracle.truffle.api.Assumption ENABLED_DISABLED =
            com.oracle.truffle.api.Assumption.create("PureProfiler disabled");
    private static volatile PureProfiler ACTIVE;

    static final class Stats
    {
        final AtomicLong invocations = new AtomicLong();
        final AtomicLong totalNanos = new AtomicLong();
        final AtomicLong selfNanos = new AtomicLong();
    }

    static final class Frame
    {
        final String name;
        final long enterNanos;
        long childNanos;

        Frame(String name, long enterNanos)
        {
            this.name = name;
            this.enterNanos = enterNanos;
        }
    }

    private final ConcurrentHashMap<String, Stats> stats = new ConcurrentHashMap<>();
    private final ThreadLocal<Deque<Frame>> stack = ThreadLocal.withInitial(ArrayDeque::new);
    private Env env;

    /**
     * Hot-path entry hook. Call from {@code RootNode.execute} on the way
     * in. Pass the function name (typically {@code root.getName()}); the
     * exit hook reads the same name off the stack.
     */
    public static void enter(String name)
    {
        if (ENABLED_DISABLED.isValid())
        {
            return;
        }
        recordEnter(name);
    }

    /**
     * Hot-path exit hook. Call from {@code RootNode.execute} on the way
     * out (regardless of whether the body returned normally or threw).
     */
    public static void exit()
    {
        if (ENABLED_DISABLED.isValid())
        {
            return;
        }
        recordExit();
    }

    @TruffleBoundary
    private static void recordEnter(String name)
    {
        PureProfiler p = ACTIVE;
        if (p == null)
        {
            return;
        }
        p.stack.get().push(new Frame(name != null ? name : "<anonymous>", System.nanoTime()));
    }

    @TruffleBoundary
    private static void recordExit()
    {
        PureProfiler p = ACTIVE;
        if (p == null)
        {
            return;
        }
        Deque<Frame> s = p.stack.get();
        if (s.isEmpty())
        {
            return;
        }
        Frame f = s.pop();
        long elapsed = System.nanoTime() - f.enterNanos;
        if (!s.isEmpty())
        {
            s.peek().childNanos += elapsed;
        }
        Stats st = p.stats.computeIfAbsent(f.name, k -> new Stats());
        st.invocations.incrementAndGet();
        st.totalNanos.addAndGet(elapsed);
        st.selfNanos.addAndGet(elapsed - f.childNanos);
    }

    @Override
    protected void onCreate(Env env)
    {
        env.registerService(this);
        this.env = env;
        if (env.getOptions().get(ENABLED_OPT))
        {
            ACTIVE = this;
            ENABLED = true;
            // Invalidates compiled Truffle code that baked in the
            // "profiler disabled" PE constant — next compile will see
            // ENABLED=true and emit the recordEnter/recordExit calls.
            ENABLED_DISABLED.invalidate();
        }
    }

    @Override
    protected void onDispose(Env env)
    {
        if (ACTIVE != this)
        {
            return;
        }
        ENABLED = false;
        ACTIVE = null;

        String outFile = env.getOptions().get(OUTPUT_FILE);
        int top = env.getOptions().get(TOP);
        try
        {
            Writer w;
            boolean closeWriter = false;
            if (outFile == null || outFile.isEmpty())
            {
                w = new java.io.OutputStreamWriter(env.out(), StandardCharsets.UTF_8);
            }
            else
            {
                Path p = Path.of(outFile);
                if (p.getParent() != null)
                {
                    Files.createDirectories(p.getParent());
                }
                w = Files.newBufferedWriter(p, StandardCharsets.UTF_8);
                closeWriter = true;
            }
            try (PrintWriter pw = new PrintWriter(w))
            {
                writeHistogram(pw, top);
                pw.flush();
            }
            finally
            {
                if (closeWriter)
                {
                    try
                    {
                        w.close();
                    }
                    catch (Exception ignored)
                    {
                    }
                }
            }
        }
        catch (Exception e)
        {
            try
            {
                env.err().write(("PureProfiler: failed to write report: " + e.getMessage() + "\n").getBytes(StandardCharsets.UTF_8));
            }
            catch (java.io.IOException ignored)
            {
            }
        }
    }

    private void writeHistogram(PrintWriter pw, int top)
    {
        List<Map.Entry<String, Stats>> entries = new ArrayList<>(stats.entrySet());
        entries.sort(Comparator.comparingLong((Map.Entry<String, Stats> e) -> e.getValue().selfNanos.get()).reversed());

        long totalSelfNs = 0;
        long totalInvocations = 0;
        for (Map.Entry<String, Stats> e : entries)
        {
            totalSelfNs += e.getValue().selfNanos.get();
            totalInvocations += e.getValue().invocations.get();
        }

        pw.println("Pure Profiler — per-function CPU time");
        pw.println("================================================================================");
        pw.printf("Total Pure self-time: %.2f ms across %d invocations%n",
                totalSelfNs / 1_000_000.0, totalInvocations);
        pw.println();
        pw.printf("%-12s %-12s %-8s %-12s %s%n",
                "Self ms", "Total ms", "Self %", "Calls", "Function");
        pw.println("--------------------------------------------------------------------------------");

        int shown = 0;
        for (Map.Entry<String, Stats> e : entries)
        {
            if (shown++ >= top)
            {
                break;
            }
            Stats st = e.getValue();
            long self = st.selfNanos.get();
            long total = st.totalNanos.get();
            long calls = st.invocations.get();
            double selfPct = totalSelfNs > 0 ? (100.0 * self / totalSelfNs) : 0.0;
            pw.printf("%-12.2f %-12.2f %-8.2f %-12d %s%n",
                    self / 1_000_000.0, total / 1_000_000.0, selfPct, calls, e.getKey());
        }
        if (entries.size() > top)
        {
            pw.printf("... %d more entries (use pureprofiler.Top=%d to show all)%n",
                    entries.size() - top, entries.size());
        }
    }

    @Override
    protected OptionDescriptors getOptionDescriptors()
    {
        OptionDescriptor enabled = OptionDescriptor.newBuilder(ENABLED_OPT, ID)
                .help("Enable the Pure profiler")
                .category(OptionCategory.USER)
                .stability(OptionStability.EXPERIMENTAL)
                .build();
        OptionDescriptor output = OptionDescriptor.newBuilder(OUTPUT_FILE, ID + ".OutputFile")
                .help("File to write the histogram to (default: stdout)")
                .category(OptionCategory.USER)
                .stability(OptionStability.EXPERIMENTAL)
                .build();
        OptionDescriptor topN = OptionDescriptor.newBuilder(TOP, ID + ".Top")
                .help("Number of top-N entries to show in the histogram (default: 50)")
                .category(OptionCategory.USER)
                .stability(OptionStability.EXPERIMENTAL)
                .build();
        return new OptionDescriptors()
        {
            @Override
            public OptionDescriptor get(String key)
            {
                if (ID.equals(key)) return enabled;
                if ((ID + ".OutputFile").equals(key)) return output;
                if ((ID + ".Top").equals(key)) return topN;
                return null;
            }

            @Override
            public java.util.Iterator<OptionDescriptor> iterator()
            {
                return List.of(enabled, output, topN).iterator();
            }
        };
    }
}
