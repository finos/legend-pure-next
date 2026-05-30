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
import org.finos.legend.pure.truffle.ast.PureNode;
import org.finos.legend.pure.truffle.runtime.TruffleMetadataAccess;

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

    @com.oracle.truffle.api.CompilerDirectives.CompilationFinal
    private Object cachedMapCgt;

    @com.oracle.truffle.api.CompilerDirectives.CompilationFinal
    private Object cachedListCgt;

    @Override
    public Object executeGeneric(VirtualFrame frame)
    {
        Object col = collectionArg.executeGeneric(frame);
        int sz = CollectionHelper.size(col);
        if (sz == 0)
        {
            return buildMap(new Object[0], new Object[0], 0, lookupMapCgt(), lookupListCgt(), getResolver());
        }
        Object keyFn = keyFnArg.executeGeneric(frame);

        Object[] items = new Object[sz];
        Object[] keys = new Object[sz];
        for (int i = 0; i < sz; i++)
        {
            items[i] = CollectionHelper.at(col, i);
            keys[i] = callNode.call(keyFn, items[i]);
        }
        return buildMap(items, keys, sz, lookupMapCgt(), lookupListCgt(), getResolver());
    }

    private Object lookupMapCgt()
    {
        Object cgt = cachedMapCgt;
        if (cgt == null)
        {
            com.oracle.truffle.api.CompilerDirectives.transferToInterpreterAndInvalidate();
            cgt = populateMapCgt();
        }
        return cgt;
    }

    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    private Object populateMapCgt()
    {
        Object cgt = getContext().cgtForType("meta::pure::functions::collection::Map");
        if (cgt == null) throw new RuntimeException("[GroupByNode] Cannot resolve Map type from PDB");
        cachedMapCgt = cgt;
        return cgt;
    }

    private Object lookupListCgt()
    {
        Object cgt = cachedListCgt;
        if (cgt == null)
        {
            com.oracle.truffle.api.CompilerDirectives.transferToInterpreterAndInvalidate();
            cgt = populateListCgt();
        }
        return cgt;
    }

    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    private Object populateListCgt()
    {
        Object cgt = getContext().cgtForType("meta::pure::functions::collection::List");
        if (cgt == null) throw new RuntimeException("[GroupByNode] Cannot resolve List type from PDB");
        cachedListCgt = cgt;
        return cgt;
    }

    /** Shared with {@link InlineGroupByNode}: build a Map<K, List<X>> from
     *  pre-computed (item, key) arrays. Resolves Map/List CGTs through the
     *  current Pure context. */
    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    static Object buildFromKeys(Object[] items, Object[] keys, int sz,
                                org.finos.legend.pure.truffle.runtime.TruffleMetadataAccess resolver)
    {
        org.finos.legend.pure.truffle.PureContext ctx = org.finos.legend.pure.truffle.PureLanguage.get(null);
        Object mapCgt = ctx.cgtForType("meta::pure::functions::collection::Map");
        Object listCgt = ctx.cgtForType("meta::pure::functions::collection::List");
        if (mapCgt == null || listCgt == null)
        {
            throw new RuntimeException("[GroupByNode] Cannot resolve Map/List type from PDB");
        }
        return buildMap(items, keys, sz, mapCgt, listCgt, resolver);
    }

    /** Empty-collection fast path for {@link InlineGroupByNode}. */
    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    static Object buildEmpty(org.finos.legend.pure.truffle.runtime.TruffleMetadataAccess resolver)
    {
        return buildFromKeys(new Object[0], new Object[0], 0, resolver);
    }

    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    private static Object buildMap(Object[] items, Object[] keys, int sz,
                                   Object mapCgt,
                                   Object listCgt,
                                   org.finos.legend.pure.truffle.runtime.TruffleMetadataAccess resolver)
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
        MapImpl mapInstance = new MapImpl();
        org.finos.legend.pure.truffle.runtime.dynobj.PureObj.write(mapInstance, "classifierGenericType", mapCgt);
        for (Map.Entry<Object, List<Object>> e : grouped.entrySet())
        {
            Object listInstance = org.finos.legend.pure.truffle.runtime.TruffleInstanceFactory.createInstance(
                    "meta::pure::functions::collection::List", resolver);
            org.finos.legend.pure.truffle.runtime.dynobj.PureObj.write(listInstance, "classifierGenericType", listCgt);
            org.finos.legend.pure.truffle.runtime.dynobj.PureObj.write(listInstance, "values",
                    new org.finos.legend.pure.truffle.types.ObjectSequence(e.getValue().toArray()));
            mapInstance.put(e.getKey(), listInstance);
        }
        return mapInstance;
    }
}
