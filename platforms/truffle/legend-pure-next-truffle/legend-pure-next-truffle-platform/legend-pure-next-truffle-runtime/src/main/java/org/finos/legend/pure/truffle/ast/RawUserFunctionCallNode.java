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
import com.oracle.truffle.api.nodes.DirectCallNode;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import com.oracle.truffle.api.nodes.IndirectCallNode;
import com.oracle.truffle.api.nodes.NodeInfo;

/**
 * Calls a user-defined FunctionDefinition.
 *
 * <p>Each instance is a *monomorphic* call site by construction — the AST
 * builder bakes in one FunctionDefinition per node, so the call target
 * never changes once resolved. We use a {@link DirectCallNode} with the
 * cached target, which is Truffle's canonical mechanism for letting the
 * runtime decide inlining per call site (small targets fold into the
 * caller; large compiler-pure functions stay as separate JIT units, by
 * Truffle's own heuristics).</p>
 *
 * <p>QualifiedProperty calls fall back to {@link IndirectCallNode} because
 * the resolved target depends on the runtime type of the receiver.</p>
 *
 * <p>The {@code fd} field is typed as {@link Object} so a future loader
 * flip to {@code PureDynamicObject} keeps working — the dispatch only
 * needs the metaclass identity which {@code PureObj.pureTypeIs} reports
 * uniformly across XPDBHelper and PDO.</p>
 */
@NodeInfo(shortName = "userFunctionCall")
public final class RawUserFunctionCallNode extends PureNode
{
    private static final String QUALIFIED_PROPERTY_PATH =
            "meta::pure::metamodel::function::property::QualifiedProperty";

    private final Object fd;

    @Children
    private PureNode[] argNodes;

    /**
     * Direct call to the cached target (monomorphic). Truffle's runtime
     * makes the per-callsite inlining decision: small targets fold into
     * the caller, large targets compile as separate JIT units.
     */
    @Child
    private DirectCallNode directCallNode;

    /**
     * Indirect call for QualifiedProperty dispatch — the resolved target
     * varies by receiver type so we can't lock in a {@link DirectCallNode}.
     */
    @Child
    private IndirectCallNode indirectCallNode;

    @CompilerDirectives.CompilationFinal
    private RootCallTarget cachedTarget;

    public RawUserFunctionCallNode(Object fd, PureNode[] argNodes)
    {
        this.fd = fd;
        this.argNodes = argNodes;
    }

    @Override
    public Object executeGeneric(VirtualFrame frame)
    {
        Object[] args = evaluateArgs(frame);
        if (args.length > 0 && org.finos.legend.pure.truffle.runtime.dynobj.PureObj.pureTypeIs(fd, QUALIFIED_PROPERTY_PATH))
        {
            return dispatchQp(fd, args);
        }
        if (directCallNode != null)
        {
            return directCallNode.call(args);
        }
        return slowDispatch(args);
    }

    private Object slowDispatch(Object[] args)
    {
        CompilerDirectives.transferToInterpreterAndInvalidate();
        if (cachedTarget == null)
        {
            cachedTarget = getContext().getCallTarget(fd);
        }
        if (cachedTarget != null)
        {
            directCallNode = insert(DirectCallNode.create(cachedTarget));
            return directCallNode.call(args);
        }
        // No call target available (legacy path) — fall through to the
        // evaluator's compile-on-demand entry point.
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

    private Object dispatchQp(Object staticQp, Object[] args)
    {
        // QP target depends on the receiver's runtime type — install (lazily)
        // an IndirectCallNode and let it dispatch per call.
        if (indirectCallNode == null)
        {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            indirectCallNode = insert(IndirectCallNode.create());
        }
        Object resolved = resolveQpTarget(staticQp, args);
        RootCallTarget ct = lookupCallTarget(resolved);
        if (ct != null)
        {
            return indirectCallNode.call(ct, args);
        }
        return invokeWithoutCallTarget(resolved, args);
    }

    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    private Object resolveQpTarget(Object staticQp, Object[] args)
    {
        return getContext().resolveQpDispatch(staticQp, args);
    }

    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    private RootCallTarget lookupCallTarget(Object resolved)
    {
        return getContext().getCallTarget(resolved);
    }

    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    private Object invokeWithoutCallTarget(Object resolved, Object[] args)
    {
        return getContext().executeFunction(resolved, args);
    }
}
