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
import com.oracle.truffle.api.nodes.NodeInfo;
import meta.pure.metamodel.valuespecification.FunctionExpression;
import org.finos.legend.pure.execution.ValueSpecificationEvaluator;
import org.finos.legend.pure.truffle.runtime.EvaluatorHolder;

/**
 * Invokes a lazy native (short-circuit boolean operators like {@code and_},
 * {@code or_}, and special constructors {@code new_}, {@code copy_}).
 *
 * <p>Unlike {@link BridgedNativeCallNode}, a lazy native receives the unevaluated
 * {@link FunctionExpression} so it can control which arguments are evaluated
 * and in what order. The native's implementation calls back into the
 * evaluator via {@code eval.evaluate(arg)}, which is routed through Truffle
 * by {@code TruffleEvaluator.evaluate}.</p>
 */
@NodeInfo(shortName = "lazyNativeCall")
public final class LazyNativeCallNode extends PureNode
{
    @CompilationFinal
    private final String signature;

    @CompilationFinal
    private final FunctionExpression fe;

    public LazyNativeCallNode(String signature, FunctionExpression fe)
    {
        this.signature = signature;
        this.fe = fe;
    }

    @Override
    public Object executeGeneric(VirtualFrame frame)
    {
        return invoke();
    }

    @TruffleBoundary
    private Object invoke()
    {
        ValueSpecificationEvaluator eval = EvaluatorHolder.current();
        return eval.natives().executeLazy(signature, eval, fe);
    }
}
