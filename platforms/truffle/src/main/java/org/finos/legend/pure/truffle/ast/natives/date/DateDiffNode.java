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

package org.finos.legend.pure.truffle.ast.natives.date;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.NodeInfo;
import meta.pure.metamodel.valuespecification.ValueSpecification;
import org.finos.legend.pure.execution._E_ValueSpecification;
import org.finos.legend.pure.truffle.ast.PureNode;
import org.finos.legend.pure.truffle.runtime.EvaluatorHolder;
import org.finos.legend.pure.truffle.types.ValueAdapter;

import java.util.List;

/**
 * {@code dateDiff(Date[1], Date[1], DurationUnit[1]) : Integer[1]}
 * — difference between two dates in the given unit.
 * Delegates to the bridged native for the complex unit-based calculation.
 */
@NodeInfo(shortName = "dateDiff")
public final class DateDiffNode extends PureNode
{
    private static final String SIG = "dateDiff_Date_1__Date_1__DurationUnit_1__Integer_1_";

    @Child
    private PureNode date1Arg;

    @Child
    private PureNode date2Arg;

    @Child
    private PureNode unitArg;

    public DateDiffNode(PureNode date1Arg, PureNode date2Arg, PureNode unitArg)
    {
        this.date1Arg = date1Arg;
        this.date2Arg = date2Arg;
        this.unitArg = unitArg;
    }

    @Override
    public Object executeGeneric(VirtualFrame frame)
    {
        Object d1 = date1Arg.executeGeneric(frame);
        Object d2 = date2Arg.executeGeneric(frame);
        Object unit = unitArg.executeGeneric(frame);
        return doDiff(d1, d2, unit);
    }

    @TruffleBoundary
    private static ValueSpecification doDiff(Object d1, Object d2, Object unit)
    {
        ValueSpecification d1VS = ValueAdapter.ensureVS(d1);
        ValueSpecification d2VS = ValueAdapter.ensureVS(d2);
        ValueSpecification unitVS = ValueAdapter.ensureVS(unit);
        return EvaluatorHolder.current().natives().execute(
                SIG,
                List.of(d1VS, d2VS, unitVS),
                EvaluatorHolder.current(),
                null,
                null);
    }
}
