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

package org.finos.legend.pure.truffle.ast.natives.assert_;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.NodeInfo;
import org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.function.LambdaFunction;
import org.finos.legend.pure.execution.PureAssertionError;
import org.finos.legend.pure.truffle.ast.PureNode;
import org.finos.legend.pure.truffle.runtime.StandaloneEvaluatorHolder;
import org.finos.legend.pure.truffle.types.RawClosure;

/**
 * {@code assert(Boolean[1], Function<{->String[1]}>[1]) : Boolean[1]} -- assert with lazy message.
 * {@code assert(Boolean[1]) : Boolean[1]} -- assert without message.
 */
@NodeInfo(shortName = "assert")
public final class AssertNode extends PureNode
{
    @Child
    private PureNode conditionArg;

    @Child
    private PureNode messageFnArg;

    public AssertNode(PureNode conditionArg, PureNode messageFnArg)
    {
        this.conditionArg = conditionArg;
        this.messageFnArg = messageFnArg;
    }

    @Override
    public Object executeGeneric(VirtualFrame frame)
    {
        Object cond = conditionArg.executeGeneric(frame);
        boolean condition = asBoolean(cond);
        if (!condition)
        {
            if (messageFnArg != null)
            {
                Object msgFn = messageFnArg.executeGeneric(frame);
                throwWithMessage(msgFn, this);
            }
            else
            {
                throw new org.finos.legend.pure.truffle.ast.PureException.AssertionError("Assert failed", this);
            }
        }
        return true;
    }

    @TruffleBoundary
    private static void throwWithMessage(Object msgFn, com.oracle.truffle.api.nodes.Node location)
    {
        Object message;
        if (msgFn instanceof RawClosure rc)
        {
            message = StandaloneEvaluatorHolder.current().executeLambda(rc, new Object[0]);
        }
        else if (msgFn instanceof LambdaFunction lf)
        {
            RawClosure closure = new RawClosure(lf, new Object[0], new String[0], null);
            message = StandaloneEvaluatorHolder.current().executeLambda(closure, new Object[0]);
        }
        else
        {
            message = String.valueOf(msgFn);
        }
        throw new org.finos.legend.pure.truffle.ast.PureException.AssertionError(String.valueOf(message), location);
    }

    private static boolean asBoolean(Object v)
    {
        if (v instanceof Boolean b)
        {
            return b;
        }
        return false;
    }
}
