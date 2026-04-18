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

package org.finos.legend.pure.truffle.ast;

import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.NodeInfo;
import meta.pure.metamodel.multiplicity.Multiplicity;
import meta.pure.metamodel.type.generics.GenericType;
import meta.pure.metamodel.valuespecification.CollectionImpl;
import meta.pure.metamodel.valuespecification.ValueSpecification;
import org.eclipse.collections.api.factory.Lists;
import org.eclipse.collections.api.list.MutableList;
import org.finos.legend.pure.truffle.types.LongSequence;
import org.finos.legend.pure.truffle.types.DoubleSequence;
import org.finos.legend.pure.truffle.types.ValueAdapter;

/**
 * Evaluates a sequence of element nodes and wraps them in a
 * {@link CollectionImpl} with the collection's original type + multiplicity.
 * Mirrors the {@code Collection col ->} arm of
 * {@code ValueSpecificationEvaluator.evaluate}.
 */
@NodeInfo(shortName = "collection")
public final class CollectionNode extends PureNode
{
    @Children
    private final Node[] elements;

    @CompilationFinal
    private final GenericType genericType;

    @CompilationFinal
    private final Multiplicity multiplicity;

    public CollectionNode(PureNode[] elements, GenericType genericType, Multiplicity multiplicity)
    {
        // Copy into a true Node[] — the runtime array class must be Node[],
        // not a PureNode[] subtype, for native-image's NodeUtil cast to Object[].
        this.elements = java.util.Arrays.copyOf(elements, elements.length, Node[].class);
        this.genericType = genericType;
        this.multiplicity = multiplicity;
    }

    @Override
    @ExplodeLoop
    public Object executeGeneric(VirtualFrame frame)
    {
        int len = elements.length;
        Object[] raw = new Object[len];
        boolean allLong = len > 0;
        boolean allDouble = len > 0;
        for (int i = 0; i < len; i++)
        {
            Object v = ((PureNode) elements[i]).executeGeneric(frame);
            raw[i] = v;
            if (allLong && !(v instanceof Long))
            {
                allLong = false;
            }
            if (allDouble && !(v instanceof Double))
            {
                allDouble = false;
            }
        }
        if (allLong)
        {
            long[] longs = new long[len];
            for (int i = 0; i < len; i++)
            {
                longs[i] = (Long) raw[i];
            }
            return new LongSequence(longs);
        }
        if (allDouble)
        {
            double[] doubles = new double[len];
            for (int i = 0; i < len; i++)
            {
                doubles[i] = (Double) raw[i];
            }
            return new DoubleSequence(doubles);
        }
        MutableList<ValueSpecification> evaluated = Lists.mutable.withInitialCapacity(len);
        for (int i = 0; i < len; i++)
        {
            evaluated.add(ValueAdapter.ensureVS(raw[i]));
        }
        return new CollectionImpl()
                ._values(evaluated)
                ._genericType(genericType)
                ._multiplicity(multiplicity);
    }
}
