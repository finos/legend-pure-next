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

package org.finos.legend.pure.truffle.ast.natives.math;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import meta.pure.metamodel.valuespecification.AtomicValue;
import org.finos.legend.pure.truffle.types.ValueAdapter;

/**
 * Unwrap helpers shared by specialized integer-arithmetic nodes. Separating
 * these into one place means Graal sees a single inlinable implementation
 * across every plus / minus / times node.
 */
public final class IntegerHelper
{
    private IntegerHelper()
    {
    }

    /**
     * Hot-path unwrap: a {@link AtomicValue} with a {@link Long} payload. Anything
     * else goes through the slow-path {@link #fallback} (bridged value, Integer
     * boxed as a different type, etc.).
     */
    public static long asLong(Object v, String signature)
    {
        if (v instanceof Long l)
        {
            return l;
        }
        if (v instanceof AtomicValue av && av._value() instanceof Long l)
        {
            return l;
        }
        if (v instanceof Number n)
        {
            return n.longValue();
        }
        return fallback(v, signature);
    }

    @TruffleBoundary
    private static long fallback(Object v, String signature)
    {
        if (v instanceof Number n)
        {
            return n.longValue();
        }
        Object raw = ValueAdapter.toRaw(v);
        if (raw instanceof Number n)
        {
            return n.longValue();
        }
        throw new ClassCastException(signature + " expected Integer, got: "
                + (v == null ? "null" : v.getClass().getName()));
    }
}
