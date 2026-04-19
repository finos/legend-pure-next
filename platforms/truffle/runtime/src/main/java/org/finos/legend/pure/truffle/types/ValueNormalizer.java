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

package org.finos.legend.pure.truffle.types;

import java.util.ArrayList;
import java.util.List;

/**
 * Converts Truffle-specific runtime types ({@link PureSequence},
 * {@link PureNull}) into standard Java types ({@link List}, {@code null},
 * raw primitives) that
 * {@link org.finos.legend.pure.execution.PureValuePrinter} understands.
 *
 * <p>Call {@link #normalize(Object)} before passing values to
 * PureValuePrinter to avoid garbled output like "ObjectSequence[...]".</p>
 */
public final class ValueNormalizer
{
    private ValueNormalizer()
    {
    }

    /**
     * Normalize a Truffle runtime value for printing:
     * <ul>
     *   <li>{@link PureNull} &rarr; {@code null}</li>
     *   <li>{@link PureSequence} &rarr; {@link List} (recursively normalized)</li>
     *   <li>Everything else &rarr; returned as-is</li>
     * </ul>
     */
    public static Object normalize(Object value)
    {
        if (value == null || value instanceof PureNull)
        {
            return null;
        }
        if (value instanceof PureSequence seq)
        {
            int sz = seq.size();
            List<Object> list = new ArrayList<>(sz);
            for (int i = 0; i < sz; i++)
            {
                list.add(normalize(seq.getBoxed(i)));
            }
            return list;
        }
        return value;
    }
}
