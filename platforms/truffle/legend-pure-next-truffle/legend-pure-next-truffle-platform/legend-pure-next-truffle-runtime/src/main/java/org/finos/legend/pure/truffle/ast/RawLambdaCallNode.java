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
import com.oracle.truffle.api.nodes.DirectCallNode;
import com.oracle.truffle.api.nodes.IndirectCallNode;
import com.oracle.truffle.api.nodes.Node;

/**
 * Raw lambda call site — no ValueSpecification anywhere.
 *
 * <p>Monomorphic inline cache: first call records the call target and
 * installs a {@link DirectCallNode}. Subsequent calls to the same target
 * dispatch through it — letting Truffle's runtime inline the callee into
 * the caller's compilation when its inlining heuristics agree. Different
 * targets at the same call site promote to an {@link IndirectCallNode},
 * which is a hard inlining boundary (the target isn't compile-time
 * constant).</p>
 *
 * <p>This is the standard Truffle pattern: instead of forcing every lambda
 * call across a {@code @TruffleBoundary} (which prevents fusion of
 * collection operations like {@code map}/{@code filter}/{@code fold} with
 * their lambda body), we let Truffle decide. The Graal trip-wire on
 * {@code TruffleTestCompilerPure} catches any regression.</p>
 *
 * <p>All arguments and return values are raw Java objects.</p>
 */
public final class RawLambdaCallNode extends Node
{

    private static final int SLOT_NAME = org.finos.legend.pure.truffle.runtime.dynobj.PureClassRegistry.globalSlot("name");
    @Child
    private DirectCallNode directCallNode;

    @Child
    private IndirectCallNode indirectCallNode;

    @Child
    private PropertyReadNode propertyReader = new PropertyReadNode();

    @CompilerDirectives.CompilationFinal
    private RootCallTarget cachedTarget;

    /** Inline-cache the lambda/closure identity that produced
     *  {@link #cachedTarget}. When the next call sees the same identity, we
     *  skip {@link #getCallTarget} entirely — that method is
     *  {@code @TruffleBoundary} so reaching it stops PE inlining and forces
     *  a real call. Monomorphic lambda call sites (the 99% case for
     *  fold/map/filter inside one function) hit the identity match and
     *  go straight to {@link #directCallNode#call}. */
    @CompilerDirectives.CompilationFinal
    private Object cachedLambdaIdentity;

    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    private org.finos.legend.pure.truffle.PureContext getContext()
    {
        return org.finos.legend.pure.truffle.PureLanguage.get(this);
    }

    public Object call(Object lambdaOrClosure)
    {
        return dispatch(lambdaOrClosure, new Object[]{lambdaOrClosure});
    }

    public Object call(Object lambdaOrClosure, Object arg)
    {
        return dispatch(lambdaOrClosure, new Object[]{lambdaOrClosure, arg});
    }

    public Object call(Object lambdaOrClosure, Object arg0, Object arg1)
    {
        return dispatch(lambdaOrClosure, new Object[]{lambdaOrClosure, arg0, arg1});
    }

    public Object callWithArgs(Object lambdaOrClosure, Object[] rawArgs)
    {
        Object[] fullArgs = new Object[rawArgs.length + 1];
        fullArgs[0] = lambdaOrClosure;
        System.arraycopy(rawArgs, 0, fullArgs, 1, rawArgs.length);
        return dispatch(lambdaOrClosure, fullArgs);
    }

    private Object dispatch(Object lambdaOrClosure, Object[] args)
    {
        // Identity-cache hot path: same lambda/closure as last call → reuse
        // {@link #cachedTarget} and the inlined DirectCallNode without
        // going through @TruffleBoundary {@link #getCallTarget}.
        if (lambdaOrClosure == cachedLambdaIdentity)
        {
            return directCallNode.call(args);
        }
        RootCallTarget target = getCallTarget(lambdaOrClosure);
        if (target == null)
        {
            return dispatchByPureType(lambdaOrClosure, args);
        }
        if (target == cachedTarget)
        {
            return directCallNode.call(args);
        }
        return slowDispatch(lambdaOrClosure, target, args);
    }

    private Object slowDispatch(Object lambdaOrClosure, RootCallTarget target, Object[] args)
    {
        // First-ever call at this site: install the monomorphic cache.
        if (cachedTarget == null)
        {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            cachedTarget = target;
            cachedLambdaIdentity = lambdaOrClosure;
            directCallNode = insert(DirectCallNode.create(target));
            return directCallNode.call(args);
        }
        // Megamorphic: a different target showed up at this site.
        // Promote to IndirectCallNode — no inlining (target isn't a PE
        // constant) but every other call site keeps its monomorphic
        // DirectCallNode.
        if (indirectCallNode == null)
        {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            indirectCallNode = insert(IndirectCallNode.create());
        }
        return indirectCallNode.call(target, args);
    }

    private static final String LAMBDA_FUNCTION_PATH =
            "meta::pure::metamodel::function::LambdaFunction";
    private static final String PROPERTY_PATH =
            "meta::pure::metamodel::function::property::Property";

    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    private RootCallTarget getCallTarget(Object v)
    {
        if (v instanceof RawClosure rc && rc.callTarget() != null)
        {
            return rc.callTarget();
        }
        if (org.finos.legend.pure.truffle.runtime.dynobj.PureObj.pureTypeIs(v, LAMBDA_FUNCTION_PATH))
        {
            return lookupCallTarget(v);
        }
        return null;
    }

    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    private RootCallTarget lookupCallTarget(Object lambda)
    {
        return getContext().callTargetForLambda(lambda);
    }

    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    private Object dispatchByPureType(Object lambdaOrClosure, Object[] args)
    {
        // {@link TempCompilerPointer} dereference. compile-pure embeds
        if (lambdaOrClosure instanceof RawClosure rc)
        {
            // Get or compile CallTarget, then call
            com.oracle.truffle.api.RootCallTarget ct = rc.callTarget();
            if (ct == null)
            {
                ct = getContext().callTargetForLambda(rc.lambda());
            }
            if (ct != null)
            {
                return ct.call(args);
            }
            throw new RuntimeException("Cannot compile lambda CallTarget");
        }
        if (org.finos.legend.pure.truffle.runtime.dynobj.PureObj.pureTypeIs(lambdaOrClosure, LAMBDA_FUNCTION_PATH))
        {
            com.oracle.truffle.api.RootCallTarget ct = getContext().callTargetForLambda(lambdaOrClosure);
            if (ct != null)
            {
                Object[] fullArgs = new Object[args.length];
                fullArgs[0] = new RawClosure(lambdaOrClosure, new Object[0], new String[0], ct);
                System.arraycopy(args, 1, fullArgs, 1, args.length - 1);
                return ct.call(fullArgs);
            }
            throw new RuntimeException("Cannot compile lambda CallTarget");
        }
        // Property is a leaf (not subtyped by QualifiedProperty here — QPs route
        // through RawUserFunctionCallNode); pureTypeIs is enough.
        if (org.finos.legend.pure.truffle.runtime.dynobj.PureObj.pureTypeIs(lambdaOrClosure, PROPERTY_PATH))
        {
            Object[] rawArgs = extractArgs(args);
            if (rawArgs.length > 0)
            {
                return propertyReader.execute(rawArgs[0],
                        (String) org.finos.legend.pure.truffle.runtime.dynobj.PureObj.readBySlot(lambdaOrClosure, SLOT_NAME));
            }
            return org.finos.legend.pure.truffle.types.PureSequence.EMPTY;
        }
        org.finos.legend.pure.truffle.runtime.TruffleMetadataAccess resolver =
                getContext().resolver();
        if (org.finos.legend.pure.truffle.runtime.dynobj.PureObj.isType(lambdaOrClosure,
                "meta::pure::metamodel::function::FunctionDefinition", resolver))
        {
            Object[] rawArgs = extractArgs(args);
            return getContext().executeFunction(lambdaOrClosure, rawArgs);
        }
        if (org.finos.legend.pure.truffle.runtime.dynobj.PureObj.pureTypeIs(lambdaOrClosure,
                "meta::pure::metamodel::function::NativeFunction"))
        {
            Object[] rawArgs = extractArgs(args);
            return org.finos.legend.pure.truffle.ast.natives.lang.EvalNode.dispatch(getContext(), lambdaOrClosure, rawArgs, propertyReader);
        }
        throw new RuntimeException("Cannot call: " + lambdaOrClosure.getClass().getName());
    }

    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    private static Object[] extractArgs(Object[] args)
    {
        Object[] rawArgs = new Object[args.length - 1];
        System.arraycopy(args, 1, rawArgs, 0, rawArgs.length);
        return rawArgs;
    }
}
