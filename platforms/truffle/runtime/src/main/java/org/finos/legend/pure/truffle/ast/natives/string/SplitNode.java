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
import org.finos.legend.pure.truffle.ast.PureNode;
import org.finos.legend.pure.truffle.types.ObjectSequence;

import java.util.regex.Pattern;

@NodeInfo(shortName = "split")
public final class SplitNode extends PureNode
{
    private static final String SIG = "split_String_1__String_1__String_MANY_";

    @Child
    private PureNode strArg;

    @Child
    private PureNode delimArg;

    private final GenericType genericType;
    private final Multiplicity multiplicity;

    public SplitNode(PureNode strArg, PureNode delimArg, GenericType genericType, Multiplicity multiplicity)
    {
        this.strArg = strArg;
        this.delimArg = delimArg;
        this.genericType = genericType;
        this.multiplicity = multiplicity;
    }

    @Override
    public Object executeGeneric(VirtualFrame frame)
    {
        String s = StringHelper.asString(strArg.executeGeneric(frame), SIG);
        String delimiter = StringHelper.asString(delimArg.executeGeneric(frame), SIG);
        return doSplit(s, delimiter);
    }

    @TruffleBoundary
    private static Object doSplit(String s, String delimiter)
    {
        String[] parts = s.split(Pattern.quote(delimiter), -1);
        return new ObjectSequence(parts);
    }
}
