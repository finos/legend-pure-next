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

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.NodeInfo;
import org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.function.LambdaFunction;
import org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.function.NativeFunction;
import org.finos.legend.pure.truffle.ast.ConstantNode;
import org.finos.legend.pure.truffle.ast.PureNode;
import org.finos.legend.pure.truffle.builder.NativeNodeRegistry;
import org.finos.legend.pure.truffle.runtime.StandaloneEvaluatorHolder;
import org.finos.legend.pure.truffle.types.RawClosure;

/**
 * Generic eval: evaluates all children, treats the first as a function and the
 * rest as arguments, then invokes the function via the standalone evaluator.
 */
@NodeInfo(shortName = "eval")
public final class EvalNode extends PureNode
{
    @Children
    private PureNode[] children;

    public EvalNode(PureNode[] children)
    {
        this.children = children;
    }

    @Override
    public Object executeGeneric(VirtualFrame frame)
    {
        Object[] values = new Object[children.length];
        for (int i = 0; i < children.length; i++)
        {
            values[i] = children[i].executeGeneric(frame);
        }
        return invokeEval(values);
    }

    @TruffleBoundary
    private static Object invokeEval(Object[] values)
    {
        Object fn = values[0];
        if (fn == null || (fn instanceof org.finos.legend.pure.truffle.types.PureSequence ps && ps.isEmpty()))
        {
            return org.finos.legend.pure.truffle.types.PureSequence.EMPTY;
        }
        Object[] args = new Object[values.length - 1];
        System.arraycopy(values, 1, args, 0, args.length);

        return dispatch(fn, args);
    }

    /**
     * Dispatch a function call with pre-unwrapped args. Used by both
     * {@link EvalNode} and {@link EvaluateNode}.
     */
    @TruffleBoundary
    public static Object dispatch(Object fn, Object[] args)
    {
        if (fn instanceof RawClosure rc)
        {
            return StandaloneEvaluatorHolder.current().executeLambda(rc, args);
        }
        if (fn instanceof LambdaFunction lf)
        {
            RawClosure closure = new RawClosure(lf, new Object[0], new String[0], null);
            return StandaloneEvaluatorHolder.current().executeLambda(closure, args);
        }
        if (fn instanceof org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.function.FunctionDefinition fd)
        {
            return StandaloneEvaluatorHolder.current().executeFunction(fd, args);
        }
        if (fn instanceof NativeFunction nf)
        {
            return executeNativeViaRegistry(nf, args);
        }
        if (fn instanceof org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.function.property.Property prop)
        {
            // Property passed as first-class function to eval: access the
            // property on the first argument
            if (args.length > 0)
            {
                return StandaloneEvaluatorHolder.current().accessProperty(args[0], prop._name());
            }
            return org.finos.legend.pure.truffle.types.PureSequence.EMPTY;
        }
        throw new RuntimeException("eval: first argument is not a function: " + fn.getClass().getName());
    }

    @TruffleBoundary
    private static Object executeNativeViaRegistry(NativeFunction nf, Object[] args)
    {
        String signature = nf._name();
        NativeNodeRegistry registry = StandaloneEvaluatorHolder.current().astBuilder().specialized();
        NativeNodeRegistry.Factory factory = registry.lookup(signature);
        if (factory == null)
        {
            throw new RuntimeException("eval: no specialized node for native: " + signature);
        }
        // Wrap each raw arg in a ConstantNode so the factory can build a
        // Truffle node tree. Then execute in a temporary frame.
        PureNode[] argNodes = new PureNode[args.length];
        for (int i = 0; i < args.length; i++)
        {
            argNodes[i] = new ConstantNode(args[i]);
        }
        PureNode node = factory.create(argNodes, null, null, null);
        VirtualFrame tmpFrame = Truffle.getRuntime().createVirtualFrame(
                new Object[0], FrameDescriptor.newBuilder().build());
        return node.executeGeneric(tmpFrame);
    }
}
