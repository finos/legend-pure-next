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
import org.finos.legend.pure.truffle.types.ValueAdapter;

/**
 * {@code indexOf(T[*], T[1]) : Integer[1]} — collection indexOf (NOT string indexOf).
 * Returns the index of the first element equal to the target, or -1 if not found.
 */
@NodeInfo(shortName = "indexOfCol")
public final class IndexOfCollectionNode extends PureNode
{
    @Child
    private PureNode collectionArg;

    @Child
    private PureNode targetArg;

    public IndexOfCollectionNode(PureNode collectionArg, PureNode targetArg)
    {
        this.collectionArg = collectionArg;
        this.targetArg = targetArg;
    }

    @Override
    public Object executeGeneric(VirtualFrame frame)
    {
        Object col = collectionArg.executeGeneric(frame);
        Object target = targetArg.executeGeneric(frame);
        return doIndexOf(col, target);
    }

    @TruffleBoundary
    private static long doIndexOf(Object col, Object target)
    {
        Object rawCol = ValueAdapter.toRaw(col);
        Object rawTarget = ValueAdapter.toRaw(target);
        // Special case: both String → string indexOf
        if (rawCol instanceof String s && rawTarget instanceof String t)
        {
            return (long) s.indexOf(t);
        }
        MutableList<ValueSpecification> values = CollectionHelper.values(col);
        ValueSpecification targetVS = ValueAdapter.ensureVS(target);
        for (int i = 0; i < values.size(); i++)
        {
            if (NativeRepository.pureEquals(values.get(i), targetVS))
            {
                return (long) i;
            }
        }
        return -1L;
    }
}
