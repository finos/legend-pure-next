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
import org.finos.legend.pure.truffle.ast.PureNode;
import org.finos.legend.pure.truffle.ast.natives.math.IntegerHelper;
import org.finos.legend.pure.truffle.types.ValueAdapter;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code add(T[*], T[1]) : T[1..*]} — append element to collection.
 * {@code add(T[*], Integer[1], T[1]) : T[1..*]} — insert at index.
 */
@NodeInfo(shortName = "add")
public final class AddNode extends PureNode
{
    private static final String SIG = "add";

    @Child
    private PureNode collectionArg;

    @Child
    private PureNode indexOrElementArg;

    @Child
    private PureNode elementArg;

    public AddNode(PureNode collectionArg, PureNode indexOrElementArg, PureNode elementArg)
    {
        this.collectionArg = collectionArg;
        this.indexOrElementArg = indexOrElementArg;
        this.elementArg = elementArg;
    }

    @Override
    public Object executeGeneric(VirtualFrame frame)
    {
        Object col = collectionArg.executeGeneric(frame);
        Object second = indexOrElementArg.executeGeneric(frame);
        if (elementArg != null)
        {
            // Three-arg: add(col, index, element)
            long index = IntegerHelper.asLong(second, SIG);
            Object element = elementArg.executeGeneric(frame);
            return insertAt(col, (int) index, element);
        }
        // Two-arg: add(col, element)
        return append(col, second);
    }

    @TruffleBoundary
    private static ValueSpecification append(Object col, Object element)
    {
        MutableList<ValueSpecification> values = CollectionHelper.values(col);
        List<ValueSpecification> result = new ArrayList<>(values.size() + 1);
        result.addAll(values);
        result.add(ValueAdapter.ensureVS(element));
        return CollectionHelper.makeCollection(result);
    }

    @TruffleBoundary
    private static ValueSpecification insertAt(Object col, int index, Object element)
    {
        MutableList<ValueSpecification> values = CollectionHelper.values(col);
        List<ValueSpecification> result = new ArrayList<>(values.size() + 1);
        result.addAll(values);
        result.add(index, ValueAdapter.ensureVS(element));
        return CollectionHelper.makeCollection(result);
    }
}
