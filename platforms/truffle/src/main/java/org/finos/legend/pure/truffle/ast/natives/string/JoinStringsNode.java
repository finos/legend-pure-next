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

import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.NodeInfo;
import meta.pure.metamodel.multiplicity.Multiplicity;
import meta.pure.metamodel.type.generics.GenericType;
import meta.pure.metamodel.valuespecification.AtomicValue;
import meta.pure.metamodel.valuespecification.Collection;
import meta.pure.metamodel.valuespecification.ValueSpecification;
import org.finos.legend.pure.execution.NativeRepository;
import org.finos.legend.pure.truffle.ast.PureNode;
import org.finos.legend.pure.truffle.types.ValueAdapter;

import java.util.List;

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

    @CompilationFinal
    private final GenericType genericType;

    @CompilationFinal
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
    private ValueSpecification join(Object col, String prefix, String separator, String suffix)
    {
        List<? extends ValueSpecification> items;
        if (col instanceof Collection c)
        {
            items = c._values();
        }
        else if (col instanceof AtomicValue av)
        {
            items = av._value() == null
                    ? List.of()
                    : List.of(av);
        }
        else
        {
            items = List.of();
        }

        StringBuilder sb = new StringBuilder();
        if (prefix != null)
        {
            sb.append(prefix);
        }
        for (int i = 0; i < items.size(); i++)
        {
            if (i > 0 && separator != null)
            {
                sb.append(separator);
            }
            sb.append(NativeRepository.pureToString(items.get(i)));
        }
        if (suffix != null)
        {
            sb.append(suffix);
        }
        return ValueAdapter.toVS(sb.toString(), genericType, multiplicity);
    }
}
