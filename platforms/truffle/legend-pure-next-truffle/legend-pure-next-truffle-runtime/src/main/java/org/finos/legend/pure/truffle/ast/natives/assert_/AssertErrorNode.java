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
import org.finos.legend.pure.truffle.ast.PureException;
import org.finos.legend.pure.truffle.ast.PureNode;
import org.finos.legend.pure.truffle.ast.RawLambdaCallNode;
import org.finos.legend.pure.truffle.ast.natives.string.StringHelper;
import org.finos.legend.pure.truffle.ast.RawClosure;

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

    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
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
            else if (org.finos.legend.pure.truffle.runtime.dynobj.PureObj.pureTypeIs(fn,
                    "meta::pure::metamodel::function::LambdaFunction"))
            {
                // RawClosure accepts Object lambda — handles both typed
                // LambdaFunction (XPDBHelper) and PureDynamicObject post-flip.
                RawClosure closure = new RawClosure(fn, new Object[0], new String[0], null);
                bodyCallNode.call(closure);
            }
            else
            {
                throw new RuntimeException("assertError: argument is not a function");
            }
            // Pass `this` (the AssertErrorNode) as the location: it carries
            // the Pure source section attached at AST-build time via
            // PureSourceHelper.withSource, so the throwing frame's
            // `frame.getLocation()` returns this node and stack-trace
            // rendering can show the call site. Passing null here lost the
            // source location entirely.
            throw new PureException.AssertionError("Expected error with message containing: '"
                    + expectedMessage + "' but no error was thrown", this);
        }
        catch (PureException.AssertionError pae)
        {
            throw pae;
        }
        catch (Exception e)
        {
            // Bootstrap (Java direct) embeds the Pure stack trace into the
            // exception message via NativeRepository's wrap; Truffle stores it
            // in the polyglot frames instead. Append the formatted Pure stack
            // to actualMessage so cross-engine assertError tests can match
            // against the full `error\nPure stack trace:...` body on both
            // backends.
            String actualMessage = e.getMessage() != null ? e.getMessage() : "";
            if (!actualMessage.contains("\nPure stack trace:"))
            {
                String pureStack = org.finos.legend.pure.truffle.runtime.PureStackFormatter.format(e);
                if (!pureStack.isEmpty())
                {
                    actualMessage = actualMessage + pureStack;
                }
            }
            if (!actualMessage.contains(expectedMessage))
            {
                throw new PureException.AssertionError(
                        "Expected error message containing: '" + expectedMessage
                                + "' but got: '" + actualMessage + "'", this);
            }
            return true;
        }
    }
}
