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

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.NodeInfo;
import org.finos.legend.pure.execution.PureValuePrinter;
import org.finos.legend.pure.truffle.ast.PureNode;
import org.finos.legend.pure.truffle.types.PureNull;
import org.finos.legend.pure.truffle.types.ValueAdapter;

/**
 * {@code println(Any[*]) : Nil[0]} — prints value to stdout with newline.
 */
@NodeInfo(shortName = "println")
public final class PrintlnNode extends PureNode
{
    @Child
    private PureNode valueArg;

    public PrintlnNode(PureNode valueArg)
    {
        this.valueArg = valueArg;
    }

    @Override
    public Object executeGeneric(VirtualFrame frame)
    {
        Object val = valueArg.executeGeneric(frame);
        doPrintln(val);
        return PureNull.INSTANCE;
    }

    @TruffleBoundary
    private static void doPrintln(Object val)
    {
        Object raw = ValueAdapter.toRaw(val);
        if (raw == PureNull.INSTANCE)
        {
            raw = null;
        }
        System.out.println(PureValuePrinter.printForOutput(raw));
    }
}
