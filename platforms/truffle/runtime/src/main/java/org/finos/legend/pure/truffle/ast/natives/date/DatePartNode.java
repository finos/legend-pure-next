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

import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.NodeInfo;
import org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.multiplicity.Multiplicity;
import org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.type.generics.GenericType;
import org.finos.legend.pure.truffle.ast.PureNode;
import org.finos.legend.pure.truffle.types.PureDate;

/**
 * {@code datePart(Date[1]) : StrictDate[1]} -- strips the time component from a date.
 * Returns the date-only string directly as a raw value.
 */
@NodeInfo(shortName = "datePart")
public final class DatePartNode extends PureNode
{
    private static final String SIG = "datePart_Date_1__StrictDate_1_";

    @Child
    private PureNode dateArg;

    @CompilationFinal
    private final GenericType genericType;

    @CompilationFinal
    private final Multiplicity multiplicity;

    public DatePartNode(PureNode dateArg, GenericType genericType, Multiplicity multiplicity)
    {
        this.dateArg = dateArg;
        this.genericType = genericType;
        this.multiplicity = multiplicity;
    }

    @Override
    public Object executeGeneric(VirtualFrame frame)
    {
        Object v = dateArg.executeGeneric(frame);
        return extract(v);
    }

    private static PureDate extract(Object v)
    {
        String dateStr = DateHelper.asDateString(v, SIG);
        String datePart = dateStr.contains("T") ? dateStr.substring(0, dateStr.indexOf('T')) : dateStr;
        if (datePart.length() == 4)
        {
            datePart += "-01-01";
        }
        else if (datePart.length() == 7)
        {
            datePart += "-01";
        }
        return new PureDate.StrictDate(datePart);
    }
}
