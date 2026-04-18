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
import org.finos.legend.pure.truffle.ast.PureNode;
import org.finos.legend.pure.truffle.runtime.EvaluatorHolder;
import org.finos.legend.pure.truffle.types.ValueAdapter;

import java.util.List;

/**
 * {@code adjust(Date[1], Integer[1], DurationUnit[1]) : Date[1]}
 * — adds or subtracts a duration from a date.
 * Delegates to the bridged native for the complex date arithmetic.
 */
@NodeInfo(shortName = "adjustDate")
public final class AdjustDateNode extends PureNode
{
    private static final String SIG = "adjust_Date_1__Integer_1__DurationUnit_1__Date_1_";

    @Child
    private PureNode dateArg;

    @Child
    private PureNode amountArg;

    @Child
    private PureNode unitArg;

    public AdjustDateNode(PureNode dateArg, PureNode amountArg, PureNode unitArg)
    {
        this.dateArg = dateArg;
        this.amountArg = amountArg;
        this.unitArg = unitArg;
    }

    @Override
    public Object executeGeneric(VirtualFrame frame)
    {
        Object date = dateArg.executeGeneric(frame);
        Object amount = amountArg.executeGeneric(frame);
        Object unit = unitArg.executeGeneric(frame);
        return doAdjust(date, amount, unit);
    }

    @TruffleBoundary
    private static ValueSpecification doAdjust(Object date, Object amount, Object unit)
    {
        ValueSpecification dateVS = ValueAdapter.ensureVS(date);
        ValueSpecification amountVS = ValueAdapter.ensureVS(amount);
        ValueSpecification unitVS = ValueAdapter.ensureVS(unit);
        return EvaluatorHolder.current().natives().execute(
                SIG,
                List.of(dateVS, amountVS, unitVS),
                EvaluatorHolder.current(),
                null,
                null);
    }
}
