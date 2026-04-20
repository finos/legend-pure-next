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
import org.finos.legend.pure.execution.PureValuePrinter;
import org.finos.legend.pure.truffle.ast.PureNode;

import java.util.Objects;

/**
 * {@code assertEqual(Any[*], Any[*]) : Boolean[1]} -- asserts structural equality.
 */
@NodeInfo(shortName = "assertEqual")
public final class AssertEqualNode extends PureNode
{
    @Child
    private PureNode expectedArg;

    @Child
    private PureNode actualArg;

    public AssertEqualNode(PureNode expectedArg, PureNode actualArg)
    {
        this.expectedArg = expectedArg;
        this.actualArg = actualArg;
    }

    @Override
    public Object executeGeneric(VirtualFrame frame)
    {
        Object expected = expectedArg.executeGeneric(frame);
        Object actual = actualArg.executeGeneric(frame);
        return doAssertEqual(expected, actual);
    }

    @TruffleBoundary
    private static boolean doAssertEqual(Object expected, Object actual)
    {
        if (!deepEquals(expected, actual))
        {
            throw new org.finos.legend.pure.execution.PureAssertionError(
                    "assertEqual failed:\nexpected: "
                    + PureValuePrinter.printForOutput(expected)
                    + "\nactual:   " + PureValuePrinter.printForOutput(actual));
        }
        return true;
    }

    private static boolean deepEquals(Object a, Object b)
    {
        if (a == b)
        {
            return true;
        }
        if (a == null || b == null)
        {
            return false;
        }
        // Numeric coercion: compare numbers by double value
        if (a instanceof Number na && b instanceof Number nb)
        {
            if (a instanceof Long && b instanceof Long)
            {
                return na.longValue() == nb.longValue();
            }
            return Double.compare(na.doubleValue(), nb.doubleValue()) == 0;
        }
        // List comparison (for PureSequence that was normalized to List)
        if (a instanceof java.util.List<?> la && b instanceof java.util.List<?> lb)
        {
            if (la.size() != lb.size())
            {
                return false;
            }
            for (int i = 0; i < la.size(); i++)
            {
                if (!deepEquals(la.get(i), lb.get(i)))
                {
                    return false;
                }
            }
            return true;
        }
        return Objects.equals(a, b);
    }
}
