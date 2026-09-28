// Copyright 2026 Goldman Sachs
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

package org.finos.legend.pure.platform.java.pdb;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * `resolveAndReturnGraph(elements)` — the compiler's pointer-resolution
 * boundary. The three compiler passes weave a `TempCompilerPointer` wherever an
 * element refers to another (`Class.package`, a generalization's `type`, a
 * `FunctionExpression.func`); this replaces every one of them with the live
 * element, and hands back a deep copy per map entry, in the map's order.
 *
 * <p>Two things are deliberately NOT copied: an element that already lives in a
 * PDB (it is the canonical instance — a clone of Integer is not Integer, and
 * `cast(@Integer)` would start failing), and an enum value (same reason, and
 * copying would lose its type). The input map and its objects are untouched.</p>
 */
public final class PointerGraph
{
    private static final String POINTER_PACKAGE = "meta::pure::metamodel::pointer::";

    private PointerGraph()
    {
    }

    public static List<Object> resolveAndReturnGraph(Object elements)
    {
        Map<?, ?> map = (Map<?, ?>) elements;
        // A pointer under a path is not a live element: resolving to it would
        // just hand back another pointer.
        Map<String, Object> byPath = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet())
        {
            if (entry.getKey() instanceof String && !isPointer(entry.getValue()))
            {
                byPath.put((String) entry.getKey(), entry.getValue());
            }
        }
        IdentityHashMap<Object, Object> copies = new IdentityHashMap<>();
        List<Object> resolved = new ArrayList<>(map.size());
        for (Map.Entry<?, ?> entry : map.entrySet())
        {
            resolved.add(walk(entry.getValue(), byPath, copies));
        }
        return resolved;
    }

    private static Object walk(Object value, Map<String, Object> byPath, IdentityHashMap<Object, Object> copies)
    {
        if (value == null || value instanceof String || value instanceof Number || value instanceof Boolean
                || value instanceof Character || value instanceof PureEnumValue || value instanceof Enum
                || value instanceof org.finos.legend.pure.platform.java.runtime.PureDate)
        {
            return value;
        }
        if (isPointer(value))
        {
            Object resolved = resolve((LazyObject) value, byPath, copies);
            if (resolved != null)
            {
                return resolved;
            }
            // An unresolvable pointer stays a pointer: a Property pointer keeps
            // its own `element` slot, and the root package has no element.
        }
        if (value instanceof Map)
        {
            return walkMap((Map<?, ?>) value, byPath, copies);
        }
        if (value instanceof List)
        {
            return walkList((List<?>) value, byPath, copies);
        }
        Object cached = copies.get(value);
        if (cached != null)
        {
            // Already being copied: the in-progress copy keeps a cycle finite.
            return cached;
        }
        if (!(value instanceof LazyObject))
        {
            // A runtime value (PureMap, a lambda): it holds no elements to rewrite.
            return value;
        }
        return walkObject((LazyObject) value, byPath, copies);
    }

    /** The live element a pointer names: the map being compiled first, then the metadata. */
    private static Object resolve(LazyObject pointer, Map<String, Object> byPath, IdentityHashMap<Object, Object> copies)
    {
        List<Object> paths = pointer.__values("path");
        if (paths.isEmpty() || !(paths.get(0) instanceof String) || ((String) paths.get(0)).isEmpty())
        {
            return null;
        }
        String path = (String) paths.get(0);
        Object inMap = byPath.get(path);
        if (inMap != null)
        {
            // An element of this same compilation: the pointer must reach that
            // element's COPY, not the original the caller still holds.
            return walk(inMap, byPath, copies);
        }
        Object live = Metadata.runtimeOrNull() == null ? null : Metadata.lenientElement(path);
        return isPointer(live) ? null : live;
    }

    private static Object walkObject(LazyObject value, Map<String, Object> byPath, IdentityHashMap<Object, Object> copies)
    {
        if (isCanonical(value, byPath))
        {
            return value;
        }
        LazyObject copy = copyOf(value);
        if (copy == null)
        {
            return value;
        }
        copies.put(value, copy);
        for (String property : new ArrayList<>(value._propertyNames()))
        {
            List<Object> slot = value.__values(property);
            List<Object> rewritten = walkList(slot, byPath, copies);
            if (rewritten != slot)
            {
                Metadata.set(copy, property, rewritten);
            }
        }
        return copy;
    }

    /** An element the metadata already holds under its own path: shared, never cloned. */
    private static boolean isCanonical(LazyObject value, Map<String, Object> byPath)
    {
        if (Metadata.runtimeOrNull() == null || value.__values("name").isEmpty())
        {
            return false;
        }
        String path = Metadata.path(value, "::");
        return !path.isEmpty() && !byPath.containsKey(path) && Metadata.lenientElement(path) == value;
    }

    private static List<Object> walkList(List<?> values, Map<String, Object> byPath, IdentityHashMap<Object, Object> copies)
    {
        List<Object> out = new ArrayList<>(values.size());
        boolean changed = false;
        for (Object value : values)
        {
            Object rewritten = walk(value, byPath, copies);
            changed = changed || rewritten != value;
            out.add(rewritten);
        }
        @SuppressWarnings("unchecked")
        List<Object> unchanged = (List<Object>) values;
        return changed ? out : unchanged;
    }

    private static Object walkMap(Map<?, ?> values, Map<String, Object> byPath, IdentityHashMap<Object, Object> copies)
    {
        Map<Object, Object> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : values.entrySet())
        {
            out.put(walk(entry.getKey(), byPath, copies), walk(entry.getValue(), byPath, copies));
        }
        return out;
    }

    /** `_copy()` — every generated implementation has one; a hand-written value may not. */
    private static LazyObject copyOf(LazyObject value)
    {
        try
        {
            return (LazyObject) value.getClass().getMethod("_copy").invoke(value);
        }
        catch (ReflectiveOperationException e)
        {
            return null;
        }
    }

    /**
     * A pointer the compiler left behind. A Property pointer carries its own
     * `element`, and is left alone — the JVM hosts do the same.
     */
    private static boolean isPointer(Object value)
    {
        return value instanceof LazyObject
                && ((LazyObject) value)._purePath().startsWith(POINTER_PACKAGE)
                && ((LazyObject) value).__values("element").isEmpty();
    }
}
