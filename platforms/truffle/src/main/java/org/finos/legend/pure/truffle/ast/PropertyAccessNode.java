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
import org.finos.legend.pure.execution.ValueSpecificationEvaluator;
import org.finos.legend.pure.truffle.TruffleEvaluator;
import org.finos.legend.pure.truffle.runtime.EvaluatorHolder;

/**
 * Property / qualified-property access.
 *
 * <p>Phase C uses a "fallback" strategy: re-delegate the whole
 * {@link FunctionExpression} to the Java {@code ValueSpecificationEvaluator}
 * via {@link TruffleEvaluator#javaEvaluate(ValueSpecification)}. This covers
 * both simple {@code AbstractProperty} access (with reflection-cached method
 * lookup) and C3-linearized {@code QualifiedProperty} dispatch in the
 * existing Java code, without duplicating either.</p>
 *
 * <p>Recursive {@code evaluate(arg)} calls inside the Java path still flow
 * back through the Truffle override, so leaf expressions benefit from
 * Truffle AST execution.</p>
 */
@NodeInfo(shortName = "propertyAccess")
public final class PropertyAccessNode extends PureNode
{
    @CompilationFinal
    private final FunctionExpression fe;

    public PropertyAccessNode(FunctionExpression fe)
    {
        this.fe = fe;
    }

    @Override
    public Object executeGeneric(VirtualFrame frame)
    {
        return invoke();
    }

    @TruffleBoundary
    private ValueSpecification invoke()
    {
        ValueSpecificationEvaluator eval = EvaluatorHolder.current();
        if (eval instanceof TruffleEvaluator te)
        {
            return te.javaEvaluate(fe);
        }
        return eval.evaluate(fe);
    }
}
