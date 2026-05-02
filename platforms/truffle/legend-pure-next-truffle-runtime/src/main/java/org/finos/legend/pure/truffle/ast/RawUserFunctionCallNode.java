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

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import com.oracle.truffle.api.nodes.IndirectCallNode;
import com.oracle.truffle.api.nodes.NodeInfo;
import org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.function.FunctionDefinition;

/**
 * Calls a user-defined FunctionDefinition via Truffle {@link IndirectCallNode}.
 * The CallTarget is resolved lazily and cached. All arguments and return values
 * are raw Java objects.
 *
 * <p>Using IndirectCallNode (instead of a plain Java call through
 * StandaloneEvaluator) enables Truffle to:
 * <ul>
 *   <li>Build proper stack frames with Pure source locations</li>
 *   <li>Inline the callee for Graal JIT compilation</li>
 * </ul>
 */
@NodeInfo(shortName = "userFunctionCall")
public final class RawUserFunctionCallNode extends PureNode
{
    private final FunctionDefinition fd;

    @Children
    private PureNode[] argNodes;

    @Child
    private IndirectCallNode callNode;

    @CompilerDirectives.CompilationFinal
    private RootCallTarget cachedCallTarget;

    public RawUserFunctionCallNode(FunctionDefinition fd, PureNode[] argNodes)
    {
        this.fd = fd;
        this.argNodes = argNodes;
        this.callNode = IndirectCallNode.create();
    }

    @Override
    public Object executeGeneric(VirtualFrame frame)
    {
        Object[] args = evaluateArgs(frame);
        // For QPs: dispatch based on target's runtime type
        if (fd instanceof org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.function.property.QualifiedProperty qp && args.length > 0)
        {
            return dispatchQp(qp, args);
        }
        // For regular functions: cached CallTarget
        RootCallTarget ct = cachedCallTarget;
        if (ct == null)
        {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            ct = getContext().getCallTarget(fd);
            cachedCallTarget = ct;
        }
        if (ct != null)
        {
            return callNode.call(ct, args);
        }
        return getContext().executeFunction(fd, args);
    }

    @ExplodeLoop
    private Object[] evaluateArgs(VirtualFrame frame)
    {
        Object[] args = new Object[argNodes.length];
        for (int i = 0; i < argNodes.length; i++)
        {
            args[i] = argNodes[i].executeGeneric(frame);
        }
        return args;
    }

    private Object dispatchQp(org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.function.property.QualifiedProperty staticQp, Object[] args)
    {
        // Resolve the actual QP from the target's runtime type
        org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.function.FunctionDefinition resolved =
                getContext().resolveQpDispatch(staticQp, args);
        RootCallTarget ct = getContext().getCallTarget(resolved);
        if (ct != null)
        {
            return callNode.call(ct, args);
        }
        return getContext().executeFunction(resolved, args);
    }
}
