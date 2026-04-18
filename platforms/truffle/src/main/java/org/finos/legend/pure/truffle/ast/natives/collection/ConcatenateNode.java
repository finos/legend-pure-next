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

import java.util.ArrayList;
import java.util.List;

/**
 * {@code concatenate(T[*], T[*]) : T[*]} — append both element lists into
 * a fresh collection. The output's {@code genericType} and
 * {@code multiplicity} are set by {@link CollectionNatives#makeCollection}
 * using the caller-site {@code FunctionExpression}'s type/multiplicity.
 */
@NodeInfo(shortName = "concatenate")
public final class ConcatenateNode extends PureNode
{
    @Child
    private PureNode left;

    @Child
    private PureNode right;

    public ConcatenateNode(PureNode left, PureNode right)
    {
        this.left = left;
        this.right = right;
    }

    @Override
    public Object executeGeneric(VirtualFrame frame)
    {
        Object a = left.executeGeneric(frame);
        Object b = right.executeGeneric(frame);
        return combine(a, b);
    }

    @TruffleBoundary
    private static ValueSpecification combine(Object a, Object b)
    {
        MutableList<ValueSpecification> aVals = CollectionHelper.values(a);
        MutableList<ValueSpecification> bVals = CollectionHelper.values(b);
        List<ValueSpecification> merged = new ArrayList<>(aVals.size() + bVals.size());
        merged.addAll(aVals);
        merged.addAll(bVals);
        return CollectionHelper.makeCollection(merged);
    }
}
