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

package org.finos.legend.pure.truffle.ast.natives.collection;

import org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.valuespecification.Collection;
import org.finos.legend.pure.truffle.types.ObjectSequence;
import org.finos.legend.pure.truffle.types.PureSequence;

/**
 * Boundary-free collection helpers. Every method is inlineable by
 * Collections, no {@code ValueSpecification} allocation.
 *
 * <p>Callers iterate via {@link #size} + {@link #at} instead of
 * materializing lists. Results are collected into {@code Object[]} and
 * wrapped as {@link ObjectSequence}.</p>
 */
public final class CollectionHelper
{
    private CollectionHelper()
    {
    }

    public static int size(Object v)
    {
        if (v == PureSequence.EMPTY || v == null)
        {
            return 0;
        }
        if (v instanceof PureSequence seq)
        {
            return seq.size();
        }
        if (v instanceof java.util.List<?> list)
        {
            return list.size();
        }
        // Single value (including Collection treated as object)
        return 1;
    }

    public static boolean isEmpty(Object v)
    {
        return size(v) == 0;
    }

    public static Object at(Object v, int i)
    {
        if (v instanceof PureSequence seq)
        {
            return seq.getBoxed(i);
        }
        if (v instanceof java.util.List<?> list)
        {
            return list.get(i);
        }
        // Single object at index 0
        return v;
    }

    /**
     * Collect elements of {@code v} into a fresh {@code Object[]}.
     * No boundaries — uses {@link #size} + {@link #at}.
     */
    public static Object[] toArray(Object v)
    {
        int sz = size(v);
        Object[] arr = new Object[sz];
        for (int i = 0; i < sz; i++)
        {
            arr[i] = at(v, i);
        }
        return arr;
    }
}
