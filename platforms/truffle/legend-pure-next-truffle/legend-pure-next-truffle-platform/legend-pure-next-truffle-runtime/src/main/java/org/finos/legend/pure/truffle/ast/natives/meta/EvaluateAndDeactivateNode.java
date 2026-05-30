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

package org.finos.legend.pure.truffle.ast.natives.meta;

import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.NodeInfo;
import org.finos.legend.pure.truffle.ast.PureNode;

/**
 * {@code evaluateAndDeactivate(Any[m]) : Any[m]}.
 *
 * <p>Pass-through node that returns its child's value as-is. In the Pure
 * interpreter this is used to force evaluation of lazy expressions, but in
 * the Truffle runtime all expressions are already evaluated eagerly.</p>
 */
@NodeInfo(shortName = "evaluateAndDeactivate")
public final class EvaluateAndDeactivateNode extends PureNode
{
    @Child
    private PureNode child;

    public EvaluateAndDeactivateNode(PureNode child)
    {
        this.child = child;
    }

    @Override
    public Object executeGeneric(VirtualFrame frame)
    {
        return child.executeGeneric(frame);
    }
}
