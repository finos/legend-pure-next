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

/**
 * Per-element compilation timing breakdown across passes.
 *
 * @param elementPath           fully qualified element path (e.g. "meta::pure::functions::lang::if")
 * @param elementType           element classifier name (e.g. "Class", "Function", "Association")
 * @param firstPassNanos        time spent creating the element in the first pass
 * @param secondPassNanos       time spent resolving cross-references in the second pass
 * @param thirdPassNanos        time spent resolving function references in the third pass
 * @param inferenceRollbacks    number of inference rollbacks during this element's compilation
 * @param candidateEvaluations  number of function candidates evaluated during this element's compilation
 */
public record ElementStatistics(
        String elementPath,
        String elementType,
        long firstPassNanos,
        long secondPassNanos,
        long thirdPassNanos,
        int inferenceRollbacks,
        int candidateEvaluations)
{
    /**
     * Total compilation time for this element across all passes.
     */
    public long totalNanos()
    {
        return firstPassNanos + secondPassNanos + thirdPassNanos;
    }

    /**
     * Total compilation time in milliseconds.
     */
    public double totalMillis()
    {
        return totalNanos() / 1_000_000.0;
    }
}
