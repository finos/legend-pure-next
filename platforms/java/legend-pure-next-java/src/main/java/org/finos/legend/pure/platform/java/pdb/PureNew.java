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

import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.function.Supplier;

/**
 * Construction semantics of {@code ^X(...)} / {@code ^$x(...)}, ported from the
 * Truffle runtime (PureContext's fresh-scope and construction stacks,
 * NewWithKeysNode's verifyAssocPropsAreFresh and setReverseAssociationPointers).
 *
 * <p>{@link #run} keeps a construction frame — what {@code ~}, {@code ~.~} read
 * through {@link #self} — and a fresh scope: the result registers into the
 * enclosing scope, so a value built anywhere inside an outer construction (a
 * fold or map lambda included) may be assigned to one of its association
 * properties. A class's generated {@code _pureFinish()} checks that with
 * {@link #checkFresh} and links the reverse ends with {@link #link}.</p>
 */
public final class PureNew
{
    private static final ArrayDeque<IdentityHashMap<Object, Boolean>> SCOPES = new ArrayDeque<>();
    private static final ArrayDeque<Object> CONSTRUCTION = new ArrayDeque<>();

    private PureNew()
    {
    }

    public static Object run(Object instance, Supplier<?> body)
    {
        SCOPES.push(new IdentityHashMap<>());
        CONSTRUCTION.push(instance);
        Object result = null;
        try
        {
            result = body.get();
            return result;
        }
        finally
        {
            CONSTRUCTION.pop();
            SCOPES.pop();
            if (result != null && !SCOPES.isEmpty())
            {
                SCOPES.peek().put(result, Boolean.TRUE);
            }
        }
    }

    /** The object under construction {@code up} levels out: 0 for {@code ~}, 1 for {@code ~.~}. */
    public static Object self(int up)
    {
        Iterator<Object> frames = CONSTRUCTION.iterator();
        for (int i = 0; i < up; i++)
        {
            frames.next();
        }
        return frames.next();
    }

    public static void checkFresh(Object value, String property, String classPath)
    {
        for (Object item : items(value))
        {
            boolean fresh = false;
            for (IdentityHashMap<Object, Boolean> scope : SCOPES)
            {
                if (scope.containsKey(item))
                {
                    fresh = true;
                    break;
                }
            }
            if (item != null && !fresh)
            {
                throw new RuntimeException("Immutability violation: association property '" + property + "' on '" + classPath
                        + "' must be instantiated within the new/copy expression. Use `" + property + " = ^Type(...)`, `"
                        + property + " = ^$x()`, or `" + property + " = []`.");
            }
        }
    }

    public static void link(Object targets, String reverseProperty, boolean many, Object self)
    {
        for (Object target : items(targets))
        {
            if (target == null)
            {
                continue;
            }
            try
            {
                Method setter = target.getClass().getMethod("_" + reverseProperty + (many ? "_add" : ""), Object.class);
                setter.invoke(target, self);
            }
            catch (ReflectiveOperationException e)
            {
                throw new RuntimeException("Cannot set association property '" + reverseProperty + "' on " + target.getClass().getSimpleName(), e);
            }
        }
    }

    private static List<?> items(Object value)
    {
        return value instanceof List<?> list ? list : value == null ? Collections.emptyList() : Collections.singletonList(value);
    }
}
