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

package org.finos.legend.pure.truffle.ast.natives.collection;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.nodes.DirectCallNode;
import com.oracle.truffle.api.nodes.IndirectCallNode;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.RootCallTarget;
import meta.pure.metamodel.function.LambdaFunction;
import meta.pure.metamodel.valuespecification.AtomicValue;
import meta.pure.metamodel.valuespecification.ValueSpecification;
import org.finos.legend.pure.truffle.TruffleEvaluator;
import org.finos.legend.pure.truffle.runtime.EvaluatorHolder;

/**
 * Monomorphic inline-cached lambda call site. Used by the lambda-driven
 * collection natives (map/filter/fold/…) to dispatch to the inner lambda
 * without re-entering the Java evaluator on every iteration.
 *
 * <p>First call records the {@link LambdaFunction} identity and the
 * {@link RootCallTarget} Truffle compiled from its body; subsequent calls
 * check for the same target in the cache and dispatch via a
 * {@link DirectCallNode} that Graal can inline into the enclosing loop.
 * A target-mismatch (a different lambda flowing through the same call
 * site) falls back to a shared {@link IndirectCallNode} — correct, but
 * megamorphic.</p>
 *
 * <p>If the lambda has no compiled CallTarget (e.g. its body hit a
 * non-eligibility condition or a LambdaCompilation error), we cross the
 * legacy {@code @TruffleBoundary} path to {@code eval.executeFunction}.
 * That path matches Phase 7.2 semantics exactly — it's just slower.</p>
 */
public final class LambdaCallNode extends Node
{
    @Child
    private DirectCallNode directCallNode;

    @Child
    private IndirectCallNode indirectCallNode;

    @CompilerDirectives.CompilationFinal
    private LambdaFunction cachedLambda;

    public ValueSpecification call(Object lambdaVS, Object arg)
    {
        return dispatch(lambdaVS, new Object[]{null, arg});
    }

    public ValueSpecification call(Object lambdaVS, Object arg0, Object arg1)
    {
        return dispatch(lambdaVS, new Object[]{null, arg0, arg1});
    }

    private ValueSpecification dispatch(Object lambdaVS, Object[] args)
    {
        Object unwrapped = lambdaVS instanceof AtomicValue av ? av._value() : lambdaVS;
        if (!(unwrapped instanceof LambdaFunction lambda))
        {
            return fallback(lambdaVS, args);
        }
        RootCallTarget target = callTargetFor(lambda);
        if (target == null)
        {
            return fallback(lambdaVS, args);
        }
        args[0] = unwrapped;
        if (cachedLambda == lambda && directCallNode != null)
        {
            return (ValueSpecification) directCallNode.call(args);
        }
        if (cachedLambda == null)
        {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            cachedLambda = lambda;
            directCallNode = insert(Truffle.getRuntime().createDirectCallNode(target));
            return (ValueSpecification) directCallNode.call(args);
        }
        if (indirectCallNode == null)
        {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            indirectCallNode = insert(Truffle.getRuntime().createIndirectCallNode());
        }
        return (ValueSpecification) indirectCallNode.call(target, args);
    }

    @TruffleBoundary
    private static RootCallTarget callTargetFor(LambdaFunction lambda)
    {
        return ((TruffleEvaluator) EvaluatorHolder.current()).callTargetForLambda(lambda);
    }

    @TruffleBoundary
    private static ValueSpecification fallback(Object lambdaVS, Object[] args)
    {
        ValueSpecification lambdaAsVS = org.finos.legend.pure.truffle.types.ValueAdapter.ensureVS(lambdaVS);
        java.util.List<ValueSpecification> list = new java.util.ArrayList<>(args.length - 1);
        for (int i = 1; i < args.length; i++)
        {
            list.add((ValueSpecification) args[i]);
        }
        return EvaluatorHolder.current().executeFunction(lambdaAsVS, list);
    }
}
