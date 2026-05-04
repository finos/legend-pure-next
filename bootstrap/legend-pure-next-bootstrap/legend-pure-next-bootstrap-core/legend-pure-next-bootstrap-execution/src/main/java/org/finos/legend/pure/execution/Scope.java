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

package org.finos.legend.pure.execution;

import meta.pure.metamodel.valuespecification.ValueSpecification;

import java.util.Arrays;
import java.util.Map;

/**
 * Variable binding frame for the bootstrap interpreter.
 *
 * <p>Replaces the previous per-call {@code HashMap<String, ValueSpecification>}
 * approach. JFR found {@code HashMap} allocation + put/copy churn at ~22%
 * of self-compile CPU; the dominant cost was {@code new HashMap<>(parent)}
 * on every function call, which copied the entire parent scope.</p>
 *
 * <p>This Scope keeps only its <em>own</em> bindings and chains to a parent.
 * Variable lookup walks the chain, so the per-call allocation cost drops
 * from O(total visible vars) to O(local vars only). Local frames are
 * typically 1-10 entries (parameters + a few {@code let}s), so a linear
 * scan over parallel arrays beats a HashMap on both speed and allocation.</p>
 *
 * <p>Names are usually interned strings from the Pure IR — the
 * {@code n == name} fast-path short-circuits {@code equals} most of the
 * time.</p>
 */
public final class Scope
{
    private static final int INITIAL_CAPACITY = 4;

    private final Scope parent;
    private String[] names;
    private ValueSpecification[] values;
    private int size;

    public Scope()
    {
        this(null, INITIAL_CAPACITY);
    }

    public Scope(Scope parent)
    {
        this(parent, INITIAL_CAPACITY);
    }

    public Scope(Scope parent, int initialCapacity)
    {
        this.parent = parent;
        int cap = Math.max(initialCapacity, 1);
        this.names = new String[cap];
        this.values = new ValueSpecification[cap];
    }

    /**
     * Look up a binding, walking the parent chain. Returns {@code null}
     * if the name is not bound anywhere up the chain.
     */
    public ValueSpecification get(String name)
    {
        Scope s = this;
        while (s != null)
        {
            String[] ns = s.names;
            for (int i = s.size - 1; i >= 0; i--)
            {
                String n = ns[i];
                if (n == name || n.equals(name))
                {
                    return s.values[i];
                }
            }
            s = s.parent;
        }
        return null;
    }

    /**
     * Bind {@code name} in this frame. Updates the existing binding in
     * this frame if present, otherwise appends. Does not touch parent
     * frames — a {@code let} in a callee never overwrites a caller's
     * binding (the existing HashMap-based impl had the same semantic
     * via the per-call HashMap copy).
     */
    public void put(String name, ValueSpecification value)
    {
        for (int i = size - 1; i >= 0; i--)
        {
            String n = names[i];
            if (n == name || n.equals(name))
            {
                values[i] = value;
                return;
            }
        }
        if (size == names.length)
        {
            int cap = names.length * 2;
            names = Arrays.copyOf(names, cap);
            values = Arrays.copyOf(values, cap);
        }
        names[size] = name;
        values[size] = value;
        size++;
    }

    /**
     * Bulk put — used by callers that already have a {@code Map} of
     * bindings to inject (e.g. constraint type-variable binding).
     */
    public void putAll(Map<String, ValueSpecification> source)
    {
        if (source == null || source.isEmpty())
        {
            return;
        }
        for (Map.Entry<String, ValueSpecification> e : source.entrySet())
        {
            put(e.getKey(), e.getValue());
        }
    }

    /**
     * Index of {@code name} in this frame's local array, or {@code -1} if
     * not present. Only inspects local bindings (does not walk parents).
     * Used by the inline-cache in
     * {@link ValueSpecificationEvaluator}'s variable-read fast path.
     */
    public int localIndexOf(String name)
    {
        for (int i = size - 1; i >= 0; i--)
        {
            String n = names[i];
            if (n == name || n.equals(name))
            {
                return i;
            }
        }
        return -1;
    }

    /**
     * Number of local bindings in this frame (parent frames not counted).
     */
    public int localSize()
    {
        return size;
    }

    /**
     * Local name at the given slot. Caller must ensure {@code 0 <= slot < localSize()}.
     */
    public String localNameAt(int slot)
    {
        return names[slot];
    }

    /**
     * Local value at the given slot. Caller must ensure {@code 0 <= slot < localSize()}.
     */
    public ValueSpecification localValueAt(int slot)
    {
        return values[slot];
    }
}
