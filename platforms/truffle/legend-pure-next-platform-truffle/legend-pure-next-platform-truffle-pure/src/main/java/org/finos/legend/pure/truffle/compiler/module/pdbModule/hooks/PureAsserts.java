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

import java.util.function.Supplier;

/** {@code assert} for the generated PDB reader — Truffle's copy of the Java platform's PureAsserts.assertTrue. */
public final class PureAsserts
{
    private PureAsserts()
    {
    }

    /** Throws when the condition is false; the message may be a String or a {@code {-> String}} thunk. */
    public static boolean assertTrue(boolean condition, Object message)
    {
        if (!condition)
        {
            Object m = message instanceof Supplier ? ((Supplier<?>) message).get() : message;
            throw new RuntimeException(String.valueOf(m));
        }
        return true;
    }
}
