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
import meta.pure.metamodel.function.FunctionDefinition;
import meta.pure.metamodel.valuespecification.ValueSpecification;
import org.finos.legend.pure.execution.ValueSpecificationEvaluator;
import org.finos.legend.pure.truffle.runtime.EvaluatorHolder;

import org.finos.legend.pure.truffle.types.ValueAdapter;

import java.util.ArrayList;
import java.util.List;

/**
 * Invokes a user-defined {@link FunctionDefinition} with evaluated args.
 * Routes through the evaluator's {@code evaluateFunctionDefinition} method,
 * which handles parameter binding, scope push/pop, closure capture, and
 * expression-sequence execution.
 *
 * <p>Phase B uses the Java interpreter's {@code evaluateFunctionDefinition}.
 * This automatically picks up any Truffle-compiled body because the
 * {@code TruffleEvaluator} subclass routes that method through its AST
 * cache (see Phase B evaluator).</p>
 */
@NodeInfo(shortName = "userCall")
public final class UserFunctionCallNode extends PureNode
{
    @Children
    private final Node[] args;

    @CompilationFinal
    private final FunctionDefinition fd;

    public UserFunctionCallNode(FunctionDefinition fd, PureNode[] args)
    {
        this.fd = fd;
        this.args = java.util.Arrays.copyOf(args, args.length, Node[].class);
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
        eval.pushScope();
        try
        {
            return eval.evaluateFunctionDefinition(fd, evaluatedArgs);
        }
        finally
        {
            eval.popScope();
        }
    }
}
