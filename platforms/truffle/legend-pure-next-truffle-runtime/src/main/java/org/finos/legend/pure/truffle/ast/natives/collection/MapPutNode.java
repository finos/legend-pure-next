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

/**
 * {@code put(Map[1], U[1], V[1]) : Map[1]} -- inserts a key-value pair into a map.
 */
@NodeInfo(shortName = "mapPut")
public final class MapPutNode extends PureNode
{
    @Child
    private PureNode mapArg;

    @Child
    private PureNode keyArg;

    @Child
    private PureNode valueArg;

    public MapPutNode(PureNode mapArg, PureNode keyArg, PureNode valueArg)
    {
        this.mapArg = mapArg;
        this.keyArg = keyArg;
        this.valueArg = valueArg;
    }

    @Override
    public Object executeGeneric(VirtualFrame frame)
    {
        Object map = mapArg.executeGeneric(frame);
        Object key = keyArg.executeGeneric(frame);
        Object value = valueArg.executeGeneric(frame);
        var cgt = getContext().cgtForType("meta::pure::functions::collection::Map");
        if (cgt == null)
        {
            throw new RuntimeException("[MapPutNode] Cannot resolve Map type from PDB");
        }
        return doPut(map, key, value, cgt);
    }

    private static Object doPut(Object map, Object key, Object value, org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.type.generics.GenericTypeValue mapCGT)
    {
        MapImpl newMap = new MapImpl();
        newMap._classifierGenericType(mapCGT);
        if (map instanceof MapImpl mi)
        {
            newMap.putAll(mi);
        }
        newMap.put(key, value);
        return newMap;
    }
}
