// ©2026 JP Morgan Chase & Co. All rights reserved.
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

package org.finos.legend.pure.truffle.compiler.module.pdbModule.hooks;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * The collection functions the generated PDB reader calls — Truffle's own copies of the Java platform's runtime
 * library methods (platforms/java/…/runtime/PureCollections), kept here so Truffle depends on no other platform.
 */
@SuppressWarnings({"rawtypes", "unchecked"})
public final class PureCollections
{
    private PureCollections()
    {
    }

    /** {@code map(coll, f)}: the results concatenated — a to-many result flattened, an empty one (null) dropped. */
    public static List map(List<?> values, Function f)
    {
        List<Object> out = new ArrayList<>();
        for (Object v : values)
        {
            Object r = f.apply(v);
            if (r instanceof List)
            {
                out.addAll((List<?>) r);
            }
            else if (r != null)
            {
                out.add(r);
            }
        }
        return out;
    }

    /** {@code at(coll, idx)}: the element at a zero-based offset, with Pure's message when there is none. */
    public static Object at(List<?> values, long index)
    {
        if (index < 0 || index >= values.size())
        {
            throw new RuntimeException("The system is trying to get an element at offset " + index + " where the collection is of size " + values.size());
        }
        return values.get((int) index);
    }

    public static List range(long end)
    {
        return range(0L, end, 1L);
    }

    public static List range(long start, long end)
    {
        return range(start, end, 1L);
    }

    /** {@code range(start, end, step)}: towards {@code end}, exclusive, by {@code step}; a negative step counts down. */
    public static List range(long start, long end, long step)
    {
        if (step == 0L)
        {
            throw new IllegalArgumentException("range step must not be 0");
        }
        List<Object> out = new ArrayList<>();
        for (long i = start; step > 0L ? i < end : i > end; i += step)
        {
            out.add(i);
        }
        return out;
    }
}
