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
 * <p>Two operating modes, picked by the AST builder:</p>
 *
 * <h3>Static (inlined-bodies) mode</h3>
 * When both the {@code then} and {@code else} arguments are literal 0-param
 * closure-lambdas with single-expression bodies, the AST builder lowers each
 * body directly as a {@link PureNode} child here. Runtime: evaluate the
 * condition, execute the chosen body. No {@code RawClosure} allocation per
 * call, no {@code RawLambdaCallNode} dispatch — the body's variable
 * references resolve directly against the caller's frame layout via the
 * builder's standard expression-lowering path. Same pattern as
 * {@link MultiIfNode}'s static mode.
 *
 * <h3>Generic (lambda-dispatched) mode</h3>
 * Anything else (e.g. the branch is a variable-bound lambda or a function
 * reference). Evaluates the branch to a closure and dispatches via
 * {@link RawLambdaCallNode}.
 */
@NodeInfo(shortName = "if")
public final class IfNode extends PureNode
{
    @Child
    private PureNode condition;

    // --- Static mode (both inlined) ---

    /** Inlined then-body; non-null in static mode, null in generic mode. */
    @Child
    private PureNode thenBody;

    /** Inlined else-body; non-null in static mode, null in generic mode. */
    @Child
    private PureNode elseBody;

    // --- Generic mode (lambda dispatch) ---

    /** Generic then-arg producing a closure; null in static mode. */
    @Child
    private PureNode thenBranch;

    /** Generic else-arg producing a closure; null in static mode. */
    @Child
    private PureNode elseBranch;

    @Child
    private RawLambdaCallNode callNode;

    /** Static-mode constructor — caller pre-lowered each branch's body. */
    public IfNode(PureNode condition, PureNode thenBody, PureNode elseBody, boolean staticMode)
    {
        this.condition = condition;
        this.thenBody = thenBody;
        this.elseBody = elseBody;
        // Skip {@link RawLambdaCallNode} entirely — static branches don't need it.
    }

    /** Generic-mode constructor — branches are closure-producing expressions. */
    public IfNode(PureNode condition, PureNode thenBranch, PureNode elseBranch)
    {
        this.condition = condition;
        this.thenBranch = thenBranch;
        this.elseBranch = elseBranch;
        this.callNode = new RawLambdaCallNode();
    }

    @Override
    public Object executeGeneric(VirtualFrame frame)
    {
        // condition.executeBoolean stays primitive on the JVM stack when the
        // child is one of the boolean producers (NotBool / And / Or / Equal /
        // comparison nodes). For producers that still return Object the base
        // PureNode.executeBoolean does the unbox.
        boolean cond = condition.executeBoolean(frame);
        if (thenBody != null)
        {
            return cond ? thenBody.executeGeneric(frame) : elseBody.executeGeneric(frame);
        }
        Object branchFn = cond ? thenBranch.executeGeneric(frame) : elseBranch.executeGeneric(frame);
        return callNode.call(branchFn);
    }
}
