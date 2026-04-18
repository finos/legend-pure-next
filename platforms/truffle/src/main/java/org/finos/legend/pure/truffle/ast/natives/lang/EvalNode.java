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
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.NodeInfo;
import meta.pure.metamodel.valuespecification.ValueSpecification;
import org.finos.legend.pure.truffle.ast.PureNode;
import org.finos.legend.pure.truffle.runtime.EvaluatorHolder;
import org.finos.legend.pure.truffle.types.ValueAdapter;

import java.util.ArrayList;
import java.util.List;

/**
 * Generic eval: evaluates all children, treats the first as a function and the
 * rest as arguments, then invokes the function via the bridged evaluator.
 *
 * <p>Handles all four {@code eval} overloads with a variable number of
 * children:</p>
 * <ul>
 *   <li>{@code eval_Function_1__V_m_} -- 1 child (fn only)</li>
 *   <li>{@code eval_Function_1__T_n__V_m_} -- 2 children</li>
 *   <li>{@code eval_Function_1__T_n__U_p__V_m_} -- 3 children</li>
 *   <li>{@code eval_Function_1__T_n__U_p__W_q__V_m_} -- 4 children</li>
 * </ul>
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
        ValueSpecification fnVS = ValueAdapter.ensureVS(values[0]);
        List<ValueSpecification> args = new ArrayList<>(values.length - 1);
        for (int i = 1; i < values.length; i++)
        {
            args.add(ValueAdapter.ensureVS(values[i]));
        }
        return EvaluatorHolder.current().executeFunction(fnVS, args);
    }
}
