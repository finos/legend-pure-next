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

package org.finos.legend.pure.truffle.ast.natives.lang;

import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.NodeInfo;
import org.finos.legend.pure.truffle.ast.PureNode;
import org.finos.legend.pure.truffle.ast.RawLambdaCallNode;

/**
 * {@code if(Boolean[1], Function<{->T[m]}>[1], Function<{->T[m]}>[1]) : T[m]}.
 *
 * <p>Evaluates the condition eagerly. Invokes the selected branch
 * as a zero-arg lambda via Truffle CallTarget dispatch.</p>
 */
@NodeInfo(shortName = "if")
public final class IfNode extends PureNode
{
    @Child
    private PureNode condition;

    @Child
    private PureNode thenBranch;

    @Child
    private PureNode elseBranch;

    @Child
    private RawLambdaCallNode callNode = new RawLambdaCallNode();

    public IfNode(PureNode condition, PureNode thenBranch, PureNode elseBranch)
    {
        this.condition = condition;
        this.thenBranch = thenBranch;
        this.elseBranch = elseBranch;
    }

    @Override
    public Object executeGeneric(VirtualFrame frame)
    {
        // condition.executeBoolean stays primitive on the JVM stack when the
        // child is one of the boolean producers (NotBool / And / Or / Equal /
        // comparison nodes). For producers that still return Object the base
        // PureNode.executeBoolean does the unbox.
        Object branchFn = condition.executeBoolean(frame)
                ? thenBranch.executeGeneric(frame)
                : elseBranch.executeGeneric(frame);
        return callNode.call(branchFn);
    }
}
