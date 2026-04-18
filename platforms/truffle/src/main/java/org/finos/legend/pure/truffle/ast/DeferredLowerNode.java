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
import meta.pure.metamodel.valuespecification.ValueSpecification;
import org.finos.legend.pure.truffle.TruffleEvaluator;
import org.finos.legend.pure.truffle.runtime.EvaluatorHolder;

/**
 * Fallback AST node for {@link FunctionExpression}s that can't be lowered
 * statically — e.g. a parser-internal {@code _func()==null} placeholder
 * such as the {@code ~} self-construction-reference inside {@code ^T(...)}.
 *
 * <p>Lazy natives ({@code new_}, {@code copy_}) normally evaluate such
 * placeholders themselves via {@code eval.pushConstruction()} and never
 * dispatch through the Truffle lowering at all, but eager body-precompile
 * (Phase 3 {@code TruffleEvaluator.compile}) walks the whole tree and
 * reaches every FE including those nested inside lazy args. Emitting this
 * deferral lets compile succeed; at runtime we just bounce the FE to the
 * Java evaluator.</p>
 */
@NodeInfo(shortName = "deferredLower")
public final class DeferredLowerNode extends PureNode
{
    @CompilationFinal
    private final FunctionExpression fe;

    public DeferredLowerNode(FunctionExpression fe)
    {
        this.fe = fe;
    }

    @Override
    public Object executeGeneric(VirtualFrame frame)
    {
        return evaluate();
    }

    @TruffleBoundary
    private ValueSpecification evaluate()
    {
        return ((TruffleEvaluator) EvaluatorHolder.current()).javaEvaluate(fe);
    }
}
