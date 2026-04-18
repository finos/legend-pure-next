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
import org.finos.legend.pure.execution._E_ValueSpecification;
import org.finos.legend.pure.truffle.ast.PureNode;
import org.finos.legend.pure.truffle.runtime.EvaluatorHolder;
import org.finos.legend.pure.truffle.types.ValueAdapter;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code sort(T[m], Function<{T[1]->U[1]}>[0..1], Function<{U[1],U[1]->Integer[1]}>[0..1]) : T[m]}
 * — sort with optional key extractor and comparator lambdas.
 */
@NodeInfo(shortName = "sort")
public final class SortNode extends PureNode
{
    @Child
    private PureNode collectionArg;

    @Child
    private PureNode keyFnArg;

    @Child
    private PureNode compFnArg;

    public SortNode(PureNode collectionArg, PureNode keyFnArg, PureNode compFnArg)
    {
        this.collectionArg = collectionArg;
        this.keyFnArg = keyFnArg;
        this.compFnArg = compFnArg;
    }

    @Override
    public Object executeGeneric(VirtualFrame frame)
    {
        Object col = collectionArg.executeGeneric(frame);
        Object keyFn = keyFnArg != null ? keyFnArg.executeGeneric(frame) : null;
        Object compFn = compFnArg != null ? compFnArg.executeGeneric(frame) : null;
        return doSort(col, keyFn, compFn);
    }

    @TruffleBoundary
    private static ValueSpecification doSort(Object col, Object keyFn, Object compFn)
    {
        MutableList<ValueSpecification> values = CollectionHelper.values(col);
        List<ValueSpecification> sorted = new ArrayList<>(values);

        ValueSpecification keyFnVS = keyFn != null ? ValueAdapter.ensureVS(keyFn) : null;
        ValueSpecification compFnVS = compFn != null ? ValueAdapter.ensureVS(compFn) : null;
        boolean hasKeyFn = keyFnVS != null && !CollectionHelper.isEmpty(keyFnVS);
        boolean hasCompFn = compFnVS != null && !CollectionHelper.isEmpty(compFnVS);

        sorted.sort((aVS, bVS) ->
        {
            ValueSpecification kA = aVS;
            ValueSpecification kB = bVS;
            if (hasKeyFn)
            {
                kA = EvaluatorHolder.current().executeFunction(keyFnVS, List.of(aVS));
                kB = EvaluatorHolder.current().executeFunction(keyFnVS, List.of(bVS));
            }
            if (hasCompFn)
            {
                ValueSpecification cmpResult = EvaluatorHolder.current().executeFunction(compFnVS, List.of(kA, kB));
                return ((Number) _E_ValueSpecification.unwrap(cmpResult)).intValue();
            }
            Object rawA = _E_ValueSpecification.unwrap(kA);
            Object rawB = _E_ValueSpecification.unwrap(kB);
            if (rawA instanceof Number nA && rawB instanceof Number nB)
            {
                return Double.compare(nA.doubleValue(), nB.doubleValue());
            }
            if (rawA instanceof Comparable c && rawA.getClass().isInstance(rawB))
            {
                @SuppressWarnings("unchecked")
                int cmp = c.compareTo(rawB);
                return cmp;
            }
            int typeCmp = rawA.getClass().getSimpleName().compareTo(rawB.getClass().getSimpleName());
            if (typeCmp != 0)
            {
                return typeCmp;
            }
            return String.valueOf(rawA).compareTo(String.valueOf(rawB));
        });
        return CollectionHelper.makeCollection(sorted);
    }
}
