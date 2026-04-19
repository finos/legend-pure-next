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
import org.finos.legend.pure.truffle.ast.natives.collection.CollectionHelper;

@NodeInfo(shortName = "joinStrings")
public final class JoinStringsNode extends PureNode
{
    @Child
    private PureNode collectionArg;

    @Child
    private PureNode prefixArg;

    @Child
    private PureNode separatorArg;

    @Child
    private PureNode suffixArg;

    private final GenericType genericType;
    private final Multiplicity multiplicity;

    public JoinStringsNode(PureNode collectionArg, PureNode prefixArg, PureNode separatorArg,
                           PureNode suffixArg, GenericType genericType, Multiplicity multiplicity)
    {
        this.collectionArg = collectionArg;
        this.prefixArg = prefixArg;
        this.separatorArg = separatorArg;
        this.suffixArg = suffixArg;
        this.genericType = genericType;
        this.multiplicity = multiplicity;
    }

    @Override
    public Object executeGeneric(VirtualFrame frame)
    {
        Object col = collectionArg.executeGeneric(frame);
        String prefix = prefixArg != null ? StringHelper.asString(prefixArg.executeGeneric(frame), "joinStrings") : null;
        String separator = separatorArg != null ? StringHelper.asString(separatorArg.executeGeneric(frame), "joinStrings") : null;
        String suffix = suffixArg != null ? StringHelper.asString(suffixArg.executeGeneric(frame), "joinStrings") : null;
        return join(col, prefix, separator, suffix);
    }

    @TruffleBoundary
    private static String join(Object col, String prefix, String separator, String suffix)
    {
        int sz = CollectionHelper.size(col);

        StringBuilder sb = new StringBuilder();
        if (prefix != null)
        {
            sb.append(prefix);
        }
        for (int i = 0; i < sz; i++)
        {
            if (i > 0 && separator != null)
            {
                sb.append(separator);
            }
            Object item = CollectionHelper.at(col, i);
            sb.append(PureValuePrinter.printForOutput(item));
        }
        if (suffix != null)
        {
            sb.append(suffix);
        }
        return sb.toString();
    }
}
