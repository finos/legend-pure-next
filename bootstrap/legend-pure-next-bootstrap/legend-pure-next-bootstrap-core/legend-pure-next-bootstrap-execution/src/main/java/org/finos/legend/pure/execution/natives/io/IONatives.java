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

package org.finos.legend.pure.execution.natives.io;

import org.finos.legend.pure.execution.NativeRepository.LazyNativeImpl;
import org.finos.legend.pure.execution.NativeRepository.NativeImpl;
import org.finos.legend.pure.execution.PureValuePrinter;
import org.finos.legend.pure.execution._E_ValueSpecification;
import org.finos.legend.pure.m3.module.MetadataAccess;

import java.util.Map;

public class IONatives
{
    /**
     * Thread-local "suppress print stdout" flag. {@code print} still computes
     * and returns the formatted string when set (so assertions work), but
     * skips the {@code System.out.print} side effect. Test runners flip this
     * around each test body so the progress UI / runner output stays visible
     * while Pure test prints don't interleave with it.
     */
    public static final ThreadLocal<Boolean> SILENCED = ThreadLocal.withInitial(() -> Boolean.FALSE);

    public static void register(Map<String, NativeImpl> natives,
                                Map<String, LazyNativeImpl> lazyNatives,
                                MetadataAccess resolver)
    {
        // print(Any[*], Integer[1]) : String[1]
        // Writes to stdout AND returns the formatted string so callers (like
        // `println` in pure spec) can compose without recomputing. Flush after
        // print so `\r`-based progress updates display immediately on
        // line-buffered terminals.
        natives.put("print_Any_MANY__Integer_1__String_1_", (args, eval, genericType, multiplicity) ->
        {
            Object val = _E_ValueSpecification.unwrap(args.get(0));
            String s = PureValuePrinter.printForOutput(val);
            if (!SILENCED.get())
            {
                System.out.print(s);
                System.out.flush();
            }
            return _E_ValueSpecification.wrap(s, genericType, multiplicity, resolver);
        });

        // withSilencedPrint(closure) : T[m]
        // Push the SILENCED flag for the duration of the closure, restoring
        // the previous value in finally. Test runners wrap each test body in
        // this so Pure {@code print} calls inside tests don't pollute stdout
        // while the runner's own progress prints (called outside) remain
        // visible.
        natives.put("withSilencedPrint_Function_1__T_m_", (args, eval, genericType, multiplicity) ->
        {
            Boolean prev = SILENCED.get();
            SILENCED.set(Boolean.TRUE);
            try
            {
                // Pass the VS itself (not the unwrapped closure) — the
                // pattern that `if` and `match` use for closure invocation.
                // executeFunction returns the body's ValueSpecification
                // already typed by its declared return type, which is what
                // the runtime expects from a native call.
                return eval.executeFunction(args.get(0), java.util.List.of());
            }
            finally
            {
                SILENCED.set(prev);
            }
        });
    }
}
