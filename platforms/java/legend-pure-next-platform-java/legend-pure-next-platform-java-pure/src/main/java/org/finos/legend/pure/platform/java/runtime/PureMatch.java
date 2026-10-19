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

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * Pure's {@code match}: the first lambda whose parameter accepts the value (its multiplicity, then the type of
 * every element) is applied to it.
 *
 * <p>A lambda whose upper bound is 1 takes the value itself (or null), any other takes the list — the same
 * representation translated code gives such a parameter. The result is then shaped to the match's own
 * multiplicity: a List when {@code many}, else a single value (null for none).</p>
 */
@SuppressWarnings({"rawtypes", "unchecked"})
public final class PureMatch
{
    private PureMatch()
    {
    }

    /**
     * Lambdas known when translating: {@code bounds} holds each lambda's lower and upper bound in turn (-1 for
     * no upper bound), {@code classes} the Java class(es) its parameter accepts — a Pure function value is
     * either a metamodel object (read from a PDB) or a Java lambda (a closure), so such an arm lists both.
     */
    public static Object match(Object value, List arms, List<? extends Number> bounds, List classes, Object extra, boolean withExtra, boolean many)
    {
        List values = asList(value);
        for (int i = 0; i < arms.size(); i++)
        {
            long upper = bounds.get(2 * i + 1).longValue();
            if (accepts(values, bounds.get(2 * i).longValue(), upper) && allInstances(values, classes.get(i)))
            {
                Object arg = argument(values, upper);
                Object result = withExtra ? ((BiFunction) arms.get(i)).apply(arg, extra) : ((Function) arms.get(i)).apply(arg);
                return shaped(result, many);
            }
        }
        throw new RuntimeException("Match failure: " + value);
    }

    /** Lambdas only known at run time (function values): their parameter is read from the metadata. */
    public static Object matchDynamic(Object value, List arms, Object extra, boolean withExtra, boolean many)
    {
        List values = asList(value);
        for (Object lambda : arms)
        {
            Object[] arm = Metadata.matchArm(lambda);
            long lower = (Long) arm[1];
            long upper = (Long) arm[2];
            if (accepts(values, lower, upper) && allOfType(values, arm[0]))
            {
                Object arg = argument(values, upper);
                Object result = Metadata.invoke(lambda, withExtra ? Arrays.asList(arg, extra) : Collections.singletonList(arg));
                return shaped(result, many);
            }
        }
        throw new RuntimeException("Match failure: " + value);
    }

    private static List asList(Object value)
    {
        return value instanceof List ? (List) value : value == null ? Collections.emptyList() : Collections.singletonList(value);
    }

    private static boolean accepts(List values, long lower, long upper)
    {
        return values.size() >= lower && (upper < 0 || values.size() <= upper);
    }

    private static boolean allInstances(List values, Object type)
    {
        List<?> types = type instanceof List ? (List<?>) type : Collections.singletonList(type);
        for (Object v : values)
        {
            if (!isInstanceOfAny(v, types))
            {
                return false;
            }
        }
        return true;
    }

    private static boolean isInstanceOfAny(Object v, List<?> types)
    {
        for (Object t : types)
        {
            if (((Class<?>) t).isInstance(v))
            {
                return true;
            }
        }
        return false;
    }

    private static boolean allOfType(List values, Object type)
    {
        for (Object v : values)
        {
            if (!Metadata.instanceOf(v, type))
            {
                return false;
            }
        }
        return true;
    }

    private static Object argument(List values, long upper)
    {
        return upper == 1 ? (values.isEmpty() ? null : values.get(0)) : values;
    }

    private static Object shaped(Object result, boolean many)
    {
        if (many)
        {
            return asList(result);
        }
        if (result instanceof List)
        {
            List l = (List) result;
            return l.isEmpty() ? null : l.size() == 1 ? l.get(0) : l;
        }
        return result;
    }
}
