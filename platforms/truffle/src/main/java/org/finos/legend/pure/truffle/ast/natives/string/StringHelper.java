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

package org.finos.legend.pure.truffle.ast.natives.string;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import meta.pure.metamodel.valuespecification.AtomicValue;
import org.finos.legend.pure.truffle.types.ValueAdapter;

public final class StringHelper
{
    private StringHelper()
    {
    }

    public static String asString(Object v, String signature)
    {
        if (v instanceof String s)
        {
            return s;
        }
        if (v instanceof AtomicValue av && av._value() instanceof String s)
        {
            return s;
        }
        return fallback(v, signature);
    }

    @TruffleBoundary
    private static String fallback(Object v, String signature)
    {
        Object raw = ValueAdapter.toRaw(v);
        if (raw instanceof String s)
        {
            return s;
        }
        throw new ClassCastException(signature + " expected String, got: "
                + (raw == null ? "null" : raw.getClass().getName()));
    }
}
