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
    /**
     * Whether the callee should be inlined into the caller during partial
     * evaluation. Pure has thousands of small user functions plus a few very
     * large ones (the compiler internals) — naive inlining of the latter
     * spreads the same JDK helpers across many specializations and exhausts
     * Graal's inlining budget ("Too deep inlining" — 800+ failures during
     * self-host). We only mark calls into the collection / lang / etc.
     * standard library as inline candidates so collection ops fold their
     * lambdas; everything else uses an indirect call with a non-constant
     * target so Graal stops at the call site.
     */
    private final boolean inlineCandidate;

    @Children
    private PureNode[] argNodes;

    @Child
    private IndirectCallNode callNode;

    /**
     * Cached call target — only marked {@link CompilerDirectives.CompilationFinal}
     * for inline-candidate callees. For non-candidates the field is read as a
     * regular (non-PE) value so Graal can't fold the target and the call stays
     * indirect.
     */
    @CompilerDirectives.CompilationFinal
    private RootCallTarget cachedCallTarget;
    private RootCallTarget regularCallTarget;

    public RawUserFunctionCallNode(FunctionDefinition fd, PureNode[] argNodes)
    {
        this.fd = fd;
        this.argNodes = argNodes;
        this.callNode = IndirectCallNode.create();
        this.inlineCandidate = isInlineCandidate(fd);
    }

    /**
     * Whitelist of standard-library namespaces whose functions are small
     * enough — and called often enough — that their bodies should fold into
     * callers during partial evaluation. Bodies in these namespaces are the
     * collection combinators (map / fold / filter / …), small lang predicates
     * (if / let / equals / …), boolean / math primitives, and metaclass
     * accessors — exactly the patterns a Pure-source compiler hot loop wants
     * unrolled.
     */
    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    private static boolean isInlineCandidate(FunctionDefinition fd)
    {
        // Empty whitelist: every named function and every lambda routes
        //   through {@link #boundaryCall} so each Pure function is its own
        //   JIT compilation unit. Test mode for "no inlining at all".
        return false;
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
        if (inlineCandidate)
        {
            // Inline-candidate path: cached as @CompilationFinal so Graal sees
            //   the target as constant and may fold the callee body in.
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
        // Non-inline path: hard boundary — Graal stops PE here so the caller's
        //   compilation graph stays small. The callee compiles as its own unit
        //   (still JIT-compiled, just not inlined).
        RootCallTarget ct = regularCallTarget;
        if (ct == null)
        {
            ct = getContext().getCallTarget(fd);
            regularCallTarget = ct;
        }
        if (ct != null)
        {
            return boundaryCall(ct, args);
        }
        return getContext().executeFunction(fd, args);
    }

    @CompilerDirectives.TruffleBoundary
    private static Object boundaryCall(RootCallTarget ct, Object[] args)
    {
        return ct.call(args);
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

    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
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
