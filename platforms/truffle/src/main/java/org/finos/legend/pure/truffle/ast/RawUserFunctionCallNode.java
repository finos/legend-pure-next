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

package org.finos.legend.pure.truffle.ast;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.NodeInfo;
import meta.pure.metamodel.function.FunctionDefinition;
import org.finos.legend.pure.truffle.runtime.StandaloneEvaluatorHolder;

/**
 * Calls a user-defined FunctionDefinition via StandaloneEvaluator.executeFunction().
 * All arguments and return values are raw Java objects.
 */
@NodeInfo(shortName = "userFunctionCall")
public final class RawUserFunctionCallNode extends PureNode
{
    private final FunctionDefinition fd;

    @Children
    private PureNode[] argNodes;

    public RawUserFunctionCallNode(FunctionDefinition fd, PureNode[] argNodes)
    {
        this.fd = fd;
        this.argNodes = argNodes;
    }

    @Override
    public Object executeGeneric(VirtualFrame frame)
    {
        Object[] args = new Object[argNodes.length];
        for (int i = 0; i < argNodes.length; i++)
        {
            args[i] = argNodes[i].executeGeneric(frame);
        }
        return doCall(fd, args);
    }

    @TruffleBoundary
    private static Object doCall(FunctionDefinition fd, Object[] args)
    {
        return StandaloneEvaluatorHolder.current().executeFunction(fd, args);
    }
}
