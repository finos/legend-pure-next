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

/**
 * {@code print(Any[*], Integer[1]) : Nil[0]} -- prints value to stdout.
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
            depthArg.executeGeneric(frame);
        }
        doPrint(val);
        return PureNull.INSTANCE;
    }

    @TruffleBoundary
    private static void doPrint(Object val)
    {
        Object normalized = org.finos.legend.pure.truffle.types.ValueNormalizer.normalize(val);
        System.out.print(PureValuePrinter.printForOutput(normalized));
        System.out.flush();
    }
}
