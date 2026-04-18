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
 * Unwrap helpers shared by specialized float-arithmetic nodes. Mirrors
 * {@link IntegerHelper} but for {@link Double}.
 */
final class FloatHelper
{
    private FloatHelper()
    {
    }

    static double asDouble(Object v, String signature)
    {
        if (v instanceof Double d)
        {
            return d;
        }
        if (v instanceof Long l)
        {
            return l.doubleValue();
        }
        if (v instanceof AtomicValue av)
        {
            Object inner = av._value();
            if (inner instanceof Double d)
            {
                return d;
            }
            if (inner instanceof Long l)
            {
                return l.doubleValue();
            }
        }
        return fallback(v, signature);
    }

    @TruffleBoundary
    private static double fallback(Object v, String signature)
    {
        Object raw = ValueAdapter.toRaw(v);
        if (raw instanceof Number n)
        {
            return n.doubleValue();
        }
        throw new ClassCastException(signature + " expected Float, got: "
                + (raw == null ? "null" : raw.getClass().getName()));
    }
}
