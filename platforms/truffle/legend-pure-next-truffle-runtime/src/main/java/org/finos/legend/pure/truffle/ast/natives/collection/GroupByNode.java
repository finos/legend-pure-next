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

import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.NodeInfo;
import org.finos.legend.pure.truffle.StandaloneEvaluator;
import org.finos.legend.pure.truffle.pdb.meta.pure.functions.collection.ListImpl;
import org.finos.legend.pure.truffle.pdb.meta.pure.functions.collection.MapImpl;
import org.finos.legend.pure.truffle.pdb.meta.pure.functions.collection.PairImpl;
import org.eclipse.collections.api.factory.Lists;
import org.finos.legend.pure.truffle.ast.PureNode;
import org.finos.legend.pure.truffle.runtime.TruffleMetadataAccess;
import org.finos.legend.pure.truffle.types.ObjectSequence;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * {@code groupBy(X[*], Function<{X[1]->K[1]}>[1]) : Map<K,List<X>>[1]}
 * -- groups elements by key function result.
 *
 * <p>Iteration is boundary-free via {@link org.finos.legend.pure.truffle.ast.RawLambdaCallNode}.
 * The map/Impl construction step stays behind
 */
@NodeInfo(shortName = "groupBy")
public final class GroupByNode extends PureNode
{
    @Child
    private PureNode collectionArg;

    @Child
    private PureNode keyFnArg;

    @Child
    private org.finos.legend.pure.truffle.ast.RawLambdaCallNode callNode = new org.finos.legend.pure.truffle.ast.RawLambdaCallNode();

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

        int sz = CollectionHelper.size(col);
        Object[] items = new Object[sz];
        Object[] keys = new Object[sz];
        for (int i = 0; i < sz; i++)
        {
            items[i] = CollectionHelper.at(col, i);
            keys[i] = callNode.call(keyFn, items[i]);
        }
        return buildMap(items, keys, sz);
    }

    private static org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.type.generics.GenericTypeValue mapCGT;

    private static org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.type.generics.GenericTypeValue getMapCGT()
    {
        if (mapCGT == null)
        {
            TruffleMetadataAccess resolver = StandaloneEvaluator.INSTANCE.resolver();
            Object mapType = resolver.getElement("meta::pure::functions::collection::Map");
            if (mapType instanceof org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.type.Type t)
            {
                mapCGT = org.finos.legend.pure.truffle.runtime.helper._GenericType.buildUserDefinedGenericType(t, resolver);
            }
            else
            {
                throw new RuntimeException("[GroupByNode] Cannot resolve Map type from PDB");
            }
        }
        return mapCGT;
    }

    private static org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.type.generics.GenericTypeValue listCGT;

    private static org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.type.generics.GenericTypeValue getListCGT()
    {
        if (listCGT == null)
        {
            TruffleMetadataAccess resolver = StandaloneEvaluator.INSTANCE.resolver();
            Object listType = resolver.getElement("meta::pure::functions::collection::List");
            if (listType instanceof org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.type.Type t)
            {
                listCGT = org.finos.legend.pure.truffle.runtime.helper._GenericType.buildUserDefinedGenericType(t, resolver);
            }
            else
            {
                throw new RuntimeException("[GroupByNode] Cannot resolve List type from PDB");
            }
        }
        return listCGT;
    }

    private static Object buildMap(Object[] items, Object[] keys, int sz)
    {
        LinkedHashMap<Object, List<Object>> grouped = new LinkedHashMap<>();
        List<Object> canonicalKeys = new ArrayList<>();

        for (int i = 0; i < sz; i++)
        {
            Object key = keys[i];
            Object canonicalKey = null;
            for (Object ck : canonicalKeys)
            {
                if (Objects.equals(ck, key))
                {
                    canonicalKey = ck;
                    break;
                }
            }
            if (canonicalKey == null)
            {
                canonicalKey = key;
                canonicalKeys.add(canonicalKey);
                grouped.put(canonicalKey, new ArrayList<>());
            }
            grouped.get(canonicalKey).add(items[i]);
        }

        // Build MapImpl backed by LinkedHashMap
        var mapCgt = getMapCGT();
        var listCgt = getListCGT();
        MapImpl mapInstance = new MapImpl();
        mapInstance._classifierGenericType(mapCgt);
        for (Map.Entry<Object, List<Object>> e : grouped.entrySet())
        {
            ListImpl listInstance = new ListImpl();
            listInstance._classifierGenericType(listCgt);
            listInstance._values(new org.finos.legend.pure.truffle.types.ObjectSequence(e.getValue().toArray()));
            mapInstance.put(e.getKey(), listInstance);
        }
        return mapInstance;
    }
}
