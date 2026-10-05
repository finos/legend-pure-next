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


package org.finos.legend.pure.truffle.compiler.module.pdbModule.hooks;

import java.util.Map;

/**
 * The lambda hook the generated PDB reader calls.
 *
 * <p>In the reader every lambda is consumed by the translated code itself — as
 * the argument of a `map`/`filter`/`fold` that the translator emits as a Java
 * stream operation — and is cast straight back to {@code Function} or
 * {@code BiFunction} at the use site. None is ever handed to Pure as a value,
 * so wrapping it would only be undone. Identity is the whole implementation.</p>
 */
public final class PureLambda
{
    private PureLambda()
    {
    }

    public static Object of(Object callable, String owner, long index)
    {
        return callable;
    }

    public static Object of(Object callable, String owner, long index, Map<?, ?> captures)
    {
        return callable;
    }

    /** A function reference's element; the reader has none. */
    public static Object elementOf(Object value)
    {
        return null;
    }
}
