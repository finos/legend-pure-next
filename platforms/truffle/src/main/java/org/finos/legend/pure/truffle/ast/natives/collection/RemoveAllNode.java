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

package org.finos.legend.pure.truffle.ast.natives.collection;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.NodeInfo;
import meta.pure.metamodel.valuespecification.ValueSpecification;
import org.eclipse.collections.api.list.MutableList;
import org.finos.legend.pure.execution.NativeRepository;
import org.finos.legend.pure.truffle.ast.PureNode;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code removeAll(T[*], T[*]) : T[*]} — set difference using structural equality.
 */
@NodeInfo(shortName = "removeAll")
public final class RemoveAllNode extends PureNode
{
    @Child
    private PureNode setArg;

    @Child
    private PureNode otherArg;

    public RemoveAllNode(PureNode setArg, PureNode otherArg)
    {
        this.setArg = setArg;
        this.otherArg = otherArg;
    }

    @Override
    public Object executeGeneric(VirtualFrame frame)
    {
        Object set = setArg.executeGeneric(frame);
        Object other = otherArg.executeGeneric(frame);
        return doRemoveAll(set, other);
    }

    @TruffleBoundary
    private static ValueSpecification doRemoveAll(Object set, Object other)
    {
        MutableList<ValueSpecification> setVals = CollectionHelper.values(set);
        MutableList<ValueSpecification> otherVals = CollectionHelper.values(other);
        List<ValueSpecification> result = new ArrayList<>();
        for (int i = 0; i < setVals.size(); i++)
        {
            ValueSpecification vs = setVals.get(i);
            boolean found = false;
            for (int j = 0; j < otherVals.size(); j++)
            {
                if (NativeRepository.pureEquals(vs, otherVals.get(j)))
                {
                    found = true;
                    break;
                }
            }
            if (!found)
            {
                result.add(vs);
            }
        }
        return CollectionHelper.makeCollection(result);
    }
}
