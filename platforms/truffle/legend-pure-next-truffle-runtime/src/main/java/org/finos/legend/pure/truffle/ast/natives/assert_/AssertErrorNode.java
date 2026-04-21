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

import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.NodeInfo;
import org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.function.LambdaFunction;
import org.finos.legend.pure.truffle.ast.PureException;
import org.finos.legend.pure.truffle.ast.PureNode;
import org.finos.legend.pure.truffle.ast.RawLambdaCallNode;
import org.finos.legend.pure.truffle.ast.natives.string.StringHelper;
import org.finos.legend.pure.truffle.types.RawClosure;

/**
 * {@code assertError(Function<{->Any[*]}>[1], String[1], Integer[0..1], Integer[0..1]) : Boolean[1]}
 * -- asserts that the function throws an error containing the expected message.
 */
@NodeInfo(shortName = "assertError")
public final class AssertErrorNode extends PureNode
{
    private static final String SIG = "assertError_Function_1__String_1__Integer_$0_1$__Integer_$0_1$__Boolean_1_";

    @Child
    private PureNode fnArg;

    @Child
    private PureNode msgArg;

    @Child
    private PureNode lineArg;

    @Child
    private PureNode colArg;

    @Child
    private RawLambdaCallNode bodyCallNode = new RawLambdaCallNode();

    public AssertErrorNode(PureNode fnArg, PureNode msgArg, PureNode lineArg, PureNode colArg)
    {
        this.fnArg = fnArg;
        this.msgArg = msgArg;
        this.lineArg = lineArg;
        this.colArg = colArg;
    }

    @Override
    public Object executeGeneric(VirtualFrame frame)
    {
        Object fn = fnArg.executeGeneric(frame);
        String expectedMessage = StringHelper.asString(msgArg.executeGeneric(frame), SIG);
        if (lineArg != null)
        {
            lineArg.executeGeneric(frame);
        }
        if (colArg != null)
        {
            colArg.executeGeneric(frame);
        }
        return doAssertError(fn, expectedMessage);
    }

    private boolean doAssertError(Object rawFn, String expectedMessage)
    {
        Object fn = rawFn;
        try
        {
            // Execute the zero-arg function
            if (fn instanceof RawClosure rc)
            {
                bodyCallNode.call(rc);
            }
            else if (fn instanceof LambdaFunction lf)
            {
                RawClosure closure = new RawClosure(lf, new Object[0], new String[0], null);
                bodyCallNode.call(closure);
            }
            else
            {
                throw new RuntimeException("assertError: argument is not a function");
            }
            throw new PureException.AssertionError("Expected error with message containing: '"
                    + expectedMessage + "' but no error was thrown", null);
        }
        catch (PureException.AssertionError pae)
        {
            throw pae;
        }
        catch (Exception e)
        {
            String actualMessage = e.getMessage();
            if (actualMessage == null || !actualMessage.contains(expectedMessage))
            {
                throw new PureException.AssertionError(
                        "Expected error message containing: '" + expectedMessage
                                + "' but got: '" + actualMessage + "'", null);
            }
            return true;
        }
    }
}
