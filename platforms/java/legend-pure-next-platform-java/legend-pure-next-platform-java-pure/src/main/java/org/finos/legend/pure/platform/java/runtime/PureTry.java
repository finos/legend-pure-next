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

import org.finos.legend.pure.m3.platform.pure.functions.lang.ErrorImpl;
import org.finos.legend.pure.m3.platform.pure.functions.lang.TryResult;
import org.finos.legend.pure.m3.platform.pure.functions.lang.TryResultImpl;
import org.finos.legend.pure.platform.java.execution.PureStack;

import java.util.function.Supplier;

/** Pure's {@code tryEval}: the thunk's value, or its failure, as a TryResult. */
public final class PureTry
{
    private PureTry()
    {
    }

    /**
     * The failure carries the Pure call chain, not the host one: {@code stack} is Pure-facing (the test runner
     * prints {@code at <frame>} from it, and it is what lands in the JSON report), so raw StackTraceElements
     * would name generated class files nobody reads. Every error caught by tryEval gets this — which is every
     * error a Pure program or a test suite ever sees. Throwable, not Exception: a Pure assertion is an Error.
     */
    public static TryResult tryEval(Supplier<?> thunk)
    {
        TryResultImpl result = new TryResultImpl();
        try
        {
            result._value(thunk.get());
        }
        catch (Throwable e)
        {
            result._failure(new ErrorImpl()._message(String.valueOf(e.getMessage()))._stack(PureStack.frames(e)));
        }
        return result;
    }
}
