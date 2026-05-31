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

package org.finos.legend.pure.next.parser.mappings;

import org.finos.legend.pure.execution.DynamicInstance;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Renders a protocol-grammar value to a canonical multi-line string that's
 * identical regardless of whether the value came from the production
 * .dsl-derived parser (a typed POJO from {@code meta.pure.protocol.grammar.*})
 * or from the Pure interpreter (a {@code DynamicInstance}).
 *
 * <p>Slot iteration is sorted alphabetically by slot name so the two pipelines
 * — which set slots in different orders — produce the same string. Empty
 * slots (null or empty list) are omitted to avoid asymmetric noise between
 * production-side `null` and Pure-side `[]`.</p>
 *
 * <p>Used by {@link MappingsInterpreterValidatorTest} to assert structural
 * equivalence across the full grammar-tests fixture corpus.</p>
 */
final class ProtocolPrinter
{
    private static final String INDENT = "  ";
    private static final int MAX_DEPTH = 40;
    // Runtime / metamodel slots present on generated Impls that aren't part of
    // the protocol shape we're comparing. Skipped to avoid recursing into the
    // metamodel (causing stack overflow) and to keep the diff focused.
    // Skips are restricted to Java/Pure runtime artifacts that are NOT declared
    // in m3_protocol.pure. Every real protocol slot is compared strictly — that
    // is the validator's whole job. Adding a real protocol field here would
    // silently mask production-vs-interpreter divergence (we learned this the
    // hard way with `aggregation` / `genericType` / `multiplicity` on enum
    // values; the fix was to fill the slots on both sides, not skip the diff).
    private static final Set<String> SKIPPED_SLOTS = new TreeSet<>(List.of(
            "classifierGenericType",   // metamodel ref — cycles into the protocol class definition
            "elementOverride",         // runtime support; not a protocol field
            "id",                      // generated runtime ID; not stable
            "copy"                     // _copy() is the protocol POJOs' clone method (infinite recursion)
    ));

    private ProtocolPrinter() {}

    static String print(Object value)
    {
        StringBuilder sb = new StringBuilder();
        append(value, sb, 0, Collections.newSetFromMap(new IdentityHashMap<>()));
        return sb.toString();
    }

    private static void append(Object value, StringBuilder sb, int indent, Set<Object> visited)
    {
        if (indent > MAX_DEPTH) { sb.append("<max-depth>"); return; }
        if (value == null)
        {
            sb.append("null");
            return;
        }
        if (value instanceof List<?> list)
        {
            if (list.isEmpty()) { sb.append("[]"); return; }
            for (Object item : list)
            {
                sb.append('\n').append(INDENT.repeat(indent + 1));
                append(item, sb, indent + 1, visited);
            }
            return;
        }
        if (value instanceof DynamicInstance di)
        {
            if (!visited.add(di)) { sb.append("<cycle>"); return; }
            sb.append(simpleNameOf(di.getClassPath()));
            // Sort by key so production-order vs interpreter-order doesn't diff.
            Map<String, Object> sorted = new TreeMap<>(di.getValues());
            for (Map.Entry<String, Object> e : sorted.entrySet())
            {
                if (SKIPPED_SLOTS.contains(e.getKey())) continue;
                if (isEmpty(e.getValue())) continue;
                if (isDefaultValue(e.getKey(), e.getValue())) continue;
                sb.append('\n').append(INDENT.repeat(indent + 1)).append(e.getKey()).append(": ");
                append(asListIfObject(e.getValue()), sb, indent + 1, visited);
            }
            return;
        }
        if (value.getClass().getName().startsWith("meta.pure.protocol."))
        {
            if (!visited.add(value)) { sb.append("<cycle>"); return; }
            String javaCls = value.getClass().getSimpleName();
            if (javaCls.endsWith("Impl")) javaCls = javaCls.substring(0, javaCls.length() - 4);
            sb.append(javaCls);
            // Collect slots via `_propName()` getters across the full interface chain,
            // dedupe by name (Pure-side slot name, not the Java getter), sort.
            Map<String, Method> getters = new TreeMap<>();
            collectGetters(value.getClass(), getters, Collections.newSetFromMap(new IdentityHashMap<>()));
            for (Map.Entry<String, Method> g : getters.entrySet())
            {
                if (SKIPPED_SLOTS.contains(g.getKey())) continue;
                Object slotVal;
                try { slotVal = g.getValue().invoke(value); }
                catch (Exception ex) { continue; }
                if (isEmpty(slotVal)) continue;
                if (isDefaultValue(g.getKey(), slotVal)) continue;
                sb.append('\n').append(INDENT.repeat(indent + 1)).append(g.getKey()).append(": ");
                append(asListIfObject(slotVal), sb, indent + 1, visited);
            }
            return;
        }
        if (value instanceof String s)
        {
            sb.append('\'').append(s).append('\'');
            return;
        }
        sb.append(value);
    }

    private static boolean isEmpty(Object v)
    {
        if (v == null) return true;
        if (v instanceof List<?> l && l.isEmpty()) return true;
        return false;
    }

    /**
     * Slots where a specific value matches the protocol's natural default —
     * production code leaves the slot null and the printer skips it; Pure
     * requires it as [1] and our code emits the default explicitly.
     * Emitting a matching value here is equivalent to "not set" for diff.
     */
    private static boolean isDefaultValue(String slot, Object v)
    {
        return "contravariant".equals(slot) && Boolean.FALSE.equals(v);
    }

    /**
     * Production's `_propName()` for *-cardinality slots always returns a
     * {@code List<X>}; DynamicInstance.get() unwraps singleton collections
     * back to the bare item. To make print output align regardless of the
     * pipeline, wrap any single object (protocol POJO or DynamicInstance)
     * in a singleton list before recursing. Scalars (String, Long, Boolean)
     * remain bare — they're [1]-cardinality and never list-wrapped on either
     * side.
     */
    private static Object asListIfObject(Object v)
    {
        if (v instanceof List<?>) return v;
        if (v instanceof DynamicInstance) return List.of(v);
        if (v != null && v.getClass().getName().startsWith("meta.pure.protocol.")) return List.of(v);
        return v;
    }

    private static String simpleNameOf(String classPath)
    {
        if (classPath == null) return "?";
        int i = classPath.lastIndexOf("::");
        return i < 0 ? classPath : classPath.substring(i + 2);
    }

    private static void collectGetters(Class<?> cls, Map<String, Method> out, Set<Class<?>> visited)
    {
        if (cls == null || !visited.add(cls)) return;
        for (Class<?> iface : cls.getInterfaces())
        {
            for (Method m : iface.getDeclaredMethods())
            {
                if (m.getParameterCount() == 0 && m.getName().startsWith("_") && m.getName().length() > 1)
                {
                    String slot = m.getName().substring(1);
                    // Keep the most-specific override per slot name (first-seen wins
                    // because we descend interface-first).
                    out.putIfAbsent(slot, m);
                }
            }
            collectGetters(iface, out, visited);
        }
        collectGetters(cls.getSuperclass(), out, visited);
    }
}
