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
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.nodes.DirectCallNode;
import com.oracle.truffle.api.nodes.IndirectCallNode;
import com.oracle.truffle.api.nodes.Node;
import meta.pure.metamodel.function.LambdaFunction;
import org.finos.legend.pure.truffle.runtime.StandaloneEvaluatorHolder;
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

    @CompilerDirectives.CompilationFinal
    private Object cachedTarget;

    public Object call(Object lambdaOrClosure, Object arg)
    {
        return dispatch(lambdaOrClosure, new Object[]{lambdaOrClosure, arg});
    }

    public Object call(Object lambdaOrClosure, Object arg0, Object arg1)
    {
        return dispatch(lambdaOrClosure, new Object[]{lambdaOrClosure, arg0, arg1});
    }

    private Object dispatch(Object lambdaOrClosure, Object[] args)
    {
        RootCallTarget target = getCallTarget(lambdaOrClosure);
        if (target == null)
        {
            return fallback(lambdaOrClosure, args);
        }
        if (cachedTarget == lambdaOrClosure && directCallNode != null)
        {
            return directCallNode.call(args);
        }
        if (cachedTarget == null)
        {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            cachedTarget = lambdaOrClosure;
            directCallNode = insert(Truffle.getRuntime().createDirectCallNode(target));
            return directCallNode.call(args);
        }
        if (indirectCallNode == null)
        {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            indirectCallNode = insert(Truffle.getRuntime().createIndirectCallNode());
        }
        return indirectCallNode.call(target, args);
    }

    private static RootCallTarget getCallTarget(Object v)
    {
        if (v instanceof RawClosure rc && rc.callTarget() != null)
        {
            return rc.callTarget();
        }
        if (v instanceof LambdaFunction lambda)
        {
            return lookupCallTarget(lambda);
        }
        // AtomicValue wrapping a LambdaFunction (from bridge path)
        if (v instanceof meta.pure.metamodel.valuespecification.AtomicValue av
                && av._value() instanceof LambdaFunction lambda)
        {
            return lookupCallTarget(lambda);
        }
        return null;
    }

    @TruffleBoundary
    private static RootCallTarget lookupCallTarget(LambdaFunction lambda)
    {
        return StandaloneEvaluatorHolder.current().callTargetForLambda(lambda);
    }

    @TruffleBoundary
    private static Object fallback(Object lambdaOrClosure, Object[] args)
    {
        // Extract the LambdaFunction and execute inline via StandaloneEvaluator
        LambdaFunction lambda;
        if (lambdaOrClosure instanceof RawClosure rc)
        {
            return StandaloneEvaluatorHolder.current().executeLambda(rc, extractArgs(args));
        }
        if (lambdaOrClosure instanceof LambdaFunction lf)
        {
            lambda = lf;
        }
        else if (lambdaOrClosure instanceof meta.pure.metamodel.valuespecification.AtomicValue av
                && av._value() instanceof LambdaFunction lf)
        {
            lambda = lf;
        }
        else if (lambdaOrClosure instanceof meta.pure.metamodel.function.property.Property prop)
        {
            // Property used as first-class function: access the property on the arg
            Object[] rawArgs = extractArgs(args);
            if (rawArgs.length > 0)
            {
                return StandaloneEvaluatorHolder.current().accessProperty(rawArgs[0], prop._name());
            }
            return org.finos.legend.pure.truffle.types.PureSequence.EMPTY;
        }
        else if (lambdaOrClosure instanceof meta.pure.metamodel.function.FunctionDefinition fd)
        {
            // FunctionDefinition used as first-class function
            Object[] rawArgs = extractArgs(args);
            return StandaloneEvaluatorHolder.current().executeFunction(fd, rawArgs);
        }
        else if (lambdaOrClosure instanceof meta.pure.metamodel.function.NativeFunction nf)
        {
            // Native function used as first-class function
            Object[] rawArgs = extractArgs(args);
            return org.finos.legend.pure.truffle.ast.natives.lang.EvalNode.dispatch(nf, rawArgs);
        }
        else
        {
            throw new RuntimeException("Cannot call non-lambda: " + lambdaOrClosure.getClass().getName());
        }
        Object[] rawArgs = extractArgs(args);
        return StandaloneEvaluatorHolder.current().executeLambda(
                new RawClosure(lambda, new Object[0], new String[0], null), rawArgs);
    }

    private static Object[] extractArgs(Object[] args)
    {
        Object[] rawArgs = new Object[args.length - 1];
        System.arraycopy(args, 1, rawArgs, 0, rawArgs.length);
        return rawArgs;
    }
}
