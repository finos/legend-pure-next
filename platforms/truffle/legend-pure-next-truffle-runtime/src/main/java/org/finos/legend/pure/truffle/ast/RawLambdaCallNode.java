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
import org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.function.LambdaFunction;
import org.finos.legend.pure.truffle.types.RawClosure;

/**
 * Raw lambda call site — no ValueSpecification anywhere.
 *
 * <p>Monomorphic inline cache: first call records the lambda identity
 * and creates a DirectCallNode. Same-lambda subsequent calls dispatch
 * directly (Graal-inlineable). Different-lambda calls fall back to
 * IndirectCallNode.</p>
 *
 * <p>All arguments and return values are raw Java objects.</p>
 */
public final class RawLambdaCallNode extends Node
{
    @Child
    private DirectCallNode directCallNode;

    @Child
    private IndirectCallNode indirectCallNode;

    @Child
    private PropertyReadNode propertyReader = new PropertyReadNode();

    @CompilerDirectives.CompilationFinal
    private Object cachedTarget;

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
        RootCallTarget target = getCallTarget(lambdaOrClosure);
        if (target == null)
        {
            return fallback(lambdaOrClosure, args);
        }
        // Cache by CallTarget identity (stable) not by closure identity (ephemeral)
        if (directCallNode != null && cachedTarget == target)
        {
            return directCallNode.call(args);
        }
        if (cachedTarget == null)
        {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            cachedTarget = target;
            directCallNode = insert(DirectCallNode.create(target));
            return directCallNode.call(args);
        }
        if (indirectCallNode == null)
        {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            indirectCallNode = insert(IndirectCallNode.create());
        }
        return indirectCallNode.call(target, args);
    }

    private RootCallTarget getCallTarget(Object v)
    {
        if (v instanceof RawClosure rc && rc.callTarget() != null)
        {
            return rc.callTarget();
        }
        if (v instanceof LambdaFunction lambda)
        {
            return lookupCallTarget(lambda);
        }
        return null;
    }

    private RootCallTarget lookupCallTarget(LambdaFunction lambda)
    {
        return getContext().callTargetForLambda(lambda);
    }

    private Object fallback(Object lambdaOrClosure, Object[] args)
    {
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
        if (lambdaOrClosure instanceof LambdaFunction lf)
        {
            com.oracle.truffle.api.RootCallTarget ct = getContext().callTargetForLambda(lf);
            if (ct != null)
            {
                Object[] fullArgs = new Object[args.length];
                fullArgs[0] = new RawClosure(lf, new Object[0], new String[0], ct);
                System.arraycopy(args, 1, fullArgs, 1, args.length - 1);
                return ct.call(fullArgs);
            }
            throw new RuntimeException("Cannot compile lambda CallTarget");
        }
        if (lambdaOrClosure instanceof org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.function.property.Property prop)
        {
            Object[] rawArgs = extractArgs(args);
            if (rawArgs.length > 0)
            {
                return propertyReader.execute(rawArgs[0], prop._name());
            }
            return org.finos.legend.pure.truffle.types.PureSequence.EMPTY;
        }
        if (lambdaOrClosure instanceof org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.function.FunctionDefinition fd)
        {
            Object[] rawArgs = extractArgs(args);
            return getContext().executeFunction(fd, rawArgs);
        }
        if (lambdaOrClosure instanceof org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.function.NativeFunction nf)
        {
            Object[] rawArgs = extractArgs(args);
            return org.finos.legend.pure.truffle.ast.natives.lang.EvalNode.dispatch(getContext(), nf, rawArgs, propertyReader);
        }
        throw new RuntimeException("Cannot call: " + lambdaOrClosure.getClass().getName());
    }

    private static Object[] extractArgs(Object[] args)
    {
        Object[] rawArgs = new Object[args.length - 1];
        System.arraycopy(args, 1, rawArgs, 0, rawArgs.length);
        return rawArgs;
    }
}
