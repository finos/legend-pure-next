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

import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.NodeInfo;
import meta.pure.metamodel.multiplicity.Multiplicity;
import meta.pure.metamodel.type.generics.GenericType;
import org.finos.legend.pure.execution.natives.string.StringNatives;
import org.finos.legend.pure.truffle.ast.PureNode;

/**
 * {@code parseDate(String[1]) : Date[1]} -- parses a date string.
 * Returns the parsed date string directly as a raw value.
 */
@NodeInfo(shortName = "parseDate")
public final class ParseDateNode extends PureNode
{
    private static final String SIG = "parseDate_String_1__Date_1_";

    @Child
    private PureNode arg;

    private final GenericType genericType;
    private final Multiplicity multiplicity;

    public ParseDateNode(PureNode arg, GenericType genericType, Multiplicity multiplicity)
    {
        this.arg = arg;
        this.genericType = genericType;
        this.multiplicity = multiplicity;
    }

    @Override
    public Object executeGeneric(VirtualFrame frame)
    {
        String s = StringHelper.asString(arg.executeGeneric(frame), SIG);
        return parseDate(s);
    }

    private static String parseDate(String s)
    {
        String dateStr = s.trim();
        String result = StringNatives.normalizePureDate(dateStr);
        if (result != null && result.endsWith("Z"))
        {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }
}
