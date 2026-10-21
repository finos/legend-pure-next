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

package org.finos.legend.pure.platform.java.runtime;

import org.finos.legend.pure.platform.java.compiler.module.pdbModule.Metadata;
import org.finos.legend.pure.platform.java.execution.Execute;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Pure's {@code memoize(f, args...)}: {@code f->eval(args...)}, computed once per execution for a given
 * function (by its {@code address}, the Pure function, so every host keys a call the same way) and arguments:
 * objects by identity, primitives by value, a collection element by element. A failing call caches nothing; a
 * call that needs its own result is an error. See lang/eval/memoize.pure.
 *
 * <p>What is remembered lasts one execution: {@link Execute#call} begins and ends one, and only the outermost
 * end forgets — natives such as compile call back into {@code Execute.call} while an execution runs.</p>
 */
public final class PureMemo
{
    private static final String ADDRESS = "platform::pure::functions::meta::address_AddressableElement_1__String_1_";

    private static final Map<List<Object>, Object> MEMO = new HashMap<>();
    private static final Set<List<Object>> MEMOIZING = new HashSet<>();
    private static int executionDepth;

    private PureMemo()
    {
    }

    public static synchronized void beginExecution()
    {
        executionDepth++;
    }

    public static synchronized void endExecution()
    {
        if (--executionDepth <= 0)
        {
            executionDepth = 0;
            MEMO.clear();
            MEMOIZING.clear();
        }
    }

    public static Object memoize(Object function, List<Object> args)
    {
        String address = address(function);
        List<Object> key = new ArrayList<>(args.size() + 1);
        key.add(address);
        for (Object arg : args)
        {
            key.add(argument(arg));
        }
        synchronized (PureMemo.class)
        {
            if (MEMO.containsKey(key))
            {
                return MEMO.get(key);
            }
            if (!MEMOIZING.add(key))
            {
                throw new RuntimeException("memoize cycle: " + address + " is already being computed for these arguments");
            }
        }
        try
        {
            Object result = Metadata.invoke(function, args);
            synchronized (PureMemo.class)
            {
                MEMO.put(key, result);
            }
            return result;
        }
        finally
        {
            synchronized (PureMemo.class)
            {
                MEMOIZING.remove(key);
            }
        }
    }

    private static String address(Object function)
    {
        Object element = org.finos.legend.pure.platform.java.compiler.module.pdbModule.PureLambda.elementOf(function);
        try
        {
            return (String) Execute.invoke(ADDRESS, element == null ? function : element);
        }
        catch (RuntimeException e)
        {
            throw e;
        }
        catch (Exception e)
        {
            throw new RuntimeException("memoize: cannot address " + function + ": " + e.getMessage(), e);
        }
    }

    /** An argument as part of a key: a collection by its elements, one value as a collection of one. */
    private static Object argument(Object value)
    {
        List<Object> elements = new ArrayList<>();
        if (value instanceof List<?> list)
        {
            for (Object e : list)
            {
                elements.add(element(e));
            }
        }
        else if (value != null)
        {
            elements.add(element(value));
        }
        return elements;
    }

    private static Object element(Object value)
    {
        return value instanceof String || value instanceof Number || value instanceof Boolean
                || value instanceof PureDate || value instanceof byte[]
                ? new Primitive(value)
                : new Identity(value);
    }

    private record Primitive(Object value)
    {
        @Override
        public boolean equals(Object o)
        {
            return o instanceof Primitive p && (value instanceof byte[] b ? p.value instanceof byte[] pb && Arrays.equals(b, pb) : Objects.equals(value, p.value));
        }

        @Override
        public int hashCode()
        {
            return value instanceof byte[] b ? Arrays.hashCode(b) : Objects.hashCode(value);
        }
    }

    private record Identity(Object value)
    {
        @Override
        public boolean equals(Object o)
        {
            return o instanceof Identity i && i.value == value;
        }

        @Override
        public int hashCode()
        {
            return System.identityHashCode(value);
        }
    }
}
