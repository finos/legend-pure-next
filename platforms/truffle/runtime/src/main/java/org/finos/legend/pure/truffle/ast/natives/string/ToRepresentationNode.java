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

package org.finos.legend.pure.truffle.ast.natives.string;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.NodeInfo;
import org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.multiplicity.Multiplicity;
import org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.type.generics.GenericType;
import org.finos.legend.pure.execution.PureValuePrinter;
import org.finos.legend.pure.truffle.ast.PureNode;
import org.finos.legend.pure.truffle.types.PureDate;

/**
 * {@code toRepresentation(Any[1]) : String[1]} -- returns the Pure-syntax
 * representation of a value.
 */
@NodeInfo(shortName = "toRepr")
public final class ToRepresentationNode extends PureNode
{
    @Child
    private PureNode arg;

    private final GenericType genericType;
    private final Multiplicity multiplicity;

    public ToRepresentationNode(PureNode arg, GenericType genericType, Multiplicity multiplicity)
    {
        this.arg = arg;
        this.genericType = genericType;
        this.multiplicity = multiplicity;
    }

    @Override
    public Object executeGeneric(VirtualFrame frame)
    {
        Object v = arg.executeGeneric(frame);
        return convert(v);
    }

    @TruffleBoundary
    private static String convert(Object v)
    {
        if (v instanceof PureDate pd)
        {
            return "%" + ToStringNode.normalizeDateString(pd.dateString());
        }
        // Date strings → %yyyy-MM-dd format
        if (v instanceof String s && ToStringNode.isDateString(s))
        {
            return "%" + ToStringNode.normalizeDateString(s);
        }
        // PureSequence of size 1 → unwrap and convert the single element
        if (v instanceof org.finos.legend.pure.truffle.types.PureSequence seq && seq.size() == 1)
        {
            return convert(seq.getBoxed(0));
        }
        if (v instanceof java.time.LocalDate ld)
        {
            return "%" + ToStringNode.formatStrictDate(ld);
        }
        if (v instanceof java.time.ZonedDateTime zdt)
        {
            return "%" + ToStringNode.formatDateTime(zdt);
        }
        if (v instanceof String s)
        {
            if (ToStringNode.isDateString(s)) return "%" + ToStringNode.normalizeDateString(s);
            return "'" + s.replace("'", "\\'") + "'";
        }
        if (v == null)
        {
            return "[]";
        }
        // Numbers, booleans — use their toString
        if (v instanceof Number || v instanceof Boolean)
        {
            return ToStringNode.pureToString(v);
        }
        return PureValuePrinter.printForOutput(v);
    }
}
