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
import org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.function.LambdaFunction;
import org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.valuespecification.AtomicValue;
import org.finos.legend.pure.truffle.ast.PureNode;
import org.finos.legend.pure.truffle.runtime.StandaloneEvaluatorHolder;
import org.finos.legend.pure.truffle.types.RawClosure;

/**
 * {@code if(Boolean[1], Function<{->T[m]}>[1], Function<{->T[m]}>[1]) : T[m]}.
 *
 * <p>Evaluates the condition eagerly. Based on the boolean result,
 * evaluates the chosen branch and invokes it as a zero-arg lambda.</p>
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
        Object condVal = condition.executeGeneric(frame);
        boolean cond = asBoolean(condVal);
        // Only evaluate the selected branch — lazy evaluation
        // prevents the non-selected branch from reading/modifying frame state
        Object branchFn = cond
                ? thenBranch.executeGeneric(frame)
                : elseBranch.executeGeneric(frame);
        return callExecuteFunction(branchFn);
    }

    @TruffleBoundary
    private static Object callExecuteFunction(Object branchFn)
    {
        if (branchFn instanceof RawClosure rc)
        {
            return StandaloneEvaluatorHolder.current().executeLambda(rc, new Object[0]);
        }
        if (branchFn instanceof LambdaFunction lf)
        {
            RawClosure closure = new RawClosure(lf, new Object[0], new String[0], null);
            return StandaloneEvaluatorHolder.current().executeLambda(closure, new Object[0]);
        }
        // AtomicValue wrapping a LambdaFunction
        if (branchFn instanceof AtomicValue av && av._value() instanceof LambdaFunction lf)
        {
            RawClosure closure = new RawClosure(lf, new Object[0], new String[0], null);
            return StandaloneEvaluatorHolder.current().executeLambda(closure, new Object[0]);
        }
        throw new RuntimeException("if: branch is not a function: " + (branchFn == null ? "null" : branchFn.getClass().getName()));
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
        throw new ClassCastException(SIG + " expected Boolean, got: "
                + (v == null ? "null" : v.getClass().getName()));
    }
}
