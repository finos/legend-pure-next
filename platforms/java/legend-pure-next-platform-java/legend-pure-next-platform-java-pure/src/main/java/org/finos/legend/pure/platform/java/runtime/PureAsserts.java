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

import org.finos.legend.pure.platform.java.execution.PureStack;

import java.util.Collections;
import java.util.List;
import java.util.function.Supplier;

/** Pure's assertions: each throws when it does not hold, and is true otherwise. */
public final class PureAsserts
{
    private PureAsserts()
    {
    }

    /**
     * {@code assert(condition[, message])}: the message may be a String or a {@code {-> String}} thunk; a format
     * string with arguments is reported unformatted.
     */
    public static boolean assertTrue(boolean condition, Object message)
    {
        if (!condition)
        {
            Object m = message instanceof Supplier ? ((Supplier<?>) message).get() : message;
            throw new RuntimeException(String.valueOf(m));
        }
        return true;
    }

    /**
     * {@code assertEquals} / {@code assertIs}: the two values compared as Pure's equality does — an absent value
     * (null) is [], a one-element list its element — with {@link PureValues#equal}, not Objects.equals (Pure has
     * one zero: -0.0 must equal the literal 0.0 here as anywhere else).
     */
    public static boolean assertEqual(Object expected, Object actual)
    {
        Object e = normalized(expected);
        Object a = normalized(actual);
        if (!PureValues.equal(e, a))
        {
            throw new RuntimeException("\nexpected: " + e + "\nactual:   " + a);
        }
        return true;
    }

    /** {@code assertIsNot}: the two values are not equal, as Pure compares them. */
    public static boolean assertNotEqual(Object first, Object second)
    {
        Object a = normalized(first);
        Object b = normalized(second);
        if (PureValues.equal(a, b))
        {
            throw new RuntimeException("Objects are the same: " + a);
        }
        return true;
    }

    private static Object normalized(Object v)
    {
        if (v == null)
        {
            return Collections.emptyList();
        }
        return v instanceof List && ((List<?>) v).size() == 1 ? ((List<?>) v).get(0) : v;
    }

    /**
     * {@code assertError(f, message)}: invoking {@code f} throws, with a message containing {@code expected} —
     * containment, as the other hosts match: a test pins the part of the message that is the contract. The
     * compared text is {@link PureStack#describe}, not getMessage(): it carries the Pure call chain, which is
     * where bootstrap (NativeRegistry), Truffle (AssertErrorNode) and the JS runtime (__assertError) all append
     * it — at the catch boundary, never at the throw site.
     */
    public static boolean assertError(Supplier<?> f, String expected)
    {
        try
        {
            f.get();
        }
        catch (AssertionError e)
        {
            throw e;
        }
        catch (Throwable t)
        {
            String actual = PureStack.describe(t);
            if (!actual.contains(expected))
            {
                throw new AssertionError("expected: " + expected + ", actual: " + actual);
            }
            return true;
        }
        throw new AssertionError("expected exception with message: " + expected);
    }
}
