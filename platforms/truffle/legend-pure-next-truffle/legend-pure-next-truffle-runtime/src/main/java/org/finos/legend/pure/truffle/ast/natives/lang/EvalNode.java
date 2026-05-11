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

import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import com.oracle.truffle.api.nodes.NodeInfo;
import org.finos.legend.pure.truffle.ast.AtomicValueNode;
import org.finos.legend.pure.truffle.ast.PureNode;
import org.finos.legend.pure.truffle.ast.RawLambdaCallNode;
import org.finos.legend.pure.truffle.builder.NativeNodeRegistry;
import org.finos.legend.pure.truffle.ast.RawClosure;

/**
 * Generic eval: evaluates all children, treats the first as a function and the
 * rest as arguments, then invokes the function via the standalone evaluator.
 */
@NodeInfo(shortName = "eval")
public final class EvalNode extends PureNode
{
    @Children
    private PureNode[] children;

    @Child
    private RawLambdaCallNode evalCallNode = new RawLambdaCallNode();

    public EvalNode(PureNode[] children)
    {
        this.children = children;
    }

    @Override
    public Object executeGeneric(VirtualFrame frame)
    {
        Object[] values = evaluateChildren(frame);
        return invokeEval(values);
    }

    @ExplodeLoop
    private Object[] evaluateChildren(VirtualFrame frame)
    {
        Object[] values = new Object[children.length];
        for (int i = 0; i < children.length; i++)
        {
            values[i] = children[i].executeGeneric(frame);
        }
        return values;
    }

    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    private Object invokeEval(Object[] values)
    {
        Object fn = values[0];
        if (fn == null || (fn instanceof org.finos.legend.pure.truffle.types.PureSequence ps && ps.isEmpty()))
        {
            return org.finos.legend.pure.truffle.types.PureSequence.EMPTY;
        }
        Object[] args = new Object[values.length - 1];
        System.arraycopy(values, 1, args, 0, args.length);

        return evalCallNode.callWithArgs(fn, args);
    }

    /**
     * Dispatch a function call with pre-unwrapped args. Used by both
     * {@link EvalNode} and {@link EvaluateNode}.
     */
    public static Object dispatch(org.finos.legend.pure.truffle.PureContext evaluator, Object fn, Object[] args,
                                    org.finos.legend.pure.truffle.ast.PropertyReadNode propertyReader)
    {
        if (fn instanceof RawClosure rc)
        {
            com.oracle.truffle.api.RootCallTarget ct = rc.callTarget();
            if (ct == null) ct = evaluator.callTargetForLambda(rc.lambda());
            if (ct != null)
            {
                Object[] fullArgs = new Object[args.length + 1];
                fullArgs[0] = rc;
                System.arraycopy(args, 0, fullArgs, 1, args.length);
                return ct.call(fullArgs);
            }
            throw new RuntimeException("Cannot compile lambda CallTarget");
        }
        if (org.finos.legend.pure.truffle.runtime.dynobj.PureObj.pureTypeIs(fn,
                "meta::pure::metamodel::function::LambdaFunction"))
        {
            com.oracle.truffle.api.RootCallTarget ct = evaluator.callTargetForLambda(fn);
            if (ct != null)
            {
                RawClosure closure = new RawClosure(fn, new Object[0], new String[0], ct);
                Object[] fullArgs = new Object[args.length + 1];
                fullArgs[0] = closure;
                System.arraycopy(args, 0, fullArgs, 1, args.length);
                return ct.call(fullArgs);
            }
            throw new RuntimeException("Cannot compile lambda CallTarget");
        }
        // Property is a leaf — pureTypeIs is enough (QualifiedProperty routes
        // to the FunctionDefinition branch below since it extends both).
        if (org.finos.legend.pure.truffle.runtime.dynobj.PureObj.pureTypeIs(fn,
                "meta::pure::metamodel::function::property::Property"))
        {
            if (args.length > 0)
            {
                return propertyReader.execute(args[0],
                        (String) org.finos.legend.pure.truffle.runtime.dynobj.PureObj.read(fn, "name"));
            }
            return org.finos.legend.pure.truffle.types.PureSequence.EMPTY;
        }
        org.finos.legend.pure.truffle.runtime.TruffleMetadataAccess resolver = evaluator.resolver();
        if (org.finos.legend.pure.truffle.runtime.dynobj.PureObj.isType(fn,
                "meta::pure::metamodel::function::FunctionDefinition", resolver))
        {
            return evaluator.executeFunction(fn, args);
        }
        // NativeFunction (leaf concrete type) — match via Pure path.
        if (org.finos.legend.pure.truffle.runtime.dynobj.PureObj.pureTypeIs(fn,
                "meta::pure::metamodel::function::NativeFunction"))
        {
            return executeNativeViaRegistry(evaluator, fn, args);
        }
        throw new RuntimeException("eval: first argument is not a function: " + fn.getClass().getName());
    }

    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    private static Object executeNativeViaRegistry(org.finos.legend.pure.truffle.PureContext evaluator, Object nf, Object[] args)
    {
        String signature = (String) org.finos.legend.pure.truffle.runtime.dynobj.PureObj.read(nf, "name");
        NativeNodeRegistry registry = evaluator.astBuilder().specialized();
        NativeNodeRegistry.Factory factory = registry.lookup(signature);
        if (factory == null)
        {
            throw new RuntimeException("eval: no specialized node for native: " + signature);
        }
        // Wrap each raw arg in an AtomicValueNode so the factory can build a
        // Truffle node tree. Then execute in a temporary frame.
        PureNode[] argNodes = new PureNode[args.length];
        for (int i = 0; i < args.length; i++)
        {
            argNodes[i] = new AtomicValueNode(args[i]);
        }
        PureNode node = factory.create(argNodes, null, null, null);
        VirtualFrame tmpFrame = Truffle.getRuntime().createVirtualFrame(
                new Object[0], FrameDescriptor.newBuilder().build());
        return node.executeGeneric(tmpFrame);
    }
}
