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
import java.util.Collections;
import java.util.List;

/**
 * Base of the generated implementations of the PDB reader's own Pure classes
 * (FbsNode, FbsSchema, ReadPointerRef, ...).
 *
 * <p>These are the READER'S WORKING VALUES, built by translated Pure code as it
 * walks a buffer. They are never PDB-backed and never handed to Pure, so there
 * is no lazy node, no parent chain and no runtime.</p>
 *
 * <p>Storage is a small HashMap whose values are the values THEMSELVES — a
 * single value is not wrapped in a one-element list. Both halves were measured
 * on the compiler suite: the original LinkedHashMap-of-ArrayList put
 * LinkedHashMap/Entry/Node[]/ArrayList at the top of the allocation profile,
 * and replacing the map with a linear scan over parallel arrays made lookup the
 * single hottest frame instead (indexOf, 4x the next entry). Dropping the list
 * wrapper is the part that paid; the map stays.</p>
 */
public abstract class LazyObject
{
    private final java.util.HashMap<String, Object> properties = new java.util.HashMap<>(8);

    /** The single value of a property, or null when unset. */
    protected final Object one(String property)
    {
        Object v = properties.get(property);
        if (v instanceof List<?> l)
        {
            return l.isEmpty() ? null : l.get(0);
        }
        return v;
    }

    /** Every value of a property, never null. */
    @SuppressWarnings("unchecked")
    protected final <T> List<T> many(String property)
    {
        Object v = properties.get(property);
        if (v == null)
        {
            return Collections.emptyList();
        }
        return v instanceof List<?> l ? (List<T>) l : (List<T>) Collections.singletonList(v);
    }

    /** Replace a property's values. A collection sets all of them. */
    protected final void set(String property, Object value)
    {
        properties.put(property, value);
    }

    /** Append to a property's values, promoting to a list on the second one. */
    protected final void add(String property, Object value)
    {
        Object current = properties.get(property);
        if (current == null)
        {
            properties.put(property, value);
            return;
        }
        if (current instanceof List<?>)
        {
            @SuppressWarnings("unchecked")
            List<Object> l = (List<Object>) current;
            if (value instanceof List<?> more) { l.addAll(more); } else { l.add(value); }
            return;
        }
        List<Object> promoted = new ArrayList<>();
        promoted.add(current);
        if (value instanceof List<?> more) { promoted.addAll(more); } else { promoted.add(value); }
        properties.put(property, promoted);
    }

    /** Copy every property into {@code target} — the generated `_copy()`. */
    protected final <T extends LazyObject> T copyInto(T target)
    {
        for (java.util.Map.Entry<String, Object> e : properties.entrySet())
        {
            Object v = e.getValue();
            ((LazyObject) target).properties.put(e.getKey(),
                    v instanceof List<?> l ? new ArrayList<>(l) : v);
        }
        return target;
    }
}
