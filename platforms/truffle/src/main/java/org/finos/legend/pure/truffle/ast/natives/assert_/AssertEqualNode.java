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
import meta.pure.metamodel.valuespecification.ValueSpecification;
import org.finos.legend.pure.execution.NativeRepository;
import org.finos.legend.pure.execution.NativeRepository.PureAssertionError;
import org.finos.legend.pure.truffle.ast.PureNode;
import org.finos.legend.pure.truffle.types.ValueAdapter;

/**
 * {@code assertEqual(Any[*], Any[*]) : Boolean[1]} — asserts structural equality.
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
        ValueSpecification expectedVS = ValueAdapter.ensureVS(expected);
        ValueSpecification actualVS = ValueAdapter.ensureVS(actual);
        if (!NativeRepository.pureEquals(expectedVS, actualVS))
        {
            throw new PureAssertionError("assertEqual failed:\nexpected: "
                    + NativeRepository.pureToString(expectedVS)
                    + "\nactual:   " + NativeRepository.pureToString(actualVS));
        }
        return true;
    }
}
