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

package org.finos.legend.pure.execution;

import meta.pure.metamodel.PackageableElement;
import meta.pure.metamodel.SimplePropertyOwner;
import meta.pure.metamodel.function.property.Property;
import meta.pure.metamodel.relationship.Generalization;
import meta.pure.metamodel.type.Any;
import meta.pure.metamodel.type.Type;
import meta.pure.metamodel.type.generics.GenericTypeValue;
import pure.annotations.Excluded;
import pure.annotations.Inferred;
import pure.annotations.Pointer;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Prints a Pure runtime value by traversing the metaclass property graph.
 *
 * <p>For a metamodel instance (e.g. a {@code ClassImpl} or {@code PropertyImpl}):
 * <ol>
 *   <li>Looks up the metaclass via {@code _classifierGenericType()._type()}</li>
 *   <li>Collects all properties from the metaclass and its full super-type chain</li>
 *   <li>For each property, invokes the corresponding {@code _name()} getter and renders
 *       the value, indenting recursively</li>
 * </ol>
 *
 * <p>Properties annotated {@code @Inferred} or {@code @Excluded} are skipped.
 * Properties annotated {@code @Pointer} are shown as a compact path to break cycles.
 */
public class PureValuePrinter
{
    private static final String INDENT = "  ";

    /**
     * Hard cap on object-graph recursion depth. The Pure {@code print} native
     * has a {@code max} arg but historically it's been used inconsistently;
     * this cap is the structural safety net. Even with cycle detection via
     * {@code visited}, a chain of distinct objects (e.g. a deeply nested
     * AST) can blow the stack — this bounds the worst case.
     */
    private static final int MAX_DEPTH = 50;

    private PureValuePrinter()
    {
    }

    // =========================================================================
    // Public entry point
    // =========================================================================

    /**
     * Formats a value for user-visible output (println/print natives).
     * Strings are emitted without surrounding quotes; other values use the structural format.
     */
    public static String printForOutput(Object value)
    {
        if (value instanceof String s)
        {
            return s;
        }
        if (value instanceof List<?> list)
        {
            StringBuilder sb = new StringBuilder();
            for (Object item : list)
            {
                if (sb.length() > 0)
                {
                    sb.append('\n');
                }
                sb.append(printForOutput(item));
            }
            return sb.toString();
        }
        return print(value);
    }

    public static String print(Object value)
    {
        StringBuilder sb = new StringBuilder();
        Set<Object> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        append(value, sb, 0, visited);
        return sb.toString();
    }

    // =========================================================================
    // Core dispatch
    // =========================================================================

    private static void append(Object value, StringBuilder sb, int indent, Set<Object> visited)
    {
        if (indent > MAX_DEPTH)
        {
            // Defensive cap on object-graph traversal. Cycle detection handles
            // back-edges but a chain of distinct objects (e.g. a deeply nested
            // AST) can still bottom out the stack without it.
            sb.append("<max-depth>");
            return;
        }
        if (value == null)
        {
            sb.append("null");
        }
        else if (value instanceof List<?> list)
        {
            appendList(list, sb, indent, visited);
        }
        else if (value instanceof DynamicInstance di)
        {
            appendDynamic(di, sb, indent, visited);
        }
        else if (value instanceof Any any)
        {
            appendMetamodel(any, sb, indent, visited);
        }
        else if (value instanceof String s)
        {
            sb.append('\'').append(s).append('\'');
        }
        else
        {
            sb.append(value);
        }
    }

    // =========================================================================
    // Metamodel instances
    // =========================================================================

    private static void appendMetamodel(Any any, StringBuilder sb, int indent, Set<Object> visited)
    {
        if (!visited.add(any))
        {
            sb.append("<cycle: ").append(shortLabel(any)).append('>');
            return;
        }

        // Header: ClassName [name if available]
        sb.append(metaclassName(any));
        String label = tryName(any);
        if (label != null)
        {
            sb.append(' ').append(label);
        }

        // Collect properties from the metaclass hierarchy
        List<Property> props = collectProperties(any);
        for (Property prop : props)
        {
            String propName = prop._name();
            Method getter = findGetter(any.getClass(), propName);
            if (getter == null)
            {
                continue;
            }
            if (getter.isAnnotationPresent(Excluded.class) || getter.isAnnotationPresent(Inferred.class))
            {
                continue;
            }

            Object propValue;
            try
            {
                propValue = getter.invoke(any);
            }
            catch (Exception ignored)
            {
                continue;
            }

            if (propValue == null)
            {
                continue;
            }
            if (propValue instanceof List<?> list && list.isEmpty())
            {
                continue;
            }

            sb.append('\n').append(INDENT.repeat(indent + 1)).append(propName).append(": ");

            if (getter.isAnnotationPresent(Pointer.class))
            {
                // Pointer: show compact path only — do not recurse
                appendPointer(propValue, sb);
            }
            else
            {
                append(propValue, sb, indent + 1, visited);
            }
        }
    }

    // =========================================================================
    // Dynamic (user-defined) instances
    // =========================================================================

    private static void appendDynamic(DynamicInstance di, StringBuilder sb, int indent, Set<Object> visited)
    {
        if (!visited.add(di))
        {
            sb.append("<cycle: ").append(shortSegment(di.getClassPath())).append('>');
            return;
        }
        // Header is just the class's simple name — no per-instance Anonymous_NNN
        // tag. The tag varies per process (sequential ID counter), making
        // output non-reproducible across runs and divergent from Truffle.
        sb.append(shortSegment(di.getClassPath()));
        di.getValues().forEach((key, val) ->
        {
            if (val == null)
            {
                return;
            }
            if (val instanceof List<?> l && l.isEmpty())
            {
                return;
            }
            sb.append('\n').append(INDENT.repeat(indent + 1)).append(key).append(": ");
            append(val, sb, indent + 1, visited);
        });
    }

    // =========================================================================
    // Lists
    // =========================================================================

    private static void appendList(List<?> list, StringBuilder sb, int indent, Set<Object> visited)
    {
        if (list.isEmpty())
        {
            sb.append("[]");
            return;
        }
        for (Object item : list)
        {
            sb.append('\n').append(INDENT.repeat(indent + 1));
            append(item, sb, indent + 1, visited);
        }
    }

    // =========================================================================
    // Pointer (compact path reference)
    // =========================================================================

    private static void appendPointer(Object value, StringBuilder sb)
    {
        if (value instanceof List<?> list)
        {
            if (list.isEmpty())
            {
                sb.append("[]");
            }
            else
            {
                sb.append('[');
                boolean first = true;
                for (Object item : list)
                {
                    if (!first) { sb.append(", "); }
                    first = false;
                    sb.append(pointerPath(item));
                }
                sb.append(']');
            }
        }
        else
        {
            sb.append(pointerPath(value));
        }
    }

    private static String pointerPath(Object value)
    {
        if (value instanceof PackageableElement pe)
        {
            return buildPath(pe);
        }
        return String.valueOf(value);
    }

    // =========================================================================
    // Property collection via metaclass hierarchy
    // =========================================================================

    /**
     * Collect all properties from the instance's metaclass and its full super-type chain.
     * Uses a LinkedHashSet so each property name appears once (most-specific wins).
     */
    private static List<Property> collectProperties(Any instance)
    {
        meta.pure.metamodel.type.generics.GenericType cgt = instance._classifierGenericType();
        if (!(cgt instanceof GenericTypeValue gtv))
        {
            return List.of();
        }
        Type metaclass = gtv._type();
        if (metaclass == null)
        {
            return List.of();
        }

        Set<String> seen = new LinkedHashSet<>();
        List<Property> result = new ArrayList<>();
        collectPropertiesFrom(metaclass, seen, result, Collections.newSetFromMap(new IdentityHashMap<>()));
        return result;
    }

    private static void collectPropertiesFrom(Type type, Set<String> seen, List<Property> result,
                                              Set<Type> visitedTypes)
    {
        if (!visitedTypes.add(type))
        {
            return;
        }
        if (type instanceof SimplePropertyOwner spo)
        {
            for (Property p : spo._properties())
            {
                String name = p._name();
                if (name != null && seen.add(name))
                {
                    result.add(p);
                }
            }
        }
        // Recurse into super-types
        if (type instanceof Type t)
        {
            for (Generalization g : t._generalizations())
            {
                meta.pure.metamodel.type.generics.GenericType general = g._general();
                if (general instanceof GenericTypeValue gtv && gtv._type() != null)
                {
                    collectPropertiesFrom(gtv._type(), seen, result, visitedTypes);
                }
            }
        }
    }

    // =========================================================================
    // Reflection helpers
    // =========================================================================

    /**
     * Find the zero-arg getter {@code _propName()} in any interface of the class hierarchy.
     * Annotations live on interface methods, so we must search interfaces.
     */
    private static Method findGetter(java.lang.Class<?> cls, String propName)
    {
        String methodName = "_" + propName;
        return findGetterInInterfaces(cls, methodName, Collections.newSetFromMap(new IdentityHashMap<>()));
    }

    private static Method findGetterInInterfaces(java.lang.Class<?> cls, String methodName,
                                                  Set<java.lang.Class<?>> visited)
    {
        if (cls == null || !visited.add(cls))
        {
            return null;
        }
        for (java.lang.Class<?> iface : cls.getInterfaces())
        {
            for (Method m : iface.getDeclaredMethods())
            {
                if (m.getName().equals(methodName) && m.getParameterCount() == 0)
                {
                    return m;
                }
            }
            Method found = findGetterInInterfaces(iface, methodName, visited);
            if (found != null)
            {
                return found;
            }
        }
        return findGetterInInterfaces(cls.getSuperclass(), methodName, visited);
    }

    // =========================================================================
    // Label / path helpers
    // =========================================================================

    private static String metaclassName(Any any)
    {
        meta.pure.metamodel.type.generics.GenericType cgt = any._classifierGenericType();
        if (cgt instanceof GenericTypeValue gtv && gtv._type() instanceof PackageableElement pe)
        {
            return pe._name() != null ? pe._name() : any.getClass().getSimpleName().replace("Impl", "");
        }
        return any.getClass().getSimpleName().replace("Impl", "");
    }

    private static String tryName(Any any)
    {
        if (any instanceof PackageableElement pe)
        {
            return pe._name();
        }
        return null;
    }

    private static String shortLabel(Any any)
    {
        if (any instanceof PackageableElement pe && pe._name() != null)
        {
            return pe._name();
        }
        return any.getClass().getSimpleName().replace("Impl", "");
    }

    private static String buildPath(PackageableElement pe)
    {
        String name = pe._name();
        if (pe._package() == null)
        {
            return name != null ? name : "?";
        }
        String parent = buildPath(pe._package());
        return "Root".equals(parent) ? name : parent + "::" + name;
    }

    private static String shortSegment(String path)
    {
        if (path == null)
        {
            return "?";
        }
        int idx = path.lastIndexOf("::");
        return idx >= 0 ? path.substring(idx + 2) : path;
    }
}
