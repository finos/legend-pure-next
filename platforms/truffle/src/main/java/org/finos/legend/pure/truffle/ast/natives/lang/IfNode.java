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
import meta.pure.metamodel.valuespecification.AtomicValue;
import meta.pure.metamodel.valuespecification.ValueSpecification;
import org.finos.legend.pure.truffle.ast.PureNode;
import org.finos.legend.pure.truffle.runtime.EvaluatorHolder;
import org.finos.legend.pure.truffle.types.ValueAdapter;

import java.util.List;

/**
 * {@code if(Boolean[1], Function<{->T[m]}>[1], Function<{->T[m]}>[1]) : T[m]}.
 *
 * <p>Evaluates the condition eagerly (child 0). Based on the boolean result,
 * evaluates the chosen branch child (child 1 for true, child 2 for false) to
 * obtain a zero-arg lambda, then invokes it via the bridged evaluator.</p>
 */
@NodeInfo(shortName = "if")
public final class IfNode extends PureNode
{
    private static final String SIG = "if_Boolean_1__Function_1__Function_1__T_m_";

    @Child
    private PureNode condition;

    @Child
    private PureNode thenBranch;

    @Child
    private PureNode elseBranch;

    public IfNode(PureNode condition, PureNode thenBranch, PureNode elseBranch)
    {
        this.condition = condition;
        this.thenBranch = thenBranch;
        this.elseBranch = elseBranch;
    }

    @Override
    public Object executeGeneric(VirtualFrame frame)
    {
        // Evaluate all children (matches BridgedNativeCallNode behavior —
        // all args are evaluated before the native sees them). The unchosen
        // branch is evaluated but not invoked.
        Object condVal = condition.executeGeneric(frame);
        Object thenFn = thenBranch.executeGeneric(frame);
        Object elseFn = elseBranch.executeGeneric(frame);
        boolean cond = asBoolean(condVal);
        return invokeBranch(cond ? thenFn : elseFn);
    }

    @TruffleBoundary
    private static Object invokeBranch(Object branchFn)
    {
        ValueSpecification branchVS = ValueAdapter.ensureVS(branchFn);
        return EvaluatorHolder.current().executeFunction(branchVS, List.of());
    }

    private static boolean asBoolean(Object v)
    {
        if (v instanceof Boolean b)
        {
            return b;
        }
        if (v instanceof AtomicValue av && av._value() instanceof Boolean b)
        {
            return b;
        }
        return fallbackBoolean(v);
    }

    @TruffleBoundary
    private static boolean fallbackBoolean(Object v)
    {
        Object raw = ValueAdapter.toRaw(v);
        if (raw instanceof Boolean b)
        {
            return b;
        }
        throw new ClassCastException(SIG + " expected Boolean, got: "
                + (raw == null ? "null" : raw.getClass().getName()));
    }
}
