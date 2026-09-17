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

package org.finos.legend.pure.m3.module;

import org.eclipse.collections.api.list.MutableList;
import org.eclipse.collections.api.map.MutableMap;
import org.eclipse.collections.impl.factory.Lists;
import org.eclipse.collections.impl.factory.Maps;

/**
 * Aggregate and per-element compilation statistics.
 *
 * @param totalDurationNanos      wall-clock time for the entire compile() call
 * @param parsingDurationNanos    time spent parsing source files
 * @param firstPassDurationNanos  time spent in the first pass (element creation and indexing)
 * @param secondPassDurationNanos time spent in the second pass (cross-reference resolution)
 * @param thirdPassDurationNanos  time spent in the third pass (function resolution)
 * @param elementCount            total number of compiled elements
 * @param sourceFileCount         number of source files parsed
 * @param memoryDeltaBytes        heap memory change during compilation (approximate)
 * @param inferenceRollbackCount  number of inference rollbacks during function candidate selection
 * @param candidateEvaluationCount number of function candidates evaluated during resolution
 * @param elementStatistics       per-element timing breakdown keyed by element path
 */
public record CompilationStatistics(
        long totalDurationNanos,
        long parsingDurationNanos,
        long firstPassDurationNanos,
        long secondPassDurationNanos,
        long thirdPassDurationNanos,
        int elementCount,
        int sourceFileCount,
        long memoryDeltaBytes,
        int inferenceRollbackCount,
        int candidateEvaluationCount,
        MutableMap<String, ElementStatistics> elementStatistics,
        MutableMap<String, Integer> rollbackSites)
{
    /**
     * An empty statistics instance for modules that don't collect stats (e.g. PdbModule).
     */
    public static final CompilationStatistics EMPTY = new CompilationStatistics(
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, Maps.mutable.empty(), Maps.mutable.empty());

    /**
     * Sum several compilations' statistics (e.g. one per module): durations, counts and
     * rollback sites add up; per-element statistics are merged by element path.
     */
    public static CompilationStatistics combine(Iterable<CompilationStatistics> statistics)
    {
        long total = 0, parsing = 0, firstPass = 0, secondPass = 0, thirdPass = 0, memoryDelta = 0;
        int elements = 0, sourceFiles = 0, rollbacks = 0, candidates = 0;
        MutableMap<String, ElementStatistics> elementStatistics = Maps.mutable.empty();
        MutableMap<String, Integer> rollbackSites = Maps.mutable.empty();
        for (CompilationStatistics s : statistics)
        {
            if (s == null)
            {
                continue;
            }
            total += s.totalDurationNanos();
            parsing += s.parsingDurationNanos();
            firstPass += s.firstPassDurationNanos();
            secondPass += s.secondPassDurationNanos();
            thirdPass += s.thirdPassDurationNanos();
            elements += s.elementCount();
            sourceFiles += s.sourceFileCount();
            memoryDelta += s.memoryDeltaBytes();
            rollbacks += s.inferenceRollbackCount();
            candidates += s.candidateEvaluationCount();
            elementStatistics.putAll(s.elementStatistics());
            s.rollbackSites().forEachKeyValue((site, count) -> rollbackSites.merge(site, count, Integer::sum));
        }
        return new CompilationStatistics(total, parsing, firstPass, secondPass, thirdPass, elements, sourceFiles,
                memoryDelta, rollbacks, candidates, elementStatistics, rollbackSites);
    }

    /** The same statistics with a different total (e.g. wall-clock time around several compilations). */
    public CompilationStatistics withTotalDurationNanos(long nanos)
    {
        return new CompilationStatistics(nanos, parsingDurationNanos, firstPassDurationNanos, secondPassDurationNanos,
                thirdPassDurationNanos, elementCount, sourceFileCount, memoryDeltaBytes, inferenceRollbackCount,
                candidateEvaluationCount, elementStatistics, rollbackSites);
    }

    /**
     * Return a human-readable summary of the compilation statistics.
     */
    public String summary()
    {
        // Indented as a nested sub-section of the parent build block (no own
        // ==== underline — the parent banner already opened the block, and
        // the indent makes it visually clear this output belongs to it).
        StringBuilder sb = new StringBuilder();
        sb.append("  Compilation Statistics\n");
        sb.append(String.format("    Total time:       %8.2f ms\n", totalDurationNanos / 1_000_000.0));
        sb.append(String.format("      Parsing:        %8.2f ms\n", parsingDurationNanos / 1_000_000.0));
        sb.append(String.format("      First pass:     %8.2f ms\n", firstPassDurationNanos / 1_000_000.0));
        sb.append(String.format("      Second pass:    %8.2f ms\n", secondPassDurationNanos / 1_000_000.0));
        sb.append(String.format("      Third pass:     %8.2f ms\n", thirdPassDurationNanos / 1_000_000.0));
        sb.append(String.format("    Elements:         %8d\n", elementCount));
        sb.append(String.format("    Source files:     %8d\n", sourceFileCount));
        sb.append(String.format("    Memory delta:     %8.2f KB\n", memoryDeltaBytes / 1024.0));
        sb.append(String.format("    Inference rollbacks:     %d\n", inferenceRollbackCount));
        sb.append(String.format("    Candidate evaluations:   %d\n", candidateEvaluationCount));
        if (!elementStatistics.isEmpty())
        {
            sb.append("    Top 10 Elements by Compilation Time\n");
            MutableList<ElementStatistics> sorted = Lists.mutable.withAll(elementStatistics.values());
            sorted.sortThisBy(e -> -e.totalNanos());
            // %-20s on elementType fits "UserDefinedFunction" (19 chars) without
            // overflowing — keeps shorter labels (Class, Profile…) aligned on
            // the same rollbacks=/candidates= columns.
            sorted.take(10).forEach(e ->
                    sb.append(String.format("      %8.2f ms  %-20s  rollbacks=%-6d candidates=%-6d  %s\n",
                            e.totalMillis(), e.elementType(), e.inferenceRollbacks(), e.candidateEvaluations(), e.elementPath())));
        }
        if (!rollbackSites.isEmpty())
        {
            sb.append("    Rollback sites (count desc)\n");
            MutableList<java.util.Map.Entry<String, Integer>> entries = Lists.mutable.withAll(rollbackSites.entrySet());
            entries.sortThisBy(e -> -e.getValue());
            entries.forEach(e -> sb.append(String.format("      %8d  %s\n", e.getValue(), e.getKey())));
        }
        return sb.toString();
    }
}
