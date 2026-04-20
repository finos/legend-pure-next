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
import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.NodeInfo;
import meta.pure.metamodel.function.LambdaFunction;
import org.finos.legend.pure.truffle.frame.FrameLayout;
import org.finos.legend.pure.truffle.runtime.StandaloneEvaluatorHolder;
import org.finos.legend.pure.truffle.types.RawClosure;

/**
 * Captures open variables from the enclosing frame and produces a
 * {@link RawClosure}. The closure carries the lambda IR reference,
 * the captured raw values, the variable names, and an optional
 * pre-compiled RootCallTarget.
 */
@NodeInfo(shortName = "lambdaCapture")
public final class RawLambdaCaptureNode extends PureNode
{
    private final LambdaFunction lambda;

    @CompilationFinal(dimensions = 1)
    private final String[] openVarNames;

    @CompilationFinal(dimensions = 1)
    private final int[] openVarSlots;

    public RawLambdaCaptureNode(LambdaFunction lambda,
                                org.eclipse.collections.api.list.MutableList<meta.pure.metamodel.valuespecification.VariableExpression> openVars,
                                FrameLayout layout)
    {
        this.lambda = lambda;
        this.openVarNames = new String[openVars.size()];
        this.openVarSlots = new int[openVars.size()];
        for (int i = 0; i < openVars.size(); i++)
        {
            String name = openVars.get(i)._name();
            openVarNames[i] = name;
            Integer slot = layout != null ? layout.slotFor(name) : null;
            openVarSlots[i] = slot != null ? slot : -1;
        }
    }

    @Override
    public Object executeGeneric(VirtualFrame frame)
    {
        Object[] capturedValues = new Object[openVarNames.length];
        for (int i = 0; i < openVarNames.length; i++)
        {
            if (openVarSlots[i] >= 0)
            {
                capturedValues[i] = frame.getObject(openVarSlots[i]);
            }
        }
        RootCallTarget ct = lookupCallTarget();
        return new RawClosure(lambda, capturedValues, openVarNames, ct);
    }

    private RootCallTarget lookupCallTarget()
    {
        return StandaloneEvaluatorHolder.current().callTargetForLambda(lambda);
    }
}
