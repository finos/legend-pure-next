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
import org.finos.legend.pure.execution.DynamicInstance;
import org.finos.legend.pure.execution.NativeRepository;
import org.finos.legend.pure.execution.PureMap;
import org.finos.legend.pure.execution._E_ValueSpecification;
import org.finos.legend.pure.truffle.ast.PureNode;
import org.finos.legend.pure.truffle.runtime.EvaluatorHolder;
import org.finos.legend.pure.truffle.types.ValueAdapter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code groupBy(X[*], Function<{X[1]->K[1]}>[1]) : Map<K,List<X>>[1]}
 * — groups elements by key function result.
 */
@NodeInfo(shortName = "groupBy")
public final class GroupByNode extends PureNode
{
    @Child
    private PureNode collectionArg;

    @Child
    private PureNode keyFnArg;

    @Child
    private LambdaCallNode callNode = new LambdaCallNode();

    public GroupByNode(PureNode collectionArg, PureNode keyFnArg)
    {
        this.collectionArg = collectionArg;
        this.keyFnArg = keyFnArg;
    }

    @Override
    public Object executeGeneric(VirtualFrame frame)
    {
        Object col = collectionArg.executeGeneric(frame);
        Object keyFn = keyFnArg.executeGeneric(frame);
        return doGroupBy(col, keyFn);
    }

    @TruffleBoundary
    private ValueSpecification doGroupBy(Object col, Object keyFn)
    {
        MutableList<ValueSpecification> values = CollectionHelper.values(col);
        ValueSpecification keyFnVS = ValueAdapter.ensureVS(keyFn);

        LinkedHashMap<ValueSpecification, List<ValueSpecification>> grouped = new LinkedHashMap<>();
        for (int i = 0; i < values.size(); i++)
        {
            ValueSpecification itemVS = values.get(i);
            ValueSpecification key = EvaluatorHolder.current().executeFunction(keyFnVS, List.of(itemVS));
            ValueSpecification canonicalKey = null;
            for (ValueSpecification k : grouped.keySet())
            {
                if (NativeRepository.pureEquals(k, key))
                {
                    canonicalKey = k;
                    break;
                }
            }
            if (canonicalKey == null)
            {
                canonicalKey = key;
                grouped.put(canonicalKey, new ArrayList<>());
            }
            grouped.get(canonicalKey).add(itemVS);
        }

        LinkedHashMap<ValueSpecification, ValueSpecification> resultMap = new LinkedHashMap<>();
        org.finos.legend.pure.m3.module.MetadataAccess resolver = EvaluatorHolder.current().natives().resolver();
        for (Map.Entry<ValueSpecification, List<ValueSpecification>> e : grouped.entrySet())
        {
            DynamicInstance listInstance = new DynamicInstance("meta::pure::functions::collection::List");
            listInstance.put("values", CollectionHelper.makeCollection(e.getValue()));
            resultMap.put(e.getKey(), _E_ValueSpecification.wrap(listInstance, null, null, resolver));
        }
        return _E_ValueSpecification.wrap(new PureMap(resultMap), null, null, resolver);
    }
}
