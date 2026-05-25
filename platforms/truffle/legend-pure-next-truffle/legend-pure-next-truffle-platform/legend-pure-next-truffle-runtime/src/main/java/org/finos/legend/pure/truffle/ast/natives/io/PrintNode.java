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

package org.finos.legend.pure.truffle.ast.natives.io;

import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.NodeInfo;
import org.finos.legend.pure.truffle.ast.PureNode;

/**
 * {@code print(Any[*], Integer[1]) : String[1]} — writes the formatted value
 * to stdout and returns the same string. The dual return lets the Pure-level
 * {@code println} compose ({@code print(v) + print('\n')}) without
 * recomputing the format, and lets tests assert on the rendering directly.
 */
@NodeInfo(shortName = "print")
public final class PrintNode extends PureNode
{
    @Child
    private PureNode valueArg;

    @Child
    private PureNode depthArg;

    public PrintNode(PureNode valueArg, PureNode depthArg)
    {
        this.valueArg = valueArg;
        this.depthArg = depthArg;
    }

    @Override
    public Object executeGeneric(VirtualFrame frame)
    {
        Object val = valueArg.executeGeneric(frame);
        if (depthArg != null)
        {
            // Evaluate for side effects; depth bound is reserved.
            depthArg.executeGeneric(frame);
        }
        return doPrint(val);
    }

    /**
     * Thread-local "suppress print stdout" flag. {@link WithSilencedPrintNode}
     * flips it around test bodies so individual tests' Pure {@code print}
     * calls don't pollute stdout while the runner's own progress prints
     * (called outside the test body) remain visible. The Pure-level value
     * returned by {@code print} is unaffected — assertions still work.
     */
    public static final ThreadLocal<Boolean> SILENCED = ThreadLocal.withInitial(() -> Boolean.FALSE);

    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    private static String doPrint(Object val)
    {
        String s = TruffleValuePrinter.printForOutput(val);
        if (!SILENCED.get())
        {
            System.out.print(s);
            System.out.flush();
        }
        return s;
    }
}
