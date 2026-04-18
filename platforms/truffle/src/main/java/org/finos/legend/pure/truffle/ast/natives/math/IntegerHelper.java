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

import meta.pure.metamodel.valuespecification.AtomicValue;

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
     * else falls through to inline Number checks.
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
        if (v instanceof AtomicValue av && av._value() instanceof Number n)
        {
            return n.longValue();
        }
        throw new ClassCastException(signature + " expected Integer, got: "
                + (v == null ? "null" : v.getClass().getName()));
    }
}
