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

import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.NodeInfo;
import meta.pure.metamodel.multiplicity.Multiplicity;
import meta.pure.metamodel.type.generics.GenericType;
import meta.pure.metamodel.valuespecification.ValueSpecification;
import org.finos.legend.pure.execution.NativeRepository;
import org.finos.legend.pure.execution.ValueSpecificationEvaluator;
import org.finos.legend.pure.truffle.runtime.EvaluatorHolder;

import org.finos.legend.pure.truffle.types.ValueAdapter;

import java.util.ArrayList;
import java.util.List;

/**
 * Invokes a Pure native function via the existing {@link NativeRepository}.
 * The "bridge-first" fallback: every native crosses a {@link TruffleBoundary}
 * and delegates to the Java {@code NativeRepository.NativeImpl} lambda.
 *
 * <p>{@code NativeNodeRegistry} is consulted first; only signatures with no
 * specialized node lower to this one. As the rewrite progresses, the bridge
 * becomes the exception rather than the rule.</p>
 *
 * <p>Args flow into this node as {@link ValueSpecification}. Specialized
 * nodes operate on raw values and must cross {@link
 * org.finos.legend.pure.truffle.types.ValueAdapter} to interoperate — that
 * is the single wrap/unwrap boundary for the whole runtime.</p>
 */
@NodeInfo(shortName = "bridgedNativeCall")
public final class BridgedNativeCallNode extends PureNode
{
    @Children
    private final Node[] args;

    @CompilationFinal
    private final String signature;

    @CompilationFinal
    private final GenericType genericType;

    @CompilationFinal
    private final Multiplicity multiplicity;

    public BridgedNativeCallNode(String signature, PureNode[] args, GenericType genericType, Multiplicity multiplicity)
    {
        this.signature = signature;
        this.args = java.util.Arrays.copyOf(args, args.length, Node[].class);
        this.genericType = genericType;
        this.multiplicity = multiplicity;
    }

    @Override
    @ExplodeLoop
    public Object executeGeneric(VirtualFrame frame)
    {
        List<ValueSpecification> evaluatedArgs = new ArrayList<>(args.length);
        for (int i = 0; i < args.length; i++)
        {
            Object result = ((PureNode) args[i]).executeGeneric(frame);
            evaluatedArgs.add(ValueAdapter.ensureVS(result));
        }
        return invoke(evaluatedArgs);
    }

    @TruffleBoundary
    private ValueSpecification invoke(List<ValueSpecification> evaluatedArgs)
    {
        ValueSpecificationEvaluator eval = EvaluatorHolder.current();
        return eval.natives().execute(signature, evaluatedArgs, eval, genericType, multiplicity);
    }
}
