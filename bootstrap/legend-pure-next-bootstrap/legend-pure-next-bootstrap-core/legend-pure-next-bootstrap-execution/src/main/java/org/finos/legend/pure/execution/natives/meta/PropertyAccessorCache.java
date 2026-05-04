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

package org.finos.legend.pure.execution.natives.meta;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-Class cache of {@code Method} objects used by reflective property
 * access. {@link MetaNatives} previously walked {@code getClass().getMethods()}
 * on every property read/write/copy — JFR found this responsible for ~98%
 * of {@code java.lang.reflect.Method} allocations during compiler-pure
 * self-compile (each {@code getMethods()} call returns a freshly-copied
 * {@code Method[]}).
 *
 * <p>Property setters can be overloaded (typically a typed {@code _foo(T)}
 * and a collection-typed {@code _foo(RichIterable&lt;T&gt;)}), so we cache
 * the candidate setter list rather than a single setter — call sites still
 * pick the right overload from the small array. Getters are no-arg, so a
 * single {@code Method} reference is enough.</p>
 */
final class PropertyAccessorCache
{
    private PropertyAccessorCache() {}

    private static final Method[] EMPTY = new Method[0];

    private static final ClassValue<Map<String, Method[]>> SETTERS = new ClassValue<>()
    {
        @Override
        protected Map<String, Method[]> computeValue(Class<?> type)
        {
            return new ConcurrentHashMap<>();
        }
    };

    private static final ClassValue<Map<String, Method>> GETTERS = new ClassValue<>()
    {
        @Override
        protected Map<String, Method> computeValue(Class<?> type)
        {
            return new ConcurrentHashMap<>();
        }
    };

    /**
     * Return all 1-arg public methods with the given name. Results are
     * cached per (Class, name); the first call enumerates the class's
     * full method table once, subsequent calls hit the map.
     */
    static Method[] settersFor(Class<?> cls, String name)
    {
        return SETTERS.get(cls).computeIfAbsent(name, n -> findSetters(cls, n));
    }

    private static Method[] findSetters(Class<?> cls, String name)
    {
        List<Method> result = new ArrayList<>(2);
        for (Method m : cls.getMethods())
        {
            if (m.getName().equals(name) && m.getParameterCount() == 1)
            {
                result.add(m);
            }
        }
        return result.isEmpty() ? EMPTY : result.toArray(EMPTY);
    }

    /**
     * Return the public no-arg method with the given name, or {@code null}
     * if none exists. The {@code null} case is also cached so repeated
     * misses don't keep paying for {@code NoSuchMethodException}.
     */
    static Method getterFor(Class<?> cls, String name)
    {
        Map<String, Method> map = GETTERS.get(cls);
        if (map.containsKey(name))
        {
            return map.get(name);
        }
        Method m;
        try
        {
            m = cls.getMethod(name);
        }
        catch (NoSuchMethodException e)
        {
            m = null;
        }
        map.put(name, m);
        return m;
    }
}
